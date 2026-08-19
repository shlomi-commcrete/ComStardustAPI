package com.commcrete.stardust.room.new_db.internal

import com.commcrete.stardust.room.new_db.AppRepository
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.MessageType
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
}