package com.commcrete.stardust.room.new_db.internal

import com.commcrete.stardust.room.StardustStorage
import com.commcrete.stardust.room.new_db.AppRepository
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.FileSummary
import com.commcrete.stardust.room.new_db.message.FileTransferCancellation
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.MessageType
import com.commcrete.stardust.room.new_db.message.SendFailureReason
import com.commcrete.stardust.room.new_db.message.SosAck
import com.commcrete.stardust.room.new_db.message.settlementForStaleInFlight
import com.commcrete.stardust.util.FileReceiver
import com.commcrete.stardust.util.RegisteredUserUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Messages domain, backed by [MessageDao]: lane-aware reads, the serialized
 * [saveMessage] pipeline, seen marking, archival, unseen counters and inbound
 * chat resolution.
 *
 * [saveMutex] serializes the whole contact-resolve -> chat-resolve -> insert
 * pipeline. [withSaveLock] shares it with `deleteChat` and `clearData`.
 */
internal class MessagesRepository(
    private val messagesDao: MessageDao,
    private val chatsDao: ChatDao,
    private val contactsDao: ContactsDao,
    private val caches: RepositoryCaches,
    private val registeredUserIdsProvider: () -> List<String>,
    private val savePttRequired: () -> Boolean,
    private val insertContactWithChat: suspend (FullContactData) -> Unit,
) {

    private val saveMutex = Mutex()

    suspend fun <R> withSaveLock(block: suspend () -> R): R = saveMutex.withLock { block() }

    // ─────────────────────────────────────────────────────────────────────
    // Lane-agnostic reads. participantId null = chat-scoped (group lanes).
    // Boundaries are passed as primitives; MessageEntity is the caller's ref.
    // ─────────────────────────────────────────────────────────────────────

    fun observeMessages(
        chatId: String,
        participantId: String?,
        limit: Int = AppRepository.PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<List<MessageEntity>> {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return flowOf(emptyList())
        val f = MessageTypeFilter.of(types, excludeTypes)
        return messagesDao.observeLatest(
            chatId = normalizedChatId,
            participantId = normalizeIdOrNull(participantId),
            limit = limit.coerceAtLeast(1),
            types = f.include,
            excludeTypes = f.exclude,
        ).conflate().flowOn(Dispatchers.IO)
    }

    /** Null boundary = newest page. */
    suspend fun loadOlder(
        chatId: String,
        participantId: String?,
        beforeEpochMs: Long?,
        beforeId: Int?,
        limit: Int = AppRepository.PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext emptyList()
        val f = MessageTypeFilter.of(types, excludeTypes)
        messagesDao.loadOlder(
            chatId = normalizedChatId,
            participantId = normalizeIdOrNull(participantId),
            beforeEpochMs = beforeEpochMs,
            beforeId = beforeId,
            limit = limit.coerceAtLeast(1),
            types = f.include,
            excludeTypes = f.exclude,
        )
    }

    suspend fun loadNewer(
        chatId: String,
        participantId: String?,
        afterEpochMs: Long,
        afterId: Int,
        limit: Int = AppRepository.PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext emptyList()
        val f = MessageTypeFilter.of(types, excludeTypes)
        messagesDao.loadNewer(
            chatId = normalizedChatId,
            participantId = normalizeIdOrNull(participantId),
            afterEpochMs = afterEpochMs,
            afterId = afterId,
            limit = limit.coerceAtLeast(1),
            types = f.include,
            excludeTypes = f.exclude,
        )
    }

    /**
     * Window centred on the anchor. REPLACES the caller's window — it must never
     * be unioned with a disjoint retained window, because interval marking fills
     * across adjacent loaded positions and a hole would be marked seen.
     */
    suspend fun loadAround(
        chatId: String,
        participantId: String?,
        anchorEpochMs: Long,
        anchorId: Int,
        limit: Int = AppRepository.PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext emptyList()
        val participant = normalizeIdOrNull(participantId)
        val f = MessageTypeFilter.of(types, excludeTypes)
        val half = (limit / 2).coerceAtLeast(1)

        val older = messagesDao.loadOlder(
            chatId = normalizedChatId,
            participantId = participant,
            beforeEpochMs = anchorEpochMs,
            beforeId = anchorId,
            limit = half,
            types = f.include,
            excludeTypes = f.exclude,
        )
        val fromAnchor = messagesDao.loadAtOrNewer(
            chatId = normalizedChatId,
            participantId = participant,
            fromEpochMs = anchorEpochMs,
            fromId = anchorId,
            limit = half,
            types = f.include,
            excludeTypes = f.exclude,
        )
        older + fromAnchor
    }

    /** Oldest unseen row (state 2), or null. */
    suspend fun findFirstUnseen(
        chatId: String,
        participantId: String?,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): MessageEntity? = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext null
        val f = MessageTypeFilter.of(types, excludeTypes)
        messagesDao.findFirstUnseen(
            chatId = normalizedChatId,
            participantId = normalizeIdOrNull(participantId),
            types = f.include,
            excludeTypes = f.exclude,
        )
    }

    /** Marks the swept range seen. Monotonic — re-running is a no-op. */
    suspend fun markSeenInRange(
        chatId: String,
        participantId: String?,
        fromEpochMs: Long,
        fromId: Int,
        toEpochMs: Long,
        toId: Int,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Int = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext 0
        val f = MessageTypeFilter.of(types, excludeTypes)
        messagesDao.markSeenInRange(
            chatId = normalizedChatId,
            participantId = normalizeIdOrNull(participantId),
            fromEpochMs = fromEpochMs,
            fromId = fromId,
            toEpochMs = toEpochMs,
            toId = toId,
            types = f.include,
            excludeTypes = f.exclude,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Writes — saveMessage pipeline  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    suspend fun saveMessage(message: MessageEntity, groupId: String? = null): Long? =
        saveMutex.withLock {
            if (!canMessageBeSaved(message)) return@withLock null

            withContext(Dispatchers.IO) {
                val senderId = message.senderID.trim().lowercase()
                val peerId =
                    if (RegisteredUserUtils.isRegisteredUser(senderId)) message.receiverID
                    else senderId

                val contactId = ensureContactExistsByUserId(peerId) ?: return@withContext null
                val chatId = message.chatId?.takeIf { it.isNotBlank() }
                    ?: if (groupId != null) resolveGroupChatId(groupId)
                    else resolveOrCreatePrivateChatId(contactId, peerId)
                        ?: return@withContext null

                messagesDao.addMessage(message.copy(chatId = normalizeId(chatId!!)))
            }
        }

    private fun canMessageBeSaved(message: MessageEntity): Boolean =
        !(message.type == MessageType.PTT && !savePttRequired())

    private suspend fun ensureContactExistsByUserId(id: String): Int? {
        val normalizedId = normalizeId(id)

        caches.getContactId(normalizedId)?.let { return it }
        contactsDao.findContactIdByMainCommunicationId(normalizedId)?.let { return it }

        FullContactData.createUserContact(name = id, image = null, userId = normalizedId)
            ?.let { insertContactWithChat(it) }

        return caches.getContactId(normalizedId)
            ?: contactsDao.findContactIdByMainCommunicationId(normalizedId)
    }

    private suspend fun resolvePrivateChatId(contactId: Int): String? =
        chatsDao.findPrivateChatIdByContactId(contactId)

    private suspend fun resolveOrCreatePrivateChatId(contactId: Int, fallbackName: String): String? {
        resolvePrivateChatId(contactId)?.let { return it }

        val contact = contactsDao.getContactById(contactId)
        chatsDao.insertChatWithParticipants(
            ChatEntity(
                name = contact?.name ?: fallbackName,
                image = contact?.image,
                type = ChatType.PRIVATE,
            ),
            listOf(contactId),
        )
        return resolvePrivateChatId(contactId)
    }

    private suspend fun resolveGroupChatId(groupId: String): String? {
        val normalizedGroupId = normalizeId(groupId)

        if (caches.isGroupIdCached(normalizedGroupId)) {
            return contactsDao.findResolvedGroupChatIdByGroupId(normalizedGroupId)
                ?: createGroupContactAndChat(normalizedGroupId, groupId)
        }

        val groupContactId = contactsDao.findContactIdByGroupId(normalizedGroupId)
            ?: return createGroupContactAndChat(normalizedGroupId, groupId)

        return findOrCreateGroupChat(groupContactId, groupId)
            ?.also { caches.addGroupId(normalizedGroupId) }
    }

    private suspend fun findOrCreateGroupChat(groupContactId: Int, groupId: String): String? {
        chatsDao.findGroupChatIdByContactId(groupContactId)?.let { return it }

        val groupContact = contactsDao.getContactById(groupContactId)
            ?: ContactEntity(name = groupId, type = ContactType.GROUP)
        chatsDao.insertChatWithParticipants(
            ChatEntity(name = groupContact.name, image = groupContact.image, type = ChatType.GROUP),
            (contactsDao.getAllMemberContactIds() + groupContactId).distinct(),
        )
        return chatsDao.findGroupChatIdByContactId(groupContactId)
    }

    private suspend fun createGroupContactAndChat(
        normalizedGroupId: String,
        groupId: String,
    ): String? {
        FullContactData.createGroupContact(name = groupId, image = null, groupId = normalizedGroupId)
            ?.let { insertContactWithChat(it) }
        caches.addGroupId(normalizedGroupId)

        return contactsDao.findContactIdByGroupId(normalizedGroupId)
            ?.let { chatsDao.findGroupChatIdByContactId(it) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inbound chat resolution  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    suspend fun getChatIdForReceivedPackage(participantId: String, groupId: String?): String {
        val normalizedParticipantId = normalizeIdOrNull(participantId) ?: return ""
        val normalizedGroupId = normalizeIdOrNull(groupId)
        val cacheKey = buildReceivedChatCacheKey(normalizedGroupId, normalizedParticipantId)

        caches.getChatId(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val resolved = resolveChatIdForReceivedPackage(
                participantId = normalizedParticipantId,
                groupId = normalizedGroupId,
            ).orEmpty()

            if (resolved.isNotBlank()) caches.putChatId(cacheKey, resolved)
            resolved
        }
    }

    suspend fun getChatForReceivedPackage(participantId: String, groupId: String?): ChatEntity? {
        val chatId = getChatIdForReceivedPackage(participantId, groupId)
        if (chatId.isBlank()) return null
        return withContext(Dispatchers.IO) { chatsDao.getChatByChatId(chatId) }
    }

    private fun buildReceivedChatCacheKey(groupId: String?, participantId: String): String =
        groupId?.let { "group:$it" } ?: "private:$participantId"

    private suspend fun resolveChatIdForReceivedPackage(
        participantId: String,
        groupId: String?,
    ): String? {
        // Explicit local so the DAO calls see a non-null String (smart-cast across
        // a suspend boundary on a nullable parameter is what produced the
        // "String? but String was expected" mismatch).
        val nonNullGroupId: String? = groupId
        if (nonNullGroupId != null) {
            return contactsDao.findResolvedGroupChatIdByGroupId(nonNullGroupId)
                ?: contactsDao.findContactIdByGroupId(nonNullGroupId)
                    ?.let { chatsDao.findGroupChatIdByContactId(it) }
        }

        val contactId = contactsDao.findContactIdByUserId(participantId)
            ?: contactsDao.findContactIdByDeviceId(participantId)
            ?: return null

        return chatsDao.findPrivateChatIdByContactId(contactId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // State transitions  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    suspend fun updateMessageReceived(messageId: Long) =
        messagesDao.updateMessageState(messageId, MessageState.RECEIVED)

    suspend fun updateMessageState(messageId: Long, state: MessageState) =
        withContext(Dispatchers.IO) { messagesDao.updateMessageState(messageId, state) }

    /**
     * Records that [ackedBy] acknowledged an SOS this user sent, appending them to the
     * [MessageExtraData.Sos.acks] list on that SOS's own row. Returns false when no row
     * matched, when it carries no SOS extra data, or when this acker is already on it.
     *
     * The row is found, not passed in: no id survives the round trip to the radio, so
     * the ack has to be matched back by address. [chatId] — the chat the ack packet
     * itself resolved to — is tried first, which is exact for a direct SOS and for a
     * group ack that came back through the group. When that finds nothing, the acker's
     * chat membership is tried, which catches a group SOS acknowledged straight back to
     * this user.
     *
     * Appending rather than overwriting is the point: an SOS addressed to a group can be
     * acknowledged by several members, and the first responder is the one worth keeping.
     * Re-recording the same acker is a no-op, so a retransmitted ack changes nothing.
     */
    suspend fun recordSosAck(
        chatId: String?,
        ackedBy: String,
        ackedAtMs: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        val ackerId = normalizeIdOrNull(ackedBy) ?: return@withContext false
        val me = normalizeIdOrNull(RegisteredUserUtils.currentUserFlow.value?.appId)
            ?: return@withContext false

        val row = findSosRowForAck(chatId, ackerId, me) ?: run {
            Timber.d("SOS ack from $ackerId matched no SOS sent by this user")
            return@withContext false
        }
        val sos = row.extraData as? MessageExtraData.Sos ?: return@withContext false
        if (sos.acks.any { it.ackedBy == ackerId }) return@withContext false

        messagesDao.recordSosAck(
            messageId = row.id.toLong(),
            extraData = sos.copy(acks = sos.acks + SosAck(ackedBy = ackerId, ackedAtMs = ackedAtMs)),
        ) > 0
    }

    /** The SOS row an incoming ack belongs to. See [recordSosAck] for the two passes. */
    private suspend fun findSosRowForAck(
        chatId: String?,
        ackerId: String,
        me: String,
    ): MessageEntity? {
        normalizeIdOrNull(chatId)?.let { normalizedChatId ->
            messagesDao.findLatestSosSentInChat(normalizedChatId, me, MessageType.SOS)
                ?.let { return it }
        }

        val ackerContactId = contactsDao.findContactIdByMainCommunicationId(ackerId) ?: return null
        return messagesDao.findLatestSosSentToParticipant(me, ackerContactId, MessageType.SOS)
    }

    /**
     * Records a file/image transfer failure on an existing (outgoing) message row:
     * state FAILED plus [failure] merged into the row's extra_data. Returns false if
     * the write was refused because the row had already settled — see
     * [MessageDao.markFileTransferFailed].
     *
     * The reason is merged here, never assembled by the caller: extra_data already
     * carries the title, path and summary of the attachment and must survive.
     */
    suspend fun markFileTransferFailed(
        messageId: Long,
        failure: FileReceiver.FileFailure,
    ): Boolean = withContext(Dispatchers.IO) {
        val attachment = messagesDao.getMessageById(messageId)?.extraData
            as? MessageExtraData.Attachment
        messagesDao.markFileTransferFailed(
            messageId = messageId,
            extraData = attachment?.copy(failure = failure),
            nowMs = System.currentTimeMillis(),
        ) > 0
    }

    /**
     * Records that an outgoing message never reached its destination: state FAILED plus [reason]
     * merged into the row's extra_data. Returns whether the row ends up carrying this failure —
     * false only when the write was refused because the row had already settled, see
     * [MessageDao.markSendFailed].
     *
     * The reason is merged here rather than assembled by the caller, so whatever the row already
     * carries — the text, the attachment's path and summary, an SOS's coordinates and acks —
     * survives. Applies to any message type, which is why it reads and writes the reason through
     * [MessageExtraData.sendFailure] / [MessageExtraData.withSendFailure] rather than casting to
     * one subtype the way the file-transfer calls above do.
     */
    suspend fun markSendFailed(
        messageId: Long,
        reason: SendFailureReason,
    ): Boolean = withContext(Dispatchers.IO) {
        val extraData = messagesDao.getMessageById(messageId)?.extraData
        // A long message goes out as several packages that share one row id, so each of them
        // reports the same failure for the same row. Reading the reason back through the base
        // settles that without caring what kind of message it is: if the row already says this,
        // there is nothing to write and no second FAILED stamp to apply.
        if (extraData != null && extraData.sendFailure == reason) return@withContext true
        messagesDao.markSendFailed(
            messageId = messageId,
            extraData = extraData?.withSendFailure(reason),
        ) > 0
    }

    /**
     * Settles an incoming transfer's in-flight row as RECEIVED, merging the [path] the
     * file landed at and its [fileSummary] into the row's extra_data. Returns false if
     * the write was refused because the row had already settled — see
     * [MessageDao.markIncomingTransferReceived].
     *
     * Merged here rather than assembled by the caller so the title and subtype written
     * when the transfer started survive: the row is the same message throughout, and only
     * the two things that were unknowable until the file existed are filled in.
     */
    suspend fun markIncomingTransferReceived(
        messageId: Long,
        path: String,
        fileSummary: FileSummary?,
    ): Boolean = withContext(Dispatchers.IO) {
        val attachment = messagesDao.getMessageById(messageId)?.extraData
            as? MessageExtraData.Attachment
        messagesDao.markIncomingTransferReceived(
            messageId = messageId,
            extraData = attachment?.copy(path = path, fileSummary = fileSummary),
        ) > 0
    }

    /**
     * Records an incoming transfer's failure on its in-flight row: state FAILED plus
     * [failure] merged into the row's extra_data, leaving the row where it sits in the
     * conversation. Returns false if the write was refused — see
     * [MessageDao.markIncomingTransferFailed].
     *
     * The path is cleared with the same write: a failed transfer has no file, and a row
     * still pointing at where one would have gone invites a UI to open nothing.
     */
    suspend fun markIncomingTransferFailed(
        messageId: Long,
        failure: FileReceiver.FileFailure,
    ): Boolean = withContext(Dispatchers.IO) {
        val attachment = messagesDao.getMessageById(messageId)?.extraData
            as? MessageExtraData.Attachment
        messagesDao.markIncomingTransferFailed(
            messageId = messageId,
            extraData = attachment?.copy(path = "", failure = failure),
        ) > 0
    }

    /**
     * Removes an in-flight row that will never settle, because the sender restarted the
     * transfer and the retry owns the conversation now. Returns false if the row had
     * already settled, in which case it is history and stays.
     */
    suspend fun deleteInFlightMessage(messageId: Long): Boolean = withContext(Dispatchers.IO) {
        messagesDao.deleteInFlightMessage(messageId) > 0
    }

    /**
     * Records a user-cancelled outgoing transfer on its message row: state CANCELLED
     * plus [cancellation] merged into the row's extra_data. Returns false if the write
     * was refused because the row had already settled — see
     * [MessageDao.markFileSendCancelled].
     *
     * Any failure reason previously recorded on the row is cleared: a transfer the user
     * stopped is cancelled, not failed, and leaving both on the row would let a UI show
     * an error for a deliberate stop.
     */
    suspend fun markFileSendCancelled(
        messageId: Long,
        cancellation: FileTransferCancellation,
    ): Boolean = withContext(Dispatchers.IO) {
        val attachment = messagesDao.getMessageById(messageId)?.extraData
            as? MessageExtraData.Attachment
        messagesDao.markFileSendCancelled(
            messageId = messageId,
            extraData = attachment?.copy(cancellation = cancellation, failure = null),
            nowMs = System.currentTimeMillis(),
        ) > 0
    }

    // ─────────────────────────────────────────────────────────────────────
    // Interrupted transfers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Settles every row still marked RECEIVING from before [cutoffMs], and returns how many
     * were settled.
     *
     * An in-flight row is finalized by the receiver that created it — a PTT stream's actor, a
     * file transfer's `FileReceiver` — and both live only in memory. A process that dies
     * mid-transfer therefore leaves a row nothing will ever finish, which reads in the
     * conversation as a message that has been arriving since yesterday. This is the only thing
     * that can tell that apart from a transfer genuinely still in flight, and it can only do so
     * at startup: [cutoffMs] is set before this process built the repository, so a row older
     * than it cannot belong to anything running now.
     *
     * Each row is classified by [settlementForStaleInFlight] and written under a guard that
     * requires it to still be in flight, so a transfer that settles while the sweep runs keeps
     * its own outcome.
     *
     * Every failure is swallowed: a sweep that cannot run leaves the rows exactly as it found
     * them, and the next startup tries again.
     */
    suspend fun sweepStaleInFlight(cutoffMs: Long): Int = withContext(Dispatchers.IO) {
        val rows = runCatching { messagesDao.getStaleInFlight(cutoffMs) }
            .onFailure { Timber.e(it, "Stale sweep: could not read in-flight rows") }
            .getOrNull()
            ?: return@withContext 0

        var settled = 0
        rows.forEach { row ->
            val settlement = settlementForStaleInFlight(row.extraData, ::hasPlayableAudio)
            val written = runCatching {
                messagesDao.settleStaleInFlight(row.id, settlement.state, settlement.extraData)
            }
                .onFailure { Timber.w(it, "Stale sweep: could not settle message ${row.id}") }
                .getOrDefault(0)
            settled += written
        }

        if (settled > 0) Timber.d("Stale sweep: settled $settled interrupted transfer(s)")
        settled
    }

    /**
     * Whether the WAV at [path] holds any audio — anything past the 44-byte header. A PTT is
     * written through as it arrives, so this is how much of the recording survived the crash.
     */
    private fun hasPlayableAudio(path: String): Boolean =
        path.isNotBlank() && runCatching { File(path).length() > WAV_HEADER_BYTES }.getOrDefault(false)

    // ─────────────────────────────────────────────────────────────────────
    // Media relocation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Re-points attachment and PTT rows at media that [StardustStorage] moved
     * out of its root and into the media subtree, and returns how many rows
     * were rewritten.
     *
     * The move happens in `StardustAPI.init`, which is long before there is a
     * database to update, so the two halves cannot be one operation: the files
     * move first and the rows catch up here, on the first use of the
     * repository. Until they do, the paths point at a file that is no longer
     * there — a gap of one startup, not of one release.
     *
     * Idempotent, and cheap enough to run unguarded by any "done" flag:
     * [StardustStorage.relocatedMediaPath] answers only for a path that still
     * names the old location, so the second run rewrites nothing. A flag would
     * have to survive the process that set it, which is exactly what this is
     * recovering from.
     */
    suspend fun rewriteRelocatedMediaPaths(): Int = withContext(Dispatchers.IO) {
        val rows = runCatching { messagesDao.getMessagesByTypes(MEDIA_PATH_TYPES) }
            .onFailure { Timber.e(it, "Media relocation: could not read message rows") }
            .getOrNull()
            ?: return@withContext 0

        var rewritten = 0
        rows.forEach { row ->
            val relocated = row.extraData?.relocated() ?: return@forEach
            val updated = runCatching { messagesDao.updateExtraData(row.id, relocated) }
                .onFailure { Timber.w(it, "Media relocation: could not rewrite message ${row.id}") }
                .getOrDefault(0)
            rewritten += updated
        }

        if (rewritten > 0) Timber.d("Media relocation: re-pointed $rewritten message row(s)")
        rewritten
    }

    /**
     * The same extra data with its file path moved to where the file now is, or
     * null when nothing needs to change. Only the path is touched — a title or
     * a cached summary describes the file, not its location.
     */
    private fun MessageExtraData.relocated(): MessageExtraData? = when (this) {
        is MessageExtraData.Attachment ->
            StardustStorage.relocatedMediaPath(path)?.let { copy(path = it) }

        is MessageExtraData.PTT ->
            StardustStorage.relocatedMediaPath(path)?.let { copy(path = it) }

        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Deletion & archival  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    suspend fun clearChatMessages(chatId: String) = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext
        messagesDao.clearChatMessages(normalizedChatId)
    }

    suspend fun clearChatMessagesInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
    ) = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext
        messagesDao.clearChatInRange(
            chatId = normalizedChatId,
            startTimestamp = minOf(startTimestamp, endTimestamp),
            endTimestamp = maxOf(startTimestamp, endTimestamp),
        )
    }

    suspend fun archiveMessage(messageId: Int): Boolean = withContext(Dispatchers.IO) {
        messagesDao.archiveMessage(messageId) > 0
    }

    suspend fun archiveMessages(messageIds: Collection<Int>): Int = withContext(Dispatchers.IO) {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return@withContext 0
        messagesDao.archiveMessages(ids)
    }

    suspend fun archiveMessagesInRange(startTimestamp: Long, endTimestamp: Long): Boolean =
        withContext(Dispatchers.IO) {
            messagesDao.archiveAllMessages(startTimestamp, endTimestamp) > 0
        }

    // ─────────────────────────────────────────────────────────────────────
    // Unseen counters
    // ─────────────────────────────────────────────────────────────────────

    fun observeReceivedMessageCount(
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<Int> {
        val f = MessageTypeFilter.of(types, excludeTypes)
        return messagesDao.observeReceivedMessagesCount(f.include, f.exclude)
            .flowOn(Dispatchers.IO)
    }

    fun observeUnseenCountsForChats(
        chatIds: List<String>,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<Map<String, Int>> {
        val normalizedChatIds = chatIds.mapNotNull { normalizeIdOrNull(it) }.distinct()
        if (normalizedChatIds.isEmpty()) return flowOf(emptyMap())

        val f = MessageTypeFilter.of(types, excludeTypes)
        return messagesDao.observeUnseenCountsForChats(normalizedChatIds, f.include, f.exclude)
            .map { rows -> rows.associate { it.chatId to it.unseenCount } }
            .flowOn(Dispatchers.IO)
    }

    fun observeUnseenCountsForTargets(
        chatIds: List<String>,
        targetIds: List<String>,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<Map<String, Map<String, Int>>> {
        val normalizedChatIds = chatIds.mapNotNull { normalizeIdOrNull(it) }.distinct()
        val normalizedTargetIds = targetIds.mapNotNull { normalizeIdOrNull(it) }.distinct()
        if (normalizedChatIds.isEmpty() || normalizedTargetIds.isEmpty()) {
            return flowOf(emptyMap())
        }

        val f = MessageTypeFilter.of(types, excludeTypes)
        return messagesDao
            .observeUnseenCountsForTargets(
                normalizedChatIds, normalizedTargetIds, f.include, f.exclude,
            )
            .map { rows ->
                val out = HashMap<String, MutableMap<String, Int>>()
                for (row in rows) {
                    out.getOrPut(row.chatId) { HashMap() }[row.targetId] = row.unseenCount
                }
                out
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Normalizes the two lane filters for the DAO: empty lists become null (an
     * empty `IN ()` matches nothing), and an explicit include list wins over an
     * exclude list so the two cannot contradict.
     */
    private data class MessageTypeFilter(
        val include: List<MessageType>?,
        val exclude: List<MessageType>?,
    ) {
        companion object {
            fun of(
                types: List<MessageType>?,
                excludeTypes: List<MessageType>?,
            ): MessageTypeFilter {
                val include = types?.distinct()?.takeIf { it.isNotEmpty() }
                val exclude =
                    if (include != null) null
                    else excludeTypes?.distinct()?.takeIf { it.isNotEmpty() }
                return MessageTypeFilter(include, exclude)
            }
        }
    }

    private companion object {
        /** The message types whose extra data carries a file path. */
        private val MEDIA_PATH_TYPES = listOf(MessageType.ATTACHMENT, MessageType.PTT)

        /** A WAV of exactly this length is a header and no audio. */
        private const val WAV_HEADER_BYTES = 44L
    }
}