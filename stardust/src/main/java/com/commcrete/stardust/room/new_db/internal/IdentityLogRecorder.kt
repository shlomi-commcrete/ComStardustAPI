package com.commcrete.stardust.room.new_db.internal

import android.util.Log
import com.commcrete.stardust.room.new_db.audit.ContactIdentityLogDao
import com.commcrete.stardust.room.new_db.audit.ContactIdentityLogEntity
import com.commcrete.stardust.room.new_db.audit.IdentityChangeKind
import com.commcrete.stardust.room.new_db.audit.IdentityKind
import com.commcrete.stardust.room.new_db.audit.IdentityLogSource
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.FullContactData
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes the [ContactIdentityLogEntity] audit trail. The only writer — every
 * identity change the library makes goes through one of the `record*` methods.
 *
 * Two invariants:
 *
 *  1. **Logging never breaks the operation it describes.** Every write is wrapped
 *     in [safely]; a failed audit row is logged to logcat and swallowed. An audit
 *     trail that can take down contact editing is worse than a missing row.
 *  2. **Ids are normalized on the way in** ([normalizeIdOrNull]), so `id_value`
 *     joins directly against the identity tables and `messages.sender_id`.
 *
 * [nextBatchId] hands out a process-unique, time-ordered batch id so all rows
 * from one `AppRepository.applyContactOperations` call can be read back together.
 */
internal class IdentityLogRecorder(
    private val dao: ContactIdentityLogDao,
) {

    private companion object {
        private const val TAG = "IdentityLogRecorder"
    }

    private val batchCounter = AtomicLong(System.currentTimeMillis())

    /** Monotonic, process-unique id shared by every row of one operation batch. */
    fun nextBatchId(): Long = batchCounter.incrementAndGet()

    /** The identity table a contact's primary id lives in. */
    fun primaryIdKind(type: ContactType): IdentityKind = when (type) {
        ContactType.USER -> IdentityKind.USER_ID
        ContactType.GROUP -> IdentityKind.GROUP_ID
        ContactType.DEVICE -> IdentityKind.DEVICE_ID
    }

    /**
     * One identity mapping a contact held **before** an insert, as read from the
     * identity tables. [slot] is set for device links only
     * (`app_contact_devices.slot`).
     */
    data class PriorOwnedId(
        val idKind: IdentityKind,
        val idValue: String,
        val slot: Int? = null,
    )

    /**
     * Diffs what [data] now owns against the pre-insert state and writes one row
     * per mapping that actually changed.
     *
     * Gained ids, from [priorOwnerOf] (a snapshot taken **before** the insert):
     *  - no prior owner            → [IdentityChangeKind.ASSIGNED]
     *  - prior owner ≠ [contactId] → [IdentityChangeKind.MOVED]
     *  - prior owner = [contactId] → nothing; the mapping was re-saved unchanged
     *
     * Lost ids, from [priorOwnedIds] (what [contactId] held before, empty for a
     * freshly created contact). Every identity upsert uses
     * `OnConflictStrategy.REPLACE` against a UNIQUE index, so a write can delete
     * a mapping nobody asked to delete:
     *  - `UNIQUE(contact_id)` on `app_contact_user_ids` / `app_contact_group_ids`
     *    → a new primary id evicts the contact's previous one.
     *  - `UNIQUE(contact_id, slot)` on `app_contact_devices` → a new device link
     *    evicts whatever held that slot (slot is always 0 today, so a contact
     *    keeps only its last-written device).
     *
     * Both are recorded as [IdentityChangeKind.STRIPPED] instead of vanishing.
     * [priorOwnerName] resolves a display name for a losing contact — best
     * effort, it may already be gone.
     */
    suspend fun recordInsert(
        data: FullContactData,
        contactId: Int,
        priorOwnerOf: suspend (String) -> Int?,
        priorOwnerName: suspend (Int) -> String?,
        priorOwnedIds: List<PriorOwnedId> = emptyList(),
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) = safely("recordInsert") {
        val rows = mutableListOf<ContactIdentityLogEntity>()
        val name = data.contact.name
        val primaryKind = primaryIdKind(data.contact.type)
        val primaryId = normalizeIdOrNull(data.getMainCommunicationId())

        val deviceIds: List<String> = when (data) {
            is FullContactData.User -> data.devices.mapNotNull { normalizeIdOrNull(it.id) }
            is FullContactData.Device -> listOfNotNull(normalizeIdOrNull(data.deviceData.id))
            is FullContactData.Group -> emptyList()
        }.distinct()

        val gained = buildList {
            primaryId?.let { add(primaryKind to it) }
            deviceIds.forEach { add(IdentityKind.DEVICE_ID to it) }
        }.distinctBy { it.second }

        for ((idKind, idValue) in gained) {
            val prior = priorOwnerOf(idValue)
            when {
                prior == null -> rows += ContactIdentityLogEntity(
                    kind = IdentityChangeKind.ASSIGNED,
                    idKind = idKind,
                    idValue = idValue,
                    toContactId = contactId,
                    toName = name,
                    source = source,
                    batchId = batchId,
                )
                prior != contactId -> rows += ContactIdentityLogEntity(
                    kind = IdentityChangeKind.MOVED,
                    idKind = idKind,
                    idValue = idValue,
                    fromContactId = prior,
                    fromName = priorOwnerName(prior),
                    toContactId = contactId,
                    toName = name,
                    source = source,
                    batchId = batchId,
                )
            }
        }

        val gainedValues = gained.map { it.second }.toSet()
        val evicted = priorOwnedIds.filter { prior ->
            if (prior.idValue in gainedValues) return@filter false
            when (prior.idKind) {
                // Evicted by UNIQUE(contact_id) when a new primary id of the same
                // kind is written.
                IdentityKind.USER_ID, IdentityKind.GROUP_ID ->
                    primaryId != null && prior.idKind == primaryKind
                // Evicted by UNIQUE(contact_id, slot) when a device is written to
                // the slot it held. Room defaults new links to slot 0.
                IdentityKind.DEVICE_ID ->
                    deviceIds.isNotEmpty() && (prior.slot ?: 0) == 0
            }
        }

        for (prior in evicted) {
            rows += ContactIdentityLogEntity(
                kind = IdentityChangeKind.STRIPPED,
                idKind = prior.idKind,
                idValue = prior.idValue,
                fromContactId = contactId,
                fromName = name,
                source = source,
                batchId = batchId,
            )
        }

        if (rows.isNotEmpty()) dao.log(rows)
    }

    /** An identity mapping explicitly removed from [fromContactId]. */
    suspend fun recordStrip(
        idKind: IdentityKind,
        idValue: String?,
        fromContactId: Int?,
        fromName: String?,
        fromChatId: String? = null,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) = safely("recordStrip") {
        val normalized = normalizeIdOrNull(idValue) ?: return@safely
        dao.log(
            ContactIdentityLogEntity(
                kind = IdentityChangeKind.STRIPPED,
                idKind = idKind,
                idValue = normalized,
                fromContactId = fromContactId,
                fromName = fromName,
                fromChatId = fromChatId,
                source = source,
                batchId = batchId,
            )
        )
    }

    /** A contact (and, when [chatId] is set, its chat) renamed in place. */
    suspend fun recordRename(
        contactId: Int?,
        fromName: String?,
        toName: String,
        chatId: String? = null,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) = safely("recordRename") {
        dao.log(
            ContactIdentityLogEntity(
                kind = IdentityChangeKind.RENAMED,
                fromContactId = contactId,
                toContactId = contactId,
                fromName = fromName,
                toName = toName,
                fromChatId = chatId,
                toChatId = chatId,
                source = source,
                batchId = batchId,
            )
        )
    }

    /**
     * A contact row deleted. [idKind] / [idValue] carry its primary id where the
     * caller knows it, so the trail for that id ends with an explicit event
     * instead of just stopping.
     */
    suspend fun recordDelete(
        contactId: Int?,
        name: String?,
        chatId: String?,
        idKind: IdentityKind? = null,
        idValue: String? = null,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) = safely("recordDelete") {
        dao.log(
            ContactIdentityLogEntity(
                kind = IdentityChangeKind.CONTACT_DELETED,
                idKind = idKind,
                idValue = normalizeIdOrNull(idValue),
                fromContactId = contactId,
                fromName = name,
                fromChatId = chatId,
                source = source,
                batchId = batchId,
            )
        )
    }

    /**
     * A bulk `messages.chat_id` rewrite. [affectedRows] is the statement's own
     * row count, so a re-parent that moved nothing is distinguishable from one
     * that moved a year of history.
     */
    suspend fun recordReparent(
        fromChatId: String,
        toChatId: String,
        affectedRows: Int,
        fromContactId: Int? = null,
        toContactId: Int? = null,
        fromName: String? = null,
        toName: String? = null,
        source: String = IdentityLogSource.UNKNOWN,
        batchId: Long? = null,
    ) = safely("recordReparent") {
        dao.log(
            ContactIdentityLogEntity(
                kind = IdentityChangeKind.MESSAGES_REPARENTED,
                fromContactId = fromContactId,
                toContactId = toContactId,
                fromName = fromName,
                toName = toName,
                fromChatId = fromChatId,
                toChatId = toChatId,
                affectedRows = affectedRows,
                source = source,
                batchId = batchId,
            )
        )
    }

    // ── Reads (exposed through AppRepository) ────────────────────────────

    suspend fun recent(limit: Int): List<ContactIdentityLogEntity> =
        withContext(Dispatchers.IO) { dao.recent(limit) }

    suspend fun historyForId(idValue: String): List<ContactIdentityLogEntity> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeIdOrNull(idValue) ?: return@withContext emptyList()
            dao.historyForId(normalized)
        }

    suspend fun ownershipAt(idValue: String, atMs: Long): ContactIdentityLogEntity? =
        withContext(Dispatchers.IO) {
            val normalized = normalizeIdOrNull(idValue) ?: return@withContext null
            dao.ownershipAt(normalized, atMs)
        }

    suspend fun historyForContact(contactId: Int): List<ContactIdentityLogEntity> =
        withContext(Dispatchers.IO) { dao.historyForContact(contactId) }

    suspend fun batch(batchId: Long): List<ContactIdentityLogEntity> =
        withContext(Dispatchers.IO) { dao.batch(batchId) }

    suspend fun reparentsForChat(chatId: String): List<ContactIdentityLogEntity> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeIdOrNull(chatId) ?: return@withContext emptyList()
            dao.reparentsForChat(normalized)
        }

    suspend fun prune(cutoffMs: Long): Int =
        withContext(Dispatchers.IO) { runCatching { dao.prune(cutoffMs) }.getOrDefault(0) }

    /**
     * Runs [block] on the IO dispatcher, swallowing any failure. Audit writes are
     * best-effort by design — see the class doc.
     */
    private suspend inline fun safely(op: String, crossinline block: suspend () -> Unit) {
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onFailure { Log.w(TAG, "$op: identity log write failed", it) }
    }
}