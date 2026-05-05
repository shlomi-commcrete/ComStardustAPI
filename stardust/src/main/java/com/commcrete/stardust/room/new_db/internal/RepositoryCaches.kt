package com.commcrete.stardust.room.new_db.internal

import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns every in-memory cache used by the new_db repository layer:
 *
 *  - **groupIds** — set of normalized group identifiers that resolve to a
 *    GROUP contact + chat. Populated lazily from
 *    [ContactsDao.getResolvedGroupIds] on first access, kept in sync via
 *    [primeGroupIds] (Flow source) and [addGroupId] (write-through).
 *
 *  - **contacts** — map of `normalized userId / deviceId / groupId -> contactId`.
 *    Populated lazily in a single UNION-ALL DAO query
 *    ([ContactsDao.getAllIdToContactRows]) on first access. Updated on every
 *    contact insert via [addContact] so the hot `saveMessage` path can
 *    short-circuit DB lookups.
 *
 *  - **chatIds** — map of received-package cache key -> resolved chatId.
 *    Pure write-through cache; entries are added by the repository after a
 *    successful resolution and dropped wholesale by [removeChatIdsMappedTo]
 *    when the underlying chat is deleted.
 *
 * Thread-safety: each cache is protected by its own [Mutex]. Hot reads use
 * a `@Volatile`-backed init flag to avoid taking the mutex once the cache is
 * primed.
 *
 * All identifiers stored in these caches are normalized via [normalizeId].
 * Callers should normalize lookups themselves; the cache APIs that accept
 * raw input document this explicitly.
 */
internal class RepositoryCaches(
    private val contactsDao: ContactsDao,
) {
    // ── Group IDs ────────────────────────────────────────────────────────
    private val groupIdsMutex = Mutex()
    @Volatile
    private var groupIds: Set<String> = emptySet()
    @Volatile
    private var groupIdsInitialized: Boolean = false

    // ── Contacts (id -> contactId) ───────────────────────────────────────
    private val contactsMutex = Mutex()
    @Volatile
    private var contacts: Map<String, Int> = emptyMap()
    @Volatile
    private var contactsInitialized: Boolean = false

    // ── Chat IDs (received-package key -> chatId) ───────────────────────
    private val chatIdsMutex = Mutex()
    @Volatile
    private var chatIds: Map<String, String> = emptyMap()

    // ─── Group IDs ───────────────────────────────────────────────────────

    /** Returns all known group IDs, initializing the cache from DAO on first call. */
    suspend fun getGroupIds(): Set<String> {
        if (groupIdsInitialized) return groupIds
        return groupIdsMutex.withLock {
            if (!groupIdsInitialized) {
                groupIds = withContext(Dispatchers.IO) {
                    contactsDao.getResolvedGroupIds().map { normalizeId(it) }.toSet()
                }
                groupIdsInitialized = true
            }
            groupIds
        }
    }

    /** True when [normalizedGroupId] is a known group identifier. */
    suspend fun isGroupIdCached(normalizedGroupId: String): Boolean =
        normalizedGroupId in getGroupIds()

    /** Adds [rawGroupId] (will be normalized) to the group-id cache. */
    suspend fun addGroupId(rawGroupId: String) {
        val normalized = normalizeId(rawGroupId)
        groupIdsMutex.withLock {
            groupIds = groupIds + normalized
            groupIdsInitialized = true
        }
    }

    /**
     * Replaces the entire group-id cache with [rawIds], normalizing each entry.
     * Used by the [contactsDao] live-Flow observer so the cache always tracks
     * the database snapshot.
     */
    suspend fun primeGroupIds(rawIds: List<String>) {
        val normalized = rawIds.map { normalizeId(it) }.toSet()
        groupIdsMutex.withLock {
            groupIds = normalized
            groupIdsInitialized = true
        }
    }

    // ─── Contacts ────────────────────────────────────────────────────────

    /**
     * Returns the contactId for [normalizedId] without touching the DB if the
     * cache is already primed; returns null on cache miss (caller must fall
     * back to a DAO query).
     */
    suspend fun getContactId(normalizedId: String): Int? {
        val cache = getContacts()
        return cache[normalizedId]
    }

    /** Returns the full id -> contactId map, initializing from DAO on first call. */
    suspend fun getContacts(): Map<String, Int> {
        if (contactsInitialized) return contacts
        return contactsMutex.withLock {
            if (!contactsInitialized) {
                contacts = withContext(Dispatchers.IO) { buildContactsFromDao() }
                contactsInitialized = true
            }
            contacts
        }
    }

    /**
     * Indexes every identifier owned by [contact] (userId + every deviceId,
     * groupId, or device id) under [contactId]. Called immediately after a
     * successful insert so the hot `saveMessage` path can resolve the new
     * contact without a DB round-trip.
     */
    suspend fun addContact(contact: FullContactData, contactId: Int) {
        contactsMutex.withLock {
            val updates = mutableMapOf<String, Int>()
            when (contact) {
                is FullContactData.User -> {
                    updates[normalizeId(contact.userId)] = contactId
                    contact.devices.forEach { device ->
                        updates[normalizeId(device.id)] = contactId
                    }
                }
                is FullContactData.Group -> {
                    updates[normalizeId(contact.groupId)] = contactId
                }
                is FullContactData.Device -> {
                    updates[normalizeId(contact.deviceId)] = contactId
                    updates[normalizeId(contact.deviceData.id)] = contactId
                }
            }
            contacts = contacts + updates
            contactsInitialized = true
        }
    }

    /**
     * Eagerly preloads every (normalizedId -> contactId) mapping in a single
     * UNION ALL query. Replaces the previous lazy/empty stub which forced every
     * `ensureSenderExists` call to hit the DB on first run.
     */
    private suspend fun buildContactsFromDao(): Map<String, Int> {
        val rows = contactsDao.getAllIdToContactRows()
        if (rows.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>(rows.size)
        for (row in rows) {
            val key = normalizeIdOrNull(row.normalizedId) ?: continue
            out[key] = row.contactId
        }
        return out
    }

    // ─── Chat IDs ────────────────────────────────────────────────────────

    /** Returns the chatId previously cached under [key], or null on miss. */
    fun getChatId(key: String): String? = chatIds[key]

    /** Stores a [key] -> [chatId] mapping. No-op when [chatId] is blank. */
    suspend fun putChatId(key: String, chatId: String) {
        if (chatId.isBlank()) return
        chatIdsMutex.withLock {
            chatIds = chatIds + (key to chatId)
        }
    }

    /**
     * Drops every cache entry that resolves to [chatId]. Called when a chat
     * row is deleted so that subsequent lookups fall through to the DB
     * instead of returning a stale chatId.
     */
    suspend fun removeChatIdsMappedTo(chatId: String) {
        chatIdsMutex.withLock {
            chatIds = chatIds.filterValues { it != chatId }
        }
    }

    // ─── Reset ───────────────────────────────────────────────────────────

    /** Clears every cache and resets initialization flags. Used by `clearData`. */
    suspend fun resetAll() {
        groupIdsMutex.withLock {
            groupIds = emptySet()
            groupIdsInitialized = false
        }
        contactsMutex.withLock {
            contacts = emptyMap()
            contactsInitialized = false
        }
        chatIdsMutex.withLock {
            chatIds = emptyMap()
        }
    }
}

