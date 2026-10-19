package com.commcrete.stardust.room.new_db.internal

import android.util.Log
import com.commcrete.stardust.room.new_db.audit.IdentityKind
import com.commcrete.stardust.room.new_db.audit.IdentityLogSource
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactGroupIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactUserIdEntity
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.DeviceEntity
import com.commcrete.stardust.room.new_db.contact.FullContactData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Owns every contacts-domain operation backed by [ContactsDao]:
 *
 *  - **Inserts** — bulk and single-contact insert paths that also create the
 *    corresponding chat ([insertContactsWithChats], [insertContactWithChat]).
 *    These are the only writers in this class; both populate [caches] before
 *    any chat creation runs so the hot save-message path can resolve the new
 *    contact without a DB round-trip.
 *
 *  - **Group-id queries** — [observeGroupIds], [getAllGroupIds], [isGroupId],
 *    [hasAnyGroupId]. Read through [caches] when possible; the live observer
 *    keeps the cache in sync with the DAO source-of-truth.
 *
 *  - **Name + lookup helpers** — [getContactNameById], [getContactNameByIdOrId],
 *    [getGroupNameById], [getGroupContactById],
 *    [isContactExistsByMainCommunicationId], [findContactIdByMainCommunicationId],
 *    [findUnknownMainCommunicationIds]. The bulk-existence check funnels its
 *    DB call through [caches] first so a fully-warm cache costs zero queries.
 *
 *  - **Roster reads** — [getUserAndGroupContactsExceptSelf],
 *    [getUserAndDeviceContactsExceptSelf]. Map raw DAO row projections back
 *    into the typed [FullContactData] hierarchy via [mapAppContactRowsToFullContactData]
 *    and [mapGroupContactRowsToFullContactData] — both pure helpers that never
 *    issue secondary queries.
 *
 * # Cross-domain dependencies
 *  - [chatsDao] is needed by the insert paths to fetch the existing
 *    GROUP-chat list before adding a new private contact to each. Read-only.
 *  - [chats] is the chat-creation facade — every chat that gets built as a
 *    side-effect of [insertContactWithChat] / [insertContactsWithChats] is
 *    created through it (PRIVATE / GROUP / participant fan-out). This is the
 *    one direction in which the contacts domain reaches into chats; the
 *    reverse never happens.
 *  - [registeredAppIdProvider] returns the registered user's lower-cased
 *    appId (or null when no user is registered). Used by the
 *    *-ExceptSelf roster queries to push the self-filter into SQL.
 *  - [identityLog] receives an audit row for every identity mapping this class
 *    creates, moves, strips or deletes. Writes are best-effort and never fail
 *    the operation they describe; see [IdentityLogRecorder].
 */
internal class ContactsRepository(
    private val contactsDao: ContactsDao,
    private val chatsDao: ChatDao,
    private val caches: RepositoryCaches,
    private val chats: ChatsRepository,
    private val identityLog: IdentityLogRecorder,
    private val registeredAppIdProvider: () -> String?,
    /** Archives a chat's settled messages; returns how many. Used by [switchBetweenUserAndGroup]. */
    private val archiveChatMessages: suspend (chatId: String) -> Int,
) {

    private companion object {
        private const val TAG = "ContactsRepository"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inserts
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.insertContactsWithChats`. */
    suspend fun insertContactsWithChats(
        contacts: List<FullContactData>,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) =
        withContext(Dispatchers.IO) {
            // Ownership snapshot for the audit trail, taken before addContacts
            // rewrites any mapping: one query for the whole batch, plus the
            // pre-insert holdings of each contact this batch is going to touch.
            val priorOwners = contactsDao.getAllIdToContactRows()
                .mapNotNull { row -> normalizeIdOrNull(row.normalizedId)?.let { it to row.contactId } }
                .toMap()
            val priorHoldings = priorHoldingsFor(contacts, priorOwners)

            val inserted = contactsDao.addContacts(contacts)
            val existingGroupChatIds = chatsDao.getAllGroupChatIds()
            val allMemberIds = contactsDao.getAllMemberContactIds()

            inserted.forEach { (data, contactId) ->
                // addContact returns null when the entry had no resolvable
                // primary ID — no ContactEntity row exists for it, so
                // creating a chat/participant referencing it would trip
                // the chat_participants.contact_id foreign key.
                if (contactId == null) {
                    Log.w(TAG, "insertContactsWithChats: skipping chat creation — unresolvable primary ID for ${data.contact.name}")
                    return@forEach
                }

                // Add to contacts cache before chat creation so concurrent
                // saveMessage callers can resolve the new contact via cache.
                forgetDisplacedIds(data, contactId, priorOwners, priorHoldings)
                caches.addContact(data, contactId)

                when (data.contact.type) {
                    ContactType.USER, ContactType.DEVICE -> {
                        if (!keepExistingChat(data.contact, contactId)) chats.createPrivateChat(data.contact, contactId)
                        chats.addToExistingGroupChats(contactId, existingGroupChatIds)
                    }
                    ContactType.GROUP -> {
                        if (!keepExistingChat(data.contact, contactId)) {
                            chats.createGroupChat(data.contact, contactId, allMemberIds)
                        }
                    }
                }
                // addContacts rewrote an existing contact's row, icon included, but creating the
                // chat leaves an existing chat untouched — so its icon copy is synced here.
                syncChatImage(data.contact.type, contactId, data.contact.image)

                recordInsert(data, contactId, priorOwners, priorHoldings, source, batchId)
            }
        }

    /**
     * An insert that matched an existing contact by an id it brings — `addContact` merges into
     * that contact rather than adding one — keeps the contact's chat: its history is the
     * contact's, and a second private chat for one contact shows it twice in search. The chat
     * takes the inserted name, which `addContact` has already written to the contact row
     * (`chats.name` is UNIQUE and kept equal to it). False when the contact has no chat yet.
     */
    private suspend fun keepExistingChat(contact: ContactEntity, contactId: Int): Boolean {
        val chatId = chatIdForContact(contact.type, contactId) ?: return false
        chatsDao.renameChatById(chatId, contact.name)
        return true
    }

    /** See `AppRepository.insertContactWithChat`. */
    suspend fun insertContactWithChat(
        contact: FullContactData,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) =
        withContext(Dispatchers.IO) {
            // Targeted snapshot rather than the full table: this path also serves
            // the hot auto-create in the message-save pipeline, where the id is
            // known to be unowned and this costs a single indexed lookup.
            val priorOwners = priorOwnersOf(contact)
            val priorHoldings = priorHoldingsFor(listOf(contact), priorOwners)

            val contactId = contactsDao.addContact(contact)
            if (contactId == null) {
                Log.w(TAG, "insertContactWithChat: skipping chat creation — unresolvable primary ID for ${contact.contact.name}")
                return@withContext
            }
            forgetDisplacedIds(contact, contactId, priorOwners, priorHoldings)
            caches.addContact(contact, contactId)

            when (contact.contact.type) {
                ContactType.USER, ContactType.DEVICE -> {
                    if (!keepExistingChat(contact.contact, contactId)) chats.createPrivateChat(contact.contact, contactId)
                    chats.addToExistingGroupChats(contactId, chatsDao.getAllGroupChatIds())
                }
                ContactType.GROUP -> {
                    if (!keepExistingChat(contact.contact, contactId)) {
                        chats.createGroupChat(
                            contact.contact,
                            contactId,
                            contactsDao.getAllMemberContactIds(),
                        )
                    }
                }
            }

            recordInsert(contact, contactId, priorOwners, priorHoldings, source, batchId)
        }

    // ─────────────────────────────────────────────────────────────────────
    // Identity-audit helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Every identifier [data] claims: its primary id plus any device links. */
    private fun claimedIds(data: FullContactData): List<String> = buildList {
        normalizeIdOrNull(data.getMainCommunicationId())?.let { add(it) }
        when (data) {
            is FullContactData.User -> data.devices.forEach { device ->
                normalizeIdOrNull(device.id)?.let { add(it) }
            }
            is FullContactData.Device -> normalizeIdOrNull(data.deviceData.id)?.let { add(it) }
            is FullContactData.Group -> Unit
        }
    }.distinct()

    /** Pre-insert owner of each id [contact] claims, resolved one id at a time. */
    private suspend fun priorOwnersOf(contact: FullContactData): Map<String, Int> =
        claimedIds(contact).mapNotNull { id ->
            contactsDao.findContactIdByMainCommunicationId(id)?.let { id to it }
        }.toMap()

    /**
     * What each contact about to be written already holds, keyed by contact id.
     *
     * The target is predicted the same way `ContactsDao.addContact` resolves it —
     * primary id first, then any device link — so the recorder can tell which
     * mappings the upsert is about to evict via `UNIQUE(contact_id)` /
     * `UNIQUE(contact_id, slot)`. A wrong prediction only costs a missing audit
     * detail: the map is keyed by contact id, so a mismatch is simply not found.
     */
    private suspend fun priorHoldingsFor(
        contacts: List<FullContactData>,
        priorOwners: Map<String, Int>,
    ): Map<Int, List<IdentityLogRecorder.PriorOwnedId>> {
        val targets = contacts.mapNotNull { data ->
            claimedIds(data).firstNotNullOfOrNull { priorOwners[it] }
        }.distinct()
        if (targets.isEmpty()) return emptyMap()

        return contactsDao.getOwnedIdRows(targets)
            .mapNotNull { row ->
                val idValue = normalizeIdOrNull(row.idValue) ?: return@mapNotNull null
                val idKind = IdentityKind.entries.firstOrNull { it.name == row.kind }
                    ?: return@mapNotNull null
                row.contactId to IdentityLogRecorder.PriorOwnedId(
                    idKind = idKind,
                    idValue = idValue,
                    slot = row.slot,
                )
            }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * An insert can take ids from another contact (a merge by device) and evict ids from the
     * contact it lands on (`UNIQUE(contact_id)` replaces its old App ID). Both still route to
     * their old owner in the caches until forgotten. The auto-create on the message path
     * claims only unowned ids, so it forgets nothing and stays a cache write.
     */
    private suspend fun forgetDisplacedIds(
        data: FullContactData,
        contactId: Int,
        priorOwners: Map<String, Int>,
        priorHoldings: Map<Int, List<IdentityLogRecorder.PriorOwnedId>>,
    ) {
        val taken = claimedIds(data).filter { id -> priorOwners[id]?.let { it != contactId } == true }
        val evicted = priorHoldings[contactId].orEmpty().map { it.idValue }
        caches.forgetIds(taken + evicted)
    }

    private suspend fun recordInsert(
        data: FullContactData,
        contactId: Int,
        priorOwners: Map<String, Int>,
        priorHoldings: Map<Int, List<IdentityLogRecorder.PriorOwnedId>>,
        source: String,
        batchId: Long?,
    ) = identityLog.recordInsert(
        data = data,
        contactId = contactId,
        priorOwnerOf = { id -> priorOwners[id] },
        priorOwnerName = { id -> contactsDao.getContactById(id)?.name },
        priorOwnedIds = priorHoldings[contactId].orEmpty(),
        source = source,
        batchId = batchId,
    )

    // ─────────────────────────────────────────────────────────────────────
    // Group-id queries
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.observeGroupIds`. */
    fun observeGroupIds(): Flow<List<String>> =
        contactsDao.observeResolvedGroupIds().onEach { ids ->
            caches.primeGroupIds(ids)
        }

    /** See `AppRepository.getAllGroupIds`. */
    suspend fun getAllGroupIds(): List<String> = withContext(Dispatchers.IO) {
        val ids = contactsDao.getResolvedGroupIds()
            .map { normalizeId(it) }
            .distinct()
        caches.primeGroupIds(ids)
        ids
    }

    /** See `AppRepository.isGroupId`. */
    suspend fun isGroupId(id: String?): Boolean = hasAnyGroupId(listOf(id))

    /** See `AppRepository.hasAnyGroupId`. */
    suspend fun hasAnyGroupId(ids: Collection<String?>): Boolean = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext false
        val normalizedIds = ids.mapNotNull { normalizeIdOrNull(it) }
        if (normalizedIds.isEmpty()) return@withContext false
        val cache = caches.getGroupIds()
        normalizedIds.any { it in cache }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Name + lookup helpers
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.getContactNameById`. */
    suspend fun getContactNameById(id: String): String? = withContext(Dispatchers.IO) {
        contactsDao.findContactNameById(normalizeId(id))
    }

    /** See `AppRepository.getContactNameByIdOrId`. */
    suspend fun getContactNameByIdOrId(id: String): String = withContext(Dispatchers.IO) {
        val normalized = normalizeId(id)
        contactsDao.findContactNameById(normalized) ?: normalized
    }

    /** See `AppRepository.getGroupNameById`. */
    suspend fun getGroupNameById(groupId: String): String? = withContext(Dispatchers.IO) {
        contactsDao.findGroupNameById(normalizeId(groupId))
    }

    /** See `AppRepository.getGroupContactById`. */
    suspend fun getGroupContactById(groupId: String): ContactEntity? = withContext(Dispatchers.IO) {
        val contactId = contactsDao.findContactIdByGroupId(normalizeId(groupId))
            ?: return@withContext null
        val contact = contactsDao.getContactById(contactId) ?: return@withContext null
        if (contact.type == ContactType.GROUP) contact else null
    }

    /** See `AppRepository.isContactExistsByMainCommunicationId`. */
    suspend fun isContactExistsByMainCommunicationId(mainCommunicationId: String?): Boolean {
        val normalized = normalizeIdOrNull(mainCommunicationId) ?: return false
        return findContactIdByMainCommunicationId(normalized) != null
    }

    /** See `AppRepository.findContactIdByMainCommunicationId`. */
    suspend fun findContactIdByMainCommunicationId(mainCommunicationId: String?): Int? {
        val normalized = normalizeIdOrNull(mainCommunicationId) ?: return null
        caches.getContactId(normalized)?.let { return it }
        return withContext(Dispatchers.IO) {
            contactsDao.findContactIdByMainCommunicationId(normalized)
        }
    }

    /** See `AppRepository.findUnknownMainCommunicationIds`. */
    suspend fun findUnknownMainCommunicationIds(mainCommunicationIds: List<String>): List<String> {
        if (mainCommunicationIds.isEmpty()) return emptyList()

        // Preserve order of first appearance after normalization.
        val ordered = LinkedHashSet<String>(mainCommunicationIds.size)
        for (raw in mainCommunicationIds) {
            normalizeIdOrNull(raw)?.let { ordered += it }
        }
        if (ordered.isEmpty()) return emptyList()

        val cache = caches.getContacts()
        val needsDbCheck = ordered.filterNot { it in cache }
        if (needsDbCheck.isEmpty()) return emptyList()

        val foundInDb: Set<String> = withContext(Dispatchers.IO) {
            contactsDao.findExistingMainCommunicationIds(needsDbCheck)
                .mapNotNull { normalizeIdOrNull(it) }
                .toSet()
        }

        return needsDbCheck.filterNot { it in foundInDb }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Roster reads (USER + GROUP / USER + DEVICE), excluding self
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.getUserAndGroupContactsExceptSelf`. */
    suspend fun getUserAndGroupContactsExceptSelf(): List<FullContactData> = withContext(Dispatchers.IO) {
        val selfId = registeredAppIdProvider()

        val groupRows = contactsDao.getAllGroupContactRows()
        val appRows = if (selfId == null) {
            contactsDao.getAllAppContactRows()
        } else {
            contactsDao.getAllAppContactRowsExceptUser(selfId)
        }

        mapGroupContactRowsToFullContactData(groupRows) + mapAppContactRowsToFullContactData(appRows)
    }

    /** See `AppRepository.observeUserAndGroupContactsExceptSelf`. */
    fun observeUserAndGroupContactsExceptSelf(): Flow<List<FullContactData>> =
        flow {
            // Resolve self per (re)subscription so a login/logout between subscriptions is reflected,
            // matching the per-call semantics of getUserAndGroupContactsExceptSelf.
            val selfId = registeredAppIdProvider()
            val appRowsFlow = if (selfId == null) {
                contactsDao.observeAllAppContactRows()
            } else {
                contactsDao.observeAllAppContactRowsExceptUser(selfId)
            }
            emitAll(
                combine(contactsDao.observeAllGroupContactRows(), appRowsFlow) { groupRows, appRows ->
                    mapGroupContactRowsToFullContactData(groupRows) + mapAppContactRowsToFullContactData(appRows)
                }
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────
    // Conflict-resolution mutations (rename / move-field / delete)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Applies an in-place change to an existing contact resolved from
     * [original]'s identity: renames it (and its chat), changes its icon, sets a
     * new App ID or device, and/or strips an identity that has moved to another
     * contact. Leaves the contact otherwise intact.
     *
     * Each strip is recorded in `contact_identity_log`: the mapping is deleted
     * outright here, so this is the only moment the losing side of a swap is
     * still known.
     */
    suspend fun updateExistingContact(
        original: ContactDraft,
        updated: ContactDraft,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) = withContext(Dispatchers.IO) {
        val contactId = resolveContactId(original) ?: return@withContext
        val newKind = updated.effectiveType()
        val oldKind = original.effectiveType()
        if (oldKind != null && newKind != null && (oldKind == ContactType.GROUP) != (newKind == ContactType.GROUP)) {
            switchBetweenUserAndGroup(contactId, original, updated, newKind, source, batchId)
            return@withContext
        }
        // The kind follows the ids: a USER whose App ID is removed is a DEVICE now, and a DEVICE
        // given one is a USER. Same contact, same chat — only the column changes.
        if (newKind != null && newKind != original.effectiveType()) {
            contactsDao.setContactType(contactId, newKind)
        }
        // A draft holds "no icon" as "" (ContactDraft.fromFullContactData); the column holds null.
        if (updated.iconKey != original.iconKey) {
            val image = updated.iconKey.ifBlank { null }
            contactsDao.setContactImage(contactId, image)
            syncChatImage(original.type, contactId, image)
        }
        val newName = updated.name.trim()
        if (newName.isNotBlank() && newName != original.name) {
            renameContactAndItsChat(contactId, original.type, original.name, newName, source, batchId)
        }
        val newAppId = normalizeIdOrNull(updated.appId)
        if (newAppId != null && !newAppId.equals(original.appId, ignoreCase = true)) {
            // A new App ID on THIS contact, set in place. An Insert would merge into the contact
            // too, but it also opens a second private chat for it. The old id is dropped first and
            // recorded, so a change reads back as strip + assign.
            val isGroup = original.type == ContactType.GROUP
            val idKind = if (isGroup) IdentityKind.GROUP_ID else IdentityKind.USER_ID
            if (original.hasAppId) {
                if (isGroup) contactsDao.removeGroupId(original.appId)
                else contactsDao.removeUserId(original.appId)
                identityLog.recordStrip(
                    idKind = idKind,
                    idValue = original.appId,
                    fromContactId = contactId,
                    fromName = original.name,
                    fromChatId = chatIdForContact(original.type, contactId),
                    source = source,
                    batchId = batchId,
                )
            }
            if (isGroup) contactsDao.upsertGroupId(ContactGroupIdEntity(groupId = newAppId, contactId = contactId))
            else contactsDao.upsertUserAppId(ContactUserIdEntity(userId = newAppId, contactId = contactId))
            identityLog.recordAssign(
                idKind = idKind,
                idValue = newAppId,
                toContactId = contactId,
                toName = newName.ifBlank { original.name },
                source = source,
                batchId = batchId,
            )
        }
        if (original.hasAppId && !updated.hasAppId) {
            val isGroup = original.type == ContactType.GROUP
            if (isGroup) contactsDao.removeGroupId(original.appId)
            else contactsDao.removeUserId(original.appId)
            identityLog.recordStrip(
                idKind = if (isGroup) IdentityKind.GROUP_ID else IdentityKind.USER_ID,
                idValue = original.appId,
                fromContactId = contactId,
                fromName = original.name,
                fromChatId = chatIdForContact(original.type, contactId),
                source = source,
                batchId = batchId,
            )
        }
        if (original.hasDeviceId && !updated.hasDeviceId) {
            contactsDao.removeDeviceLink(original.deviceId)
            identityLog.recordStrip(
                idKind = IdentityKind.DEVICE_ID,
                idValue = original.deviceId,
                fromContactId = contactId,
                fromName = original.name,
                fromChatId = chatIdForContact(original.type, contactId),
                source = source,
                batchId = batchId,
            )
        }
        val sameDevice = original.hasDeviceId && updated.deviceId.equals(original.deviceId, ignoreCase = true)
        when {
            // Same device, new details — a radio re-issued with a corrected model
            // or serial. Only the details move; the id and its contact link stay
            // put, so this is an identity-neutral change and has no audit row.
            sameDevice -> if (updated.model != original.model || updated.serial != original.serial) {
                contactsDao.updateDeviceDetails(
                    deviceId = original.deviceId,
                    model = updated.model.ifBlank { null },
                    serial = updated.serial.ifBlank { null },
                )
            }

            // A device gained, or swapped for another: link it to THIS contact in
            // place. An Insert would merge into the contact too, but it also opens
            // a second private chat for it. The old link is dropped first and
            // recorded, so a swap reads back as strip + assign.
            updated.hasDeviceId -> {
                if (original.hasDeviceId) {
                    contactsDao.removeDeviceLink(original.deviceId)
                    identityLog.recordStrip(
                        idKind = IdentityKind.DEVICE_ID,
                        idValue = original.deviceId,
                        fromContactId = contactId,
                        fromName = original.name,
                        fromChatId = chatIdForContact(original.type, contactId),
                        source = source,
                        batchId = batchId,
                    )
                }
                normalizeIdOrNull(updated.deviceId)?.let { deviceId ->
                    contactsDao.linkDevice(
                        DeviceEntity(
                            id = deviceId,
                            model = updated.model.ifBlank { null },
                            serial = updated.serial.ifBlank { null },
                        ),
                        contactId,
                    )
                    identityLog.recordAssign(
                        idKind = IdentityKind.DEVICE_ID,
                        idValue = deviceId,
                        toContactId = contactId,
                        toName = updated.name.ifBlank { original.name },
                        source = source,
                        batchId = batchId,
                    )
                }
            }
        }
        // After the writes, so a lookup racing them cannot re-cache the old owner.
        caches.forgetIds(listOf(original.appId, original.deviceId, updated.appId, updated.deviceId))
    }

    /**
     * A user contact becoming a group, or a group becoming a user, on the same App ID — the
     * import's type-change case, which the operator confirms first.
     *
     * In place: same contact row, same chat row. A delete and a fresh insert would lose the
     * history outright — deleting a chat cascades to its messages, archived ones included.
     * Instead every settled message in the chat is archived: a group's traffic must not reappear
     * in a private chat, nor a private chat's in a group. In-flight rows are left to settle
     * (see `MessageDao.archiveChatMessages`).
     *
     * Archiving comes first. These writes are not one transaction, like every other write in
     * this layer, so a failure part-way leaves messages hidden rather than shown to the wrong
     * audience.
     */
    private suspend fun switchBetweenUserAndGroup(
        contactId: Int,
        original: ContactDraft,
        updated: ContactDraft,
        newKind: ContactType,
        source: String,
        batchId: Long?,
    ) {
        val toGroup = newKind == ContactType.GROUP
        val chatId = chatIdForContact(original.type, contactId)
        chatId?.let { archiveChatMessages(it) }

        // Every id it held goes: an App ID lives in the other kind's table now, and a group
        // holds no device.
        val owned = contactsDao.getOwnedIdRows(listOf(contactId))
        owned.forEach { row ->
            when (row.kind) {
                IdentityKind.USER_ID.name -> contactsDao.removeUserId(row.idValue)
                IdentityKind.GROUP_ID.name -> contactsDao.removeGroupId(row.idValue)
                IdentityKind.DEVICE_ID.name -> contactsDao.removeDeviceLink(row.idValue)
                else -> return@forEach
            }
            identityLog.recordStrip(
                idKind = IdentityKind.entries.first { it.name == row.kind },
                idValue = row.idValue,
                fromContactId = contactId,
                fromName = original.name,
                fromChatId = chatId,
                source = source,
                batchId = batchId,
            )
        }

        val name = updated.name.trim().ifBlank { original.name }
        // Non-null: newKind came from effectiveType, which needs the App ID.
        val appId = normalizeIdOrNull(updated.appId)
        if (appId != null) {
            if (toGroup) contactsDao.upsertGroupId(ContactGroupIdEntity(groupId = appId, contactId = contactId))
            else contactsDao.upsertUserAppId(ContactUserIdEntity(userId = appId, contactId = contactId))
            identityLog.recordAssign(
                idKind = if (toGroup) IdentityKind.GROUP_ID else IdentityKind.USER_ID,
                idValue = appId,
                toContactId = contactId,
                toName = name,
                source = source,
                batchId = batchId,
            )
        }
        val deviceId = normalizeIdOrNull(updated.deviceId).takeIf { !toGroup }
        if (deviceId != null) {
            contactsDao.linkDevice(
                DeviceEntity(id = deviceId, model = updated.model.ifBlank { null }, serial = updated.serial.ifBlank { null }),
                contactId,
            )
            identityLog.recordAssign(
                idKind = IdentityKind.DEVICE_ID,
                idValue = deviceId,
                toContactId = contactId,
                toName = name,
                source = source,
                batchId = batchId,
            )
        }
        contactsDao.setContactType(contactId, newKind)

        // The chat changes kind with it, and its participants with that: a group chat has every
        // member, a private chat its one contact — as a newly created chat of that kind would.
        // After setContactType, so the member list no longer counts this contact as a member.
        val members = contactsDao.getAllMemberContactIds()
        if (chatId != null) {
            chatsDao.setChatType(chatId, if (toGroup) ChatType.GROUP else ChatType.PRIVATE)
            chatsDao.replaceParticipants(chatId, if (toGroup) members + contactId else listOf(contactId))
        }
        if (toGroup) {
            chatsDao.removeParticipantFromOtherChats(contactId, keepChatId = chatId.orEmpty())
        } else {
            chats.addToExistingGroupChats(contactId, chatsDao.getAllGroupChatIds() - chatId.orEmpty())
        }

        if (name != original.name) renameContactAndItsChat(contactId, newKind, original.name, name, source, batchId)
        val image = updated.iconKey.ifBlank { null }
        if (updated.iconKey != original.iconKey) {
            contactsDao.setContactImage(contactId, image)
            syncChatImage(newKind, contactId, image)
        }
        if (chatId == null) {
            // No chat to convert: create the one this kind would have had.
            contactsDao.getContactById(contactId)?.let { contact ->
                if (toGroup) chats.createGroupChat(contact, contactId, members)
                else chats.createPrivateChat(contact, contactId)
            }
        }
        caches.forgetIds(owned.map { it.idValue } + listOfNotNull(appId, deviceId))
    }

    /**
     * Renames an existing contact **and** its chat in place. Used for the
     * "same ids, callsign changed" case so a matching import updates the name
     * without creating a duplicate contact / chat.
     */
    suspend fun renameContactAndChat(
        target: ContactDraft,
        newName: String,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) = withContext(Dispatchers.IO) {
        val contactId = resolveContactId(target) ?: return@withContext
        val trimmed = newName.trim()
        // Exact comparison: a case-only change ("alpha" → "Alpha") is a real rename.
        if (trimmed.isBlank() || trimmed == target.name) return@withContext
        renameContactAndItsChat(contactId, target.type, target.name, trimmed, source, batchId)
    }

    /**
     * Every callsign change goes through here, so the contact and its chat never disagree. The
     * chat list reads the contact's name ([ChatDao] `CHAT_NAME_SQL`); `chats.name` is still kept
     * equal because it is UNIQUE, and a stale copy blocks creating a chat under that name.
     */
    private suspend fun renameContactAndItsChat(
        contactId: Int,
        type: ContactType,
        fromName: String,
        toName: String,
        source: String,
        batchId: Long?,
    ) {
        contactsDao.renameContact(contactId, toName)
        val chatId = chatIdForContact(type, contactId)
        chatId?.let { chatsDao.renameChatById(it, toName) }
        identityLog.recordRename(
            contactId = contactId,
            fromName = fromName,
            toName = toName,
            chatId = chatId,
            source = source,
            batchId = batchId,
        )
    }

    /**
     * Fully removes the contact resolved from [target]'s identity — its chat
     * first (so nothing is orphaned), then the contact row (FK cascades drop
     * its id/device mappings and remaining chat participants).
     *
     * The audit row is built before the delete and written after it, so the
     * trail names the contact and chat that ceased to exist.
     */
    suspend fun deleteContact(
        target: ContactDraft,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
        /**
         * [target]'s contact as resolved BEFORE the batch it belongs to ran. See
         * `AppRepository.applyContactOperations` — resolving it here, afterwards,
         * finds whoever holds the target's id now.
         */
        resolvedContactId: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val contactId = resolvedContactId ?: resolveContactId(target) ?: return@withContext
        val chatId = chatIdForContact(target.type, contactId)
        val name = contactsDao.getContactById(contactId)?.name ?: target.name
        val effectiveType = target.effectiveType() ?: target.type
        val primaryId = target.appId.ifBlank { target.deviceId }
        // Read before the delete: the cascade removes the rows, and the draft may not list every id.
        val ownedIds = contactsDao.getOwnedIdRows(listOf(contactId)).map { it.idValue }

        chatId?.let { chats.deleteChat(it) }
        contactsDao.deleteContactById(contactId)
        caches.forgetIds(ownedIds + listOf(target.appId, target.deviceId))

        identityLog.recordDelete(
            contactId = contactId,
            name = name,
            chatId = chatId,
            idKind = identityLog.primaryIdKind(effectiveType),
            idValue = primaryId,
            source = source,
            batchId = batchId,
        )
    }

    /** Chat id for [contactId] (private or group depending on [type]). */
    suspend fun chatIdForContactDraft(draft: ContactDraft): String? = withContext(Dispatchers.IO) {
        val contactId = resolveContactId(draft) ?: return@withContext null
        chatIdForContact(draft.type, contactId)
    }

    /**
     * Contact id behind [draft]'s identity, or null when it resolves to nothing.
     * Read before a destructive op so the audit row can name the contact that is
     * about to stop existing.
     */
    suspend fun contactIdForDraft(draft: ContactDraft): Int? = withContext(Dispatchers.IO) {
        resolveContactId(draft)
    }

    /** The chat of an already-resolved contact. */
    suspend fun chatIdForContactId(type: ContactType, contactId: Int): String? = withContext(Dispatchers.IO) {
        chatIdForContact(type, contactId)
    }

    /**
     * A chat keeps its own copy of its contact's icon (`chats.image`, copied at creation), and the
     * chat list draws that copy. Every write of `contacts.image` calls this, or the list keeps
     * showing the old icon. No chat yet is fine: it copies the icon when it is created.
     */
    private suspend fun syncChatImage(type: ContactType, contactId: Int, image: String?) {
        chatIdForContact(type, contactId)?.let { chatsDao.setChatImage(it, image) }
    }

    private suspend fun chatIdForContact(type: ContactType, contactId: Int): String? = when (type) {
        ContactType.GROUP -> chatsDao.findGroupChatIdByContactId(contactId)
        ContactType.USER, ContactType.DEVICE -> chatsDao.findPrivateChatIdByContactId(contactId)
    }

    private suspend fun resolveContactId(draft: ContactDraft): Int? = when {
        draft.type == ContactType.GROUP && draft.hasAppId -> contactsDao.findContactIdByGroupId(draft.appId)
        draft.hasAppId -> contactsDao.findContactIdByUserId(draft.appId)
        draft.hasDeviceId -> contactsDao.findContactIdByDeviceId(draft.deviceId)
        else -> null
    }

    /** See `AppRepository.getAllContacts`. */
    suspend fun getAllContacts(): List<FullContactData> = withContext(Dispatchers.IO) {
        val appRows = contactsDao.getAllUserAndDeviceContactRows()
        val groupRows = contactsDao.getAllGroupContactRows()
        mapAppContactRowsToFullContactData(appRows) + mapGroupContactRowsToFullContactData(groupRows)
    }

    /** See `AppRepository.observeAllContacts`. */
    fun observeAllContacts(): Flow<List<FullContactData>> =
        combine(
            contactsDao.observeAllUserAndDeviceContactRows(),
            contactsDao.observeAllGroupContactRows(),
        ) { appRows, groupRows ->
            mapAppContactRowsToFullContactData(appRows) + mapGroupContactRowsToFullContactData(groupRows)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    /** See `AppRepository.getUserAndDeviceContactsExceptSelf`. */
    suspend fun getUserAndDeviceContactsExceptSelf(): List<FullContactData> = withContext(Dispatchers.IO) {
        val selfId = registeredAppIdProvider()

        val rows = if (selfId == null) {
            contactsDao.getAllUserAndDeviceContactRows()
        } else {
            contactsDao.getAllUserAndDeviceContactRowsExceptUser(selfId)
        }

        mapAppContactRowsToFullContactData(rows)
            .filter { it is FullContactData.User || it is FullContactData.Device }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Row mappers — pure, no DB access
    // ─────────────────────────────────────────────────────────────────────

    private fun mapAppContactRowsToFullContactData(
        rows: List<ContactsDao.AppContactRow>,
    ): List<FullContactData> {
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { it.contact.id }.values.mapNotNull { groupedRows ->
            val contact = groupedRows.first().contact
            when (contact.type) {
                ContactType.USER -> {
                    val userId = groupedRows.firstNotNullOfOrNull { normalizeIdOrNull(it.userId) }
                        ?: return@mapNotNull null
                    val devices = groupedRows
                        .mapNotNull { row ->
                            val deviceId = normalizeIdOrNull(row.deviceId) ?: return@mapNotNull null
                            DeviceEntity(id = deviceId, model = row.deviceModel, serial = row.deviceSerial) to
                                    (row.deviceSlot ?: Int.MAX_VALUE)
                        }
                        .sortedBy { it.second }
                        .map { it.first }
                        .distinctBy { it.id }
                    FullContactData.User(contact = contact, userId = userId, devices = devices)
                }
                ContactType.DEVICE -> {
                    val best = groupedRows
                        .asSequence()
                        .mapNotNull { row ->
                            val deviceId = normalizeIdOrNull(row.deviceId) ?: return@mapNotNull null
                            Triple(deviceId, row.deviceModel, row.deviceSerial) to (row.deviceSlot ?: Int.MAX_VALUE)
                        }
                        .minByOrNull { it.second }?.first ?: return@mapNotNull null
                    FullContactData.Device(
                        contact = contact,
                        deviceId = best.first,
                        deviceData = DeviceEntity(id = best.first, model = best.second, serial = best.third),
                    )
                }
                ContactType.GROUP -> null
            }
        }
    }

    private fun mapGroupContactRowsToFullContactData(
        rows: List<ContactsDao.GroupContactRow>,
    ): List<FullContactData> =
        rows.mapNotNull { row ->
            val groupId = normalizeIdOrNull(row.groupId) ?: return@mapNotNull null
            FullContactData.Group(contact = row.contact, groupId = groupId)
        }
}

