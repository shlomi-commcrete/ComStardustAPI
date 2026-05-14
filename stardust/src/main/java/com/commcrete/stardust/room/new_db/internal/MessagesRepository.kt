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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns every messages-domain operation backed by [MessageDao]:
 *
 *  - **Reads** — paginated and live message feeds
 *    ([getMessages], [loadOlderMessages], [loadPageForTarget],
 *    [loadPageForChat], [observeLatestForTarget]).
 *  - **Writes** — the serialized save pipeline ([saveMessage]) plus state
 *    transitions ([updateMessageReceived], [updateChatRead]) and archival
 *    helpers ([archiveMessage], [archiveMessages], [archiveMessagesInRange]).
 *  - **Unseen counters** — composite-index-backed live COUNTs
 *    ([observeUnseenCountForChat], [observeUnseenCountForTarget],
 *    [observeUnseenCountsForChats], [observeUnseenCountsForTargets]).
 *  - **Inbound chat resolution** — cache-aware lookup used by the hot
 *    incoming-package path ([getChatIdForReceivedPackage],
 *    [getChatForReceivedPackage]).
 *
 * # Ownership of [saveMutex]
 * [saveMessage] runs the entire contact-resolve → chat-resolve → insert pipeline
 * under [saveMutex] so two simultaneous calls cannot interleave their
 * resolution steps or duplicate a contact/chat. Other callers that need to
 * synchronize against in-flight saves (chat deletion, "wipe everything")
 * use [withSaveLock] to share the same lock.
 *
 * # Cross-domain dependencies
 * `saveMessage` reaches into the contacts and chats domains to ensure the
 * sender contact exists and to resolve / create the destination chat. Rather
 * than duplicating that logic, the repository injects two callbacks:
 *
 *  - [insertContactWithChat] — auto-creates a USER or GROUP contact (with its
 *    associated chat) when an unknown id arrives in [saveMessage]. Wired to
 *    `AppRepository::insertContactWithChat`.
 *  - [savePttRequired] — gates whether [MessageType.PTT] messages are
 *    persisted at all (driven by a runtime user setting).
 *  - [registeredUserIdsProvider] — lower-cased identifiers of the currently
 *    registered user, used to scope [loadPageForChat] to messages that
 *    actually involve the user.
 *
 * Chat *resolution* helpers ([resolvePrivateChatId], [resolveGroupChatId],
 * [findOrCreateGroupChat], [createGroupContactAndChat]) live here because
 * `saveMessage` is their only caller; they will move out to `ChatsRepository`
 * once that class exists.
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

    /**
     * Serializes every [saveMessage] call — contact resolution, chat resolution,
     * and the final insert all run under this lock. [withSaveLock] exposes the
     * same lock to [AppRepository.clearData] and chat-deletion paths so they
     * cannot race with an in-flight save.
     */
    private val saveMutex = Mutex()

    /**
     * Runs [block] under the same mutex that guards [saveMessage]. Used by
     * `clearData` and `deleteChat` to wait for any in-flight save to complete
     * before they touch the messages table.
     */
    suspend fun <R> withSaveLock(block: suspend () -> R): R = saveMutex.withLock { block() }

    // ─────────────────────────────────────────────────────────────────────
    // Reads
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.getMessages`. */
    fun getMessages(
        chatId: String,
        participantId: String? = null,
        limit: Int = AppRepository.PAGE_SIZE,
    ): Flow<List<MessageEntity>> {
        return if (participantId != null)
            messagesDao.getLatestMessagesByParticipant(chatId, participantId.trim().lowercase(), limit)
        else
            messagesDao.getLatestMessages(chatId, limit)
    }

    /** See `AppRepository.loadOlderMessages`. */
    suspend fun loadOlderMessages(
        chatId: String,
        beforeEpochMs: Long,
        participantId: String? = null,
        limit: Int = AppRepository.PAGE_SIZE,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        if (participantId != null)
            messagesDao.loadOlderMessagesByParticipant(chatId, participantId.trim().lowercase(), beforeEpochMs, limit)
        else
            messagesDao.loadOlderMessages(chatId, beforeEpochMs, limit)
    }

    /** See `AppRepository.loadPageForTarget`. */
    suspend fun loadPageForTarget(
        targetId: String,
        chatId: String,
        page: Int,
        pageSize: Int = AppRepository.PAGE_SIZE,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext emptyList()
        val normalizedTargetId = normalizeIdOrNull(targetId) ?: return@withContext emptyList()
        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceAtLeast(1)

        messagesDao.loadPageForTarget(
            chatId = normalizedChatId,
            participantId = normalizedTargetId,
            limit = safePageSize,
            offset = safePage * safePageSize,
        )
    }

    /** See `AppRepository.loadPageForChat`. */
    suspend fun loadPageForChat(
        chatId: String,
        page: Int,
        pageSize: Int = AppRepository.PAGE_SIZE,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext emptyList()
        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceAtLeast(1)

        val registeredUserIds = registeredUserIdsProvider()
        if (registeredUserIds.isEmpty()) return@withContext emptyList()

        messagesDao.loadPageForChatScopedToUsers(
            chatId = normalizedChatId,
            userIds = registeredUserIds,
            limit = safePageSize,
            offset = safePage * safePageSize,
        )
    }

    /** See `AppRepository.observeLatestForTarget`. */
    fun observeLatestForTarget(
        targetId: String,
        chatId: String,
        limit: Int = AppRepository.PAGE_SIZE,
    ): Flow<List<MessageEntity>> {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return flowOf(emptyList())
        val normalizedTargetId = normalizeIdOrNull(targetId) ?: return flowOf(emptyList())
        val safeLimit = limit.coerceAtLeast(1)
        return messagesDao.getLatestMessagesByParticipant(normalizedChatId, normalizedTargetId, safeLimit)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Writes — saveMessage pipeline
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Resolves chat context then inserts [message]. See `AppRepository.saveMessage`.
     */
    suspend fun saveMessage(message: MessageEntity): Long? = saveMessage(message, null)

    /**
     * Resolves chat context then inserts [message]. See `AppRepository.saveMessage`.
     */
    suspend fun saveMessage(message: MessageEntity, groupId: String? = null): Long? =
        saveMutex.withLock {
            if (!canMessageBeSaved(message)) return@withLock null

            withContext(Dispatchers.IO) {
                val senderId = message.senderID.trim().lowercase()
                val id = if(RegisteredUserUtils.isRegisteredUser(senderId)) message.receiverID else senderId
                val contactId = ensureContactExistsByUserId(id) ?: return@withContext null
                val chatId = message.chatId?.takeIf { it.isNotBlank() }
                    ?: if (groupId != null) {
                        resolveGroupChatId(groupId)
                    } else {
                        resolveOrCreatePrivateChatId(contactId, id)
                    }
                val chatIdString = chatId ?: return@withContext null
                messagesDao.addMessage(message.copy(chatId = normalizeId(chatIdString)))
            }
        }

    private fun canMessageBeSaved(message: MessageEntity): Boolean {
        // Only save PTT messages if the user has enabled the setting.
        return !(message.type == MessageType.PTT && !savePttRequired())
    }

    /** Ensures sender exists in DB, auto-creating a USER contact if not. Returns the contact id. */
    private suspend fun ensureContactExistsByUserId(id: String): Int? {
        val normalizedSenderId = normalizeId(id)

        // Fast path for hot message-insert loop — check cache first.
        caches.getContactId(normalizedSenderId)?.let { return it }

        // Single-shot DB lookup across user_ids + device_ids + group_ids.
        // Uses the universal lookup that checks all three identity tables.
        contactsDao.findContactIdByMainCommunicationId(normalizedSenderId)?.let { return it }

        // Not found — auto-create USER contact (this also populates the cache).
        FullContactData.createUserContact(
            name = id,
            image = null,
            userId = normalizedSenderId,
        )?.let { insertContactWithChat(it) }

        return caches.getContactId(normalizedSenderId)
            ?: contactsDao.findContactIdByMainCommunicationId(normalizedSenderId)
    }

    /** Finds the private chat id for [contactId]. */
    private suspend fun resolvePrivateChatId(contactId: Int): String? =
        chatsDao.findPrivateChatIdByContactId(contactId)

    /**
     * Resolves the private chat for [contactId]; if it does not exist yet, creates it.
     * This is used when [MessageEntity.chatId] is null and the caller did not provide a group id.
     */
    private suspend fun resolveOrCreatePrivateChatId(contactId: Int, fallbackName: String): String? {
        resolvePrivateChatId(contactId)?.let { return it }

        val contact = contactsDao.getContactById(contactId)
        val chatName = contact?.name ?: fallbackName
        val chatImage = contact?.image

        chatsDao.insertChatWithParticipants(
            ChatEntity(name = chatName, image = chatImage, type = ChatType.PRIVATE),
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

        return if (groupContactId != null) {
            val chatId = findOrCreateGroupChat(groupContactId, groupId)
            if (chatId != null) caches.addGroupId(normalizedGroupId)
            chatId
        } else {
            createGroupContactAndChat(normalizedGroupId, groupId)
        }
    }

    private suspend fun findOrCreateGroupChat(groupContactId: Int, groupId: String): String? {
        return chatsDao.findGroupChatIdByContactId(groupContactId)
            ?: run {
                val groupContact = contactsDao.getContactById(groupContactId)
                    ?: ContactEntity(name = groupId, type = ContactType.GROUP)
                // Inlined `createGroupChat` from AppRepository — avoids a callback for one DAO call.
                chatsDao.insertChatWithParticipants(
                    ChatEntity(name = groupContact.name, image = groupContact.image, type = ChatType.GROUP),
                    (contactsDao.getAllMemberContactIds() + groupContactId).distinct(),
                )
                chatsDao.findGroupChatIdByContactId(groupContactId)
            }
    }

    private suspend fun createGroupContactAndChat(normalizedGroupId: String, groupId: String): String? {
        FullContactData.createGroupContact(
            name = groupId,
            image = null,
            groupId = normalizedGroupId,
        )?.let { insertContactWithChat(it) }
        caches.addGroupId(normalizedGroupId)
        val newGroupContactId = contactsDao.findContactIdByGroupId(normalizedGroupId)
        return newGroupContactId?.let { chatsDao.findGroupChatIdByContactId(it) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inbound chat resolution
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.getChatIdForReceivedPackage`. */
    suspend fun getChatIdForReceivedPackage(participantId: String, groupId: String?): String {
        val normalizedParticipantId = normalizeIdOrNull(participantId) ?: return ""
        val normalizedGroupId = normalizeIdOrNull(groupId)
        val cacheKey = buildReceivedChatCacheKey(
            groupId = normalizedGroupId,
            participantId = normalizedParticipantId,
        )

        caches.getChatId(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val resolved = resolveChatIdForReceivedPackage(
                participantId = normalizedParticipantId,
                groupId = normalizedGroupId,
            ) ?: ""

            if (resolved.isNotBlank()) {
                caches.putChatId(cacheKey, resolved)
            }

            resolved
        }
    }

    /** See `AppRepository.getChatForReceivedPackage`. */
    suspend fun getChatForReceivedPackage(participantId: String, groupId: String?): ChatEntity? {
        val chatId = getChatIdForReceivedPackage(participantId, groupId)
        if (chatId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            chatsDao.getChatByChatId(chatId)
        }
    }

    private fun buildReceivedChatCacheKey(groupId: String?, participantId: String): String {
        // Group packages map to a single chat regardless of who sent the package.
        return groupId?.let { "group:$it" } ?: "private:$participantId"
    }

    private suspend fun resolveChatIdForReceivedPackage(
        participantId: String,
        groupId: String?,
    ): String? {
        if (groupId != null) {
            return contactsDao.findResolvedGroupChatIdByGroupId(groupId)
                ?: contactsDao.findContactIdByGroupId(groupId)
                    ?.let { chatsDao.findGroupChatIdByContactId(it) }
        }

        val contactId = contactsDao.findContactIdByUserId(participantId)
            ?: contactsDao.findContactIdByDeviceId(participantId)
            ?: return null

        return chatsDao.findPrivateChatIdByContactId(contactId)
    }

    // ─────────────────────────────────────────────────────────────────────
    // State transitions
    // ─────────────────────────────────────────────────────────────────────

    suspend fun updateMessageReceived(messageId: Long) {
        messagesDao.updateMessageState(messageId, MessageState.RECEIVED)
    }

    /** See `AppRepository.updateChatRead`. */
    suspend fun updateChatRead(chatId: String, untilEpochMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            messagesDao.markMessagesAsSeenUntil(chatId, untilEpochMs)
        }

    /** See `AppRepository.clearChatMessages`. */
    suspend fun clearChatMessages(chatId: String) = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext
        messagesDao.clearChatMessages(normalizedChatId)
    }

    /** See `AppRepository.clearChatMessagesInRange`. */
    suspend fun clearChatMessagesInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
    ) = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext
        val rangeStart = minOf(startTimestamp, endTimestamp)
        val rangeEnd = maxOf(startTimestamp, endTimestamp)
        messagesDao.clearChatInRange(normalizedChatId, rangeStart, rangeEnd)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Archival
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.archiveMessage`. */
    suspend fun archiveMessage(messageId: Int): Boolean = withContext(Dispatchers.IO) {
        messagesDao.archiveMessage(messageId) > 0
    }

    /** See `AppRepository.archiveMessages`. */
    suspend fun archiveMessages(messageIds: Collection<Int>): Int = withContext(Dispatchers.IO) {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return@withContext 0
        messagesDao.archiveMessages(ids)
    }

    /** See `AppRepository.archiveMessagesInRange`. */
    suspend fun archiveMessagesInRange(startTimestamp: Long, endTimestamp: Long): Boolean =
        withContext(Dispatchers.IO) {
            messagesDao.archiveAllMessages(startTimestamp, endTimestamp) > 0
        }

    // ─────────────────────────────────────────────────────────────────────
    // Unseen counters
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.observeUnseenCountForChat`. */
    fun observeUnseenCountForChat(chatId: String): Flow<Int> =
        normalizeIdOrNull(chatId)
            ?.let { messagesDao.observeUnseenCountForChat(it).flowOn(Dispatchers.IO) }
            ?: flowOf(0)

    /** See `AppRepository.observeUnseenCountForTarget`. */
    fun observeUnseenCountForTarget(chatId: String, targetId: String): Flow<Int> {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return flowOf(0)
        val normalizedTargetId = normalizeIdOrNull(targetId) ?: return flowOf(0)
        return messagesDao.observeUnseenCountForTarget(normalizedChatId, normalizedTargetId)
            .flowOn(Dispatchers.IO)
    }

    /** See `AppRepository.observeUnseenCountsForTargets`. */
    fun observeUnseenCountsForTargets(
        chatIds: List<String>,
        targetIds: List<String>,
    ): Flow<Map<String, Map<String, Int>>> {
        val normalizedChatIds = chatIds.mapNotNull { normalizeIdOrNull(it) }.distinct()
        val normalizedTargetIds = targetIds.mapNotNull { normalizeIdOrNull(it) }.distinct()
        if (normalizedChatIds.isEmpty() || normalizedTargetIds.isEmpty()) {
            return flowOf(emptyMap())
        }
        return messagesDao.observeUnseenCountsForTargets(normalizedChatIds, normalizedTargetIds)
            .map { rows ->
                if (rows.isEmpty()) return@map emptyMap()
                val out = HashMap<String, MutableMap<String, Int>>()
                for (row in rows) {
                    out.getOrPut(row.chatId) { HashMap() }[row.targetId] = row.unseenCount
                }
                out
            }
            .flowOn(Dispatchers.IO)
    }

    /** See `AppRepository.observeUnseenCountsForChats`. */
    fun observeUnseenCountsForChats(chatIds: List<String>): Flow<Map<String, Int>> {
        val normalizedChatIds = chatIds.mapNotNull { normalizeIdOrNull(it) }.distinct()
        if (normalizedChatIds.isEmpty()) return flowOf(emptyMap())
        return messagesDao.observeUnseenCountsForChats(normalizedChatIds)
            .map { rows ->
                if (rows.isEmpty()) emptyMap()
                else rows.associate { it.chatId to it.unseenCount }
            }
            .flowOn(Dispatchers.IO)
    }
}

