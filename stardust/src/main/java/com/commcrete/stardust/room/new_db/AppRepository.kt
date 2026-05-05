package com.commcrete.stardust.room.new_db

import android.content.Context
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatParticipantEntity
import com.commcrete.stardust.room.new_db.chat.ChatSummary
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.contact.DeviceEntity
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageType
import com.commcrete.stardust.room.legacy_db.ChatsDatabase
import com.commcrete.stardust.room.legacy_db.ContactsDatabase
import com.commcrete.stardust.room.legacy_db.MessagesDatabase
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.legacy_db.messages.MessageItem
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipants
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipantsAsShortParticipantInfo
import com.commcrete.stardust.room.new_db.chat.ShortParticipantInfo
import com.commcrete.stardust.util.RegisteredUserUtils
import timber.log.Timber

/**
 * Unified repository that combines chats, contacts and messages operations
 * backed by the single [AppDatabase].
 */
class AppRepository(
    private val chatsDao: ChatDao,
    private val contactsDao: ContactsDao,
    private val messagesDao: MessageDao,
) {

    /**
     * Serializes every [saveMessage] call — contact resolution, chat resolution,
     * and the final insert all run under this lock.
     *
     * - Mutex covers the entire pipeline atomically, so two simultaneous calls
     *   cannot interleave their resolution steps or duplicate a contact/chat.
     * - Callers suspend (not block) while waiting, so no thread is wasted.
     * - Messages are inserted in the exact order [saveMessage] was called.
     */
    private val saveMutex = Mutex()
    private val groupIdsCacheMutex = Mutex()
    @Volatile
    private var groupIdsCache: Set<String> = emptySet()
    @Volatile
    private var isGroupIdsCacheInitialized: Boolean = false

    private val contactsCacheMutex = Mutex()
    @Volatile
    private var contactsCache: Map<String, Int> = emptyMap() // Maps normalized userId/deviceId -> contactId
    @Volatile
    private var isContactsCacheInitialized: Boolean = false

    private val chatIdsCacheMutex = Mutex()
    @Volatile
    private var chatIdsCache: Map<String, String> = emptyMap() // cache key -> chatId string

    // ─────────────────────────────────────────────────────────────────────
    // Contacts + Chats
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inserts all contacts and creates a chat for each one.
     * See [insertContactWithChat] for per-contact logic.
     */
    suspend fun insertContactsWithChats(contacts: List<FullContactData>) =
        withContext(Dispatchers.IO) {
            val inserted = contactsDao.addContacts(contacts)
            val existingGroupChatIds = chatsDao.getAllGroupChatIds()
            val allMemberIds = contactsDao.getAllMemberContactIds()

            inserted.forEach { (data, contactId) ->
                // Add to contacts cache
                addContactToCache(data, contactId)
                
                when (data.contact.type) {
                    ContactType.USER, ContactType.DEVICE -> {
                        createPrivateChat(data.contact, contactId)
                        addToExistingGroupChats(contactId, existingGroupChatIds)
                    }
                    ContactType.GROUP -> {
                        createGroupChat(data.contact, contactId, allMemberIds)
                    }
                }
            }
        }

    /**
     * Inserts a single contact and creates a chat for it:
     * - USER / DEVICE → private chat + added to every existing GROUP chat.
     * - GROUP → new group chat populated with all existing non-group members.
     */
    suspend fun insertContactWithChat(contact: FullContactData) =
        withContext(Dispatchers.IO) {
            val contactId = contactsDao.addContact(contact)
            
            // Add to contacts cache
            addContactToCache(contact, contactId)

            when (contact.contact.type) {
                ContactType.USER, ContactType.DEVICE -> {
                    createPrivateChat(contact.contact, contactId)
                    addToExistingGroupChats(contactId, chatsDao.getAllGroupChatIds())
                }
                ContactType.GROUP -> {
                    createGroupChat(contact.contact, contactId, contactsDao.getAllMemberContactIds())
                }
            }
        }

    /**
     * Reactive chat list for the UI. Each [ChatSummary] contains the
     * last message text/sender and the current unseen count, computed live
     * from the messages table. The Flow re-emits on every insert/update/delete
     * in either `new_chats_table` or `new_messages_table`.
     */
    fun getChatSummaries(): Flow<List<ChatSummary>> = chatsDao.getAllChatsSummaries()

    /**
     * Live stream of group IDs from the dedicated group-id table, linked to GROUP chats.
     */
    fun observeGroupIds(): Flow<List<String>> =
        contactsDao.observeResolvedGroupIds().onEach { ids ->
            groupIdsCacheMutex.withLock {
                groupIdsCache = ids.map { normalizeId(it) }.toSet()
                isGroupIdsCacheInitialized = true
            }
        }

    /** Returns all known group IDs from the dedicated group-id source. */
    suspend fun getAllGroupIds(): List<String> = withContext(Dispatchers.IO) {
        val ids = contactsDao.getResolvedGroupIds()
            .map { normalizeId(it) }
            .distinct()

        groupIdsCacheMutex.withLock {
            groupIdsCache = ids.toSet()
            isGroupIdsCacheInitialized = true
        }

        ids
    }

    /** True when [id] belongs to a known GROUP contact. */
    suspend fun isGroupId(id: String?): Boolean = hasAnyGroupId(listOf(id))

    /** True when any value in [ids] belongs to a known GROUP contact. */
    suspend fun hasAnyGroupId(ids: Collection<String?>): Boolean = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext false
        val normalizedIds = ids.mapNotNull { normalizeIdOrNull(it) }
        if (normalizedIds.isEmpty()) return@withContext false
        val cache = getCachedGroupIds()
        normalizedIds.any { it in cache }
    }

    
    /**
     * Returns the display name of the contact that owns [id], searching across
     * user IDs, group IDs, and device IDs. Returns null if no contact owns [id].
     */
    suspend fun getContactNameById(id: String): String? = withContext(Dispatchers.IO) {
        contactsDao.findContactNameById(normalizeId(id))
    }

    suspend fun getContactNameByIdOrId(id: String): String = withContext(Dispatchers.IO) {
        val normalizeId = normalizeId(id)
        contactsDao.findContactNameById(normalizeId) ?: normalizeId
    }

    /**
     * Returns the display name of the GROUP contact that owns [groupId].
     * Returns null if no group contact owns that ID.
     */
    suspend fun getGroupNameById(groupId: String): String? = withContext(Dispatchers.IO) {
        contactsDao.findGroupNameById(normalizeId(groupId))
    }

    /**
     * Returns the GROUP contact that owns [groupId], or null when not found.
     */
    suspend fun getGroupContactById(groupId: String): ContactEntity? = withContext(Dispatchers.IO) {
        val contactId = contactsDao.findContactIdByGroupId(normalizeId(groupId)) ?: return@withContext null
        val contact = contactsDao.getContactById(contactId) ?: return@withContext null
        if (contact.type == ContactType.GROUP) contact else null
    }


    suspend fun getUserAndGroupContactsExceptSelf(): List<FullContactData> = withContext(Dispatchers.IO) {
        val selfId = RegisteredUserUtils.mRegisterUser.value?.appId
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val groupRows = contactsDao.getAllGroupContactRows()

        val appRows = if (selfId == null) { contactsDao.getAllAppContactRows()
        } else {
            contactsDao.getAllAppContactRowsExceptUser(selfId)
        }

        mapGroupContactRowsToFullContactData(groupRows) + mapAppContactRowsToFullContactData(appRows)
    }

    /**
     * Returns every USER and DEVICE contact except the registered user themselves.
     * Mirrors [getUserAndGroupContactsExceptSelf] but excludes GROUP contacts and
     * includes standalone DEVICE contacts (plus devices attached to USER contacts).
     */
    suspend fun getUserAndDeviceContactsExceptSelf(): List<FullContactData> = withContext(Dispatchers.IO) {
        val selfId = RegisteredUserUtils.mRegisterUser.value?.appId
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val rows = if (selfId == null) {
            contactsDao.getAllUserAndDeviceContactRows()
        } else {
            contactsDao.getAllUserAndDeviceContactRowsExceptUser(selfId)
        }

        mapAppContactRowsToFullContactData(rows)
            .filter { it is FullContactData.User || it is FullContactData.Device }
    }

    private fun mapAppContactRowsToFullContactData(rows: List<ContactsDao.AppContactRow>): List<FullContactData> {
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

    private fun mapGroupContactRowsToFullContactData(rows: List<ContactsDao.GroupContactRow>): List<FullContactData> =
        rows.mapNotNull { row ->
            val groupId = normalizeIdOrNull(row.groupId) ?: return@mapNotNull null
            FullContactData.Group(contact = row.contact, groupId = groupId)
        }

    // ── Private helpers ──────────────────────────────────────────────────

    private suspend fun createPrivateChat(contact: ContactEntity, contactId: Int) {
        chatsDao.insertChatWithParticipants(
            ChatEntity(name = contact.name, image = contact.image, type = ChatType.PRIVATE),
            listOf(contactId)
        )
    }

    private suspend fun createGroupChat(
        contact: ContactEntity,
        groupContactId: Int,
        memberIds: List<Int>,
    ) {
        chatsDao.insertChatWithParticipants(
            ChatEntity(name = contact.name, image = contact.image, type = ChatType.GROUP),
            (memberIds + groupContactId).distinct()
        )
    }

    private suspend fun addToExistingGroupChats(contactId: Int, groupChatIds: List<String>) {
        if (groupChatIds.isEmpty()) return
        chatsDao.addParticipants(
            groupChatIds.map { chatId -> ChatParticipantEntity(chatId = chatId, contactId = contactId) }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Messages
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Live feed of the [limit] most-recent messages in [chatId], ordered
     * chronologically (oldest-first). Re-emits automatically on every insert,
     * update, or delete — suitable for both group and private chat views.
     *
     * For group chats pass [participantId] = null (default).
     * For private chats pass a [participantId] to restrict to messages where
     * that contact is sender **or** receiver.
     *
     * Combine with [loadOlderMessages] to implement infinite upward pagination:
     * ```
     * // initial load
     * val feed = repo.getMessages(chatId)
     * // user scrolls to top
     * val olderPage = repo.loadOlderMessages(chatId, oldestEpochMsVisible)
     * // prepend olderPage to your UI list
     * ```
     */
    fun getMessages(
        chatId: String,
        participantId: String? = null,
        limit: Int = PAGE_SIZE,
    ): Flow<List<MessageEntity>> {
        return if (participantId != null)
            messagesDao.getLatestMessagesByParticipant(chatId, participantId.trim().lowercase(), limit)
        else
            messagesDao.getLatestMessages(chatId, limit)
    }

    /**
     * Fetches the [limit] messages that come just **before** [beforeEpochMs]
     * in [chatId], ordered chronologically. Call this when the user scrolls
     * to the top of the chat view to prepend the next page.
     *
     * Pass the same [participantId] you used in [getMessages] to keep the
     * filter consistent.
     */
    suspend fun loadOlderMessages(
        chatId: String,
        beforeEpochMs: Long,
        participantId: String? = null,
        limit: Int = PAGE_SIZE,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        if (participantId != null)
            messagesDao.loadOlderMessagesByParticipant(chatId, participantId.trim().lowercase(), beforeEpochMs, limit)
        else
            messagesDao.loadOlderMessages(chatId, beforeEpochMs, limit)
    }

    /**
     * Loads a target-scoped page (sender OR receiver == [targetId]) inside one chat.
     *
     * This works with the existing timestamp-based DAO by walking pages from newest
     * backwards until the requested [page] is reached. Returned list is chronological.
     */
    suspend fun loadPageForTarget(
        targetId: String,
        chatId: String,
        page: Int,
        pageSize: Int = PAGE_SIZE,
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

    /**
     * Loads a chat-scoped page (all messages in [chatId] where one of sender/receiver
     * is the registered user) for the requested [page] number.
     *
     * This works with the existing timestamp-based DAO by walking pages from newest
     * backwards until the requested [page] is reached. Messages are filtered to only
     * include those involving the currently registered user. Returned list is chronological.
     */
    suspend fun loadPageForChat(
        chatId: String,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext emptyList()
        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceAtLeast(1)

        val registeredUserIds = registeredUserIds()
        if (registeredUserIds.isEmpty()) return@withContext emptyList()

        messagesDao.loadPageForChatScopedToUsers(
            chatId = normalizedChatId,
            userIds = registeredUserIds,
            limit = safePageSize,
            offset = safePage * safePageSize,
        )
    }

    /** Lower-cased identifiers (appId + deviceId) of the registered user, or empty when absent. */
    private fun registeredUserIds(): List<String> {
        val user = RegisteredUserUtils.mRegisterUser.value ?: return emptyList()
        return listOfNotNull(
            normalizeIdOrNull(user.appId),
            normalizeIdOrNull(user.deviceId),
        ).distinct()
    }

    /**
     * Live top-page stream for a single target in one chat.
     */
    fun observeLatestForTarget(
        targetId: String,
        chatId: String,
        limit: Int = PAGE_SIZE,
    ): Flow<List<MessageEntity>> {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return flowOf(emptyList())
        val normalizedTargetId = normalizeIdOrNull(targetId) ?: return flowOf(emptyList())
        val safeLimit = limit.coerceAtLeast(1)
        return messagesDao.getLatestMessagesByParticipant(normalizedChatId, normalizedTargetId, safeLimit)
    }

    /**
     * Resolves chat context then inserts [message]:
     * 1. If sender id is unknown in DB — auto-inserts a USER contact via [insertContactWithChat].
     * 2. If [groupId] is not null — finds (or creates) the GROUP chat for that group ID.
     * 3. If [groupId] is null — finds the sender's private chat.
     *
     * @return inserted Room row ID, or null when message is filtered/not resolvable.
     */
    suspend fun saveMessage(message: MessageEntity, groupId: String? = null): Long? =
        saveMutex.withLock {
            if (!canMessageBeSaved(message)) return@withLock null

            withContext(Dispatchers.IO) {
                val senderId = if(RegisteredUserUtils.isRegisteredUser(message.senderID)) message.receiverID else message.senderID
                val contactId = ensureSenderExists(senderId.trim().lowercase()) ?: return@withContext null
                val chatId = if (groupId != null) resolveGroupChatId(groupId) else resolvePrivateChatId(contactId)
                val chatIdString = chatId ?: return@withContext null
                messagesDao.addMessage(message.copy(chatId = chatIdString))
            }
        }

    private fun canMessageBeSaved(message: MessageEntity): Boolean {
        // Only save PTT messages if the user has enabled the setting to save them.
        return !(message.type == MessageType.PTT && !DataManager.getSavePTTFilesRequired(context))
    }

    /** Ensures sender exists in DB, auto-creating a USER contact if not. Returns the contact ID. */
    private suspend fun ensureSenderExists(senderId: String): Int? {
        val normalizedSenderId = normalizeId(senderId)

        // Fast path for hot message insert loop — check cache first
        getContactIdFromCache(normalizedSenderId)?.let { return it }

        // Single-shot DB lookup across user_ids + device_ids
        contactsDao.findContactIdByUserOrDeviceId(normalizedSenderId)?.let { return it }

        // Not found — auto-create USER contact (this also populates the cache).
        FullContactData.createUserContact(
            name = senderId,
            image = null,
            userId = normalizedSenderId,
        )?.let { insertContactWithChat(it) }

        return getContactIdFromCache(normalizedSenderId)
            ?: contactsDao.findContactIdByUserOrDeviceId(normalizedSenderId)
    }

    /** Finds the private chat ID for [contactId]. */
    private suspend fun resolvePrivateChatId(contactId: Int): String? =
        chatsDao.findPrivateChatIdByContactId(contactId)

    private suspend fun resolveGroupChatId(groupId: String): String? {
        val normalizedGroupId = normalizeId(groupId)

        if (isGroupIdCached(normalizedGroupId)) {
            return contactsDao.findResolvedGroupChatIdByGroupId(normalizedGroupId)
                ?: createGroupContactAndChat(normalizedGroupId, groupId)
        }

        val groupContactId = contactsDao.findContactIdByGroupId(normalizedGroupId)

        return if (groupContactId != null) {
            val chatId = findOrCreateGroupChat(groupContactId, groupId)
            if (chatId != null) addGroupIdToCache(normalizedGroupId)
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
                createGroupChat(groupContact, groupContactId, contactsDao.getAllMemberContactIds())
                chatsDao.findGroupChatIdByContactId(groupContactId)
            }
    }

    private suspend fun createGroupContactAndChat(normalizedGroupId: String, groupId: String): String? {
        FullContactData.createGroupContact(
            name = groupId,
            image = null,
            groupId = normalizedGroupId,
        )?.let { insertContactWithChat(it) }
        addGroupIdToCache(normalizedGroupId)
        val newGroupContactId = contactsDao.findContactIdByGroupId(normalizedGroupId)
        return newGroupContactId?.let { chatsDao.findGroupChatIdByContactId(it) }
    }

    /**
     * Resolves chatId for an incoming package using participantId + optional groupId.
     * Uses cache for hot packet flow and DB lookup on cache miss.
     */
    suspend fun getChatIdForReceivedPackage(participantId: String, groupId: String?): String {
        val normalizedParticipantId = normalizeIdOrNull(participantId) ?: return ""
        val normalizedGroupId = normalizeIdOrNull(groupId)
        val cacheKey = buildReceivedChatCacheKey(
            groupId = normalizedGroupId,
            participantId = normalizedParticipantId,
        )

        chatIdsCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            val resolved = resolveChatIdForReceivedPackage(
                participantId = normalizedParticipantId,
                groupId = normalizedGroupId,
            ) ?: ""

            if (resolved.isNotBlank()) {
                chatIdsCacheMutex.withLock {
                    chatIdsCache = chatIdsCache + (cacheKey to resolved)
                }
            }

            resolved
        }
    }

    /**
     * Resolves chat for an incoming package using participantId + optional groupId.
     * Applies the same cache + DB lookup flow as [getChatIdForReceivedPackage].
     */
    suspend fun getChatForReceivedPackage(participantId: String, groupId: String?): ChatEntity? {
        val chatId = getChatIdForReceivedPackage(participantId, groupId)
        if (chatId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            chatsDao.getChatById(chatId)
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

    private fun normalizeId(value: String): String = value.trim().lowercase()

    private fun normalizeIdOrNull(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private suspend fun isGroupIdCached(groupId: String): Boolean {
        val cache = getCachedGroupIds()
        return cache.contains(groupId)
    }

    private suspend fun getCachedGroupIds(): Set<String> {
        if (isGroupIdsCacheInitialized) return groupIdsCache
        return groupIdsCacheMutex.withLock {
            if (!isGroupIdsCacheInitialized) {
                groupIdsCache = contactsDao.getResolvedGroupIds().map { normalizeId(it) }.toSet()
                isGroupIdsCacheInitialized = true
            }
            groupIdsCache
        }
    }

    private suspend fun addGroupIdToCache(groupId: String) {
        groupIdsCacheMutex.withLock {
            val normalized = normalizeId(groupId)
            groupIdsCache = groupIdsCache + normalized
            isGroupIdsCacheInitialized = true
        }
    }

    private suspend fun getContactIdFromCache(normalizedId: String): Int? {
        val cache = getCachedContacts()
        return cache[normalizedId]
    }

    private suspend fun addContactToCache(contact: FullContactData, contactId: Int) {
        contactsCacheMutex.withLock {
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
            contactsCache = contactsCache + updates
            isContactsCacheInitialized = true
        }
    }

    private suspend fun getCachedContacts(): Map<String, Int> {
        if (isContactsCacheInitialized) return contactsCache
        return contactsCacheMutex.withLock {
            if (!isContactsCacheInitialized) {
                contactsCache = buildCachedContacts()
                isContactsCacheInitialized = true
            }
            contactsCache
        }
    }

    /**
     * Eagerly preloads every (normalizedId -> contactId) mapping in a single
     * UNION ALL query. Replaces the previous lazy/empty stub which forced every
     * `ensureSenderExists` call to hit the DB on first run.
     */
    private suspend fun buildCachedContacts(): Map<String, Int> {
        val rows = contactsDao.getAllIdToContactRows()
        if (rows.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>(rows.size)
        for (row in rows) {
            val key = normalizeIdOrNull(row.normalizedId) ?: continue
            out[key] = row.contactId
        }
        return out
    }


    suspend fun updateMessageReceived(messageId: Long) {
        messagesDao.updateMessageState(messageId, MessageState.RECEIVED)
    }

    // ── Unseen counters (always reactive) ────────────────────────────────

    /**
     * Live unseen-message count for [chatId].
     *
     * Backed by the `(chat_id, state, epoch_time_ms)` composite index — every
     * emission is an index-only COUNT, no row scan. Re-emits whenever a message
     * in that chat changes state.
     *
     * Cost: O(log N) per emission. Cheap enough to subscribe per chat row,
     * but if you already render a chat list, prefer [getChatSummaries] —
     * `ChatSummary.unseenCount` is the same value computed in the same view
     * across every chat in one query.
     */
    fun observeUnseenCountForChat(chatId: String): Flow<Int> =
        normalizeIdOrNull(chatId)
            ?.let { messagesDao.observeUnseenCountForChat(it).flowOn(Dispatchers.IO) }
            ?: flowOf(0)

    /**
     * Live unseen-message count for messages in [chatId] where [targetId] is
     * sender OR receiver. Useful for per-participant badges inside a shared chat.
     *
     * Cost per emission: a single indexed COUNT (uses the `chat_id` index plus
     * the `sender_id` / `receiver_id` indexes). Still cheap, but **N independent
     * subscriptions cost N COUNT queries on every message change** — see
     * [observeUnseenCountsForTargets] for the bulk variant.
     */
    fun observeUnseenCountForTarget(chatId: String, targetId: String): Flow<Int> {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return flowOf(0)
        val normalizedTargetId = normalizeIdOrNull(targetId) ?: return flowOf(0)
        return messagesDao.observeUnseenCountForTarget(normalizedChatId, normalizedTargetId)
            .flowOn(Dispatchers.IO)
    }

    /**
     * Live unseen-message counts for every (chatId, targetId) combination
     * where `chatId IN [chatIds]` and `targetId IN [targetIds]`.
     *
     * **Use this when a fragment needs per-target badges for many targets.**
     * One COUNT query per messages-table change covers all pairs, instead of
     * N separate Flows each running their own COUNT.
     *
     * Emission shape: `Map<chatId, Map<targetId, count>>`. Pairs with zero
     * unseen messages are omitted — read with `.getOrDefault(0)`.
     *
     * Cost per emission: 1 grouped UNION-COUNT query, regardless of list size
     * (until very large IN-lists hit SQLite's variable limit, ~999).
     */
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

    /**
     * Live unseen-message counts for every chat in [chatIds].
     * One COUNT per emission for the whole list. Pairs with zero unseen are
     * omitted from the map.
     */
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

    /** Archives a single message by ID. Returns true when a row was updated. */
    suspend fun archiveMessage(messageId: Int): Boolean = withContext(Dispatchers.IO) {
        messagesDao.archiveMessage(messageId) > 0
    }

    /** Archives multiple messages by ID. Returns number of rows updated. */
    suspend fun archiveMessages(messageIds: Collection<Int>): Int = withContext(Dispatchers.IO) {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) return@withContext 0
        messagesDao.archiveMessages(ids)
    }

    suspend fun archiveMessagesInRange(startTimestamp: Long, endTimestamp: Long): Boolean = withContext(Dispatchers.IO) {
        messagesDao.archiveAllMessages(startTimestamp, endTimestamp) > 0
    }

    /**
     * Marks all RECEIVED / RECEIVING messages in [chatId] that arrived on or before
     * [untilEpochMs] as SEEN.  Defaults to the current system time so calling
     * `updateChatRead(chatId)` marks every already-received message as read.
     *
     * The ChatSummary live query will automatically re-emit with unseenCount = 0
     * once the update is committed.
     */
    suspend fun updateChatRead(chatId: String, untilEpochMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            messagesDao.markMessagesAsSeenUntil(chatId, untilEpochMs)
        }

    /** Returns all chat IDs currently stored in the database. */
    suspend fun getChatIds(): List<String> = withContext(Dispatchers.IO) {
        chatsDao.getAllChatIds()
    }

    /** Fetches just the ChatEntity by ID (lightweight, no participants). Returns null if not found. */
    suspend fun getShortChatDataByChatId(chatId: String): ChatEntity? = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext null
        chatsDao.getChatById(normalizedChatId)
    }

    /** Fetches a single chat by its ID with participants. Returns null if not found. */
    suspend fun getChatWithParticipantsByChatId(chatId: String): ChatWithParticipants? = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext null
        chatsDao.getChatWithParticipants(normalizedChatId)
    }

    /**
     * Builds [ShortParticipantInfo] entries directly from raw DAO rows, avoiding
     * any per-contact secondary queries. USER rows produce both userId + deviceId
     * entries; DEVICE/GROUP produce a single entry.
     */
    private fun mapParticipantIdRows(
        rows: List<ContactsDao.ChatParticipantIdRow>,
    ): Map<String, List<ShortParticipantInfo>> {
        if (rows.isEmpty()) return emptyMap()
        val result = HashMap<String, MutableList<ShortParticipantInfo>>()
        for (row in rows) {
            val type = when (row.kind) {
                "USER" -> ContactType.USER
                "DEVICE" -> ContactType.DEVICE
                "GROUP" -> ContactType.GROUP
                else -> continue
            }
            val list = result.getOrPut(row.chatId) { mutableListOf() }
            // de-dup at insert time so we don't have to scan again
            if (list.none { it.id == row.participantId && it.type == type }) {
                list += ShortParticipantInfo(id = row.participantId, type = type)
            }
        }
        return result
    }

    /**
     * Retrieves a chat with its participants as lightweight [ShortParticipantInfo] (id + type only).
     *
     * This is more efficient than fetching full FullContactData when you only need to know
     * the participant's communication ID and type.
     *
     * @param chatId the chat ID to fetch
     * @return ChatWithParticipantsAsShortParticipantInfo with participants as ParticipantInfo, or null if chat not found
     */
    suspend fun getChatWithParticipantsShortParticipantInfo(chatId: String): ChatWithParticipantsAsShortParticipantInfo? =
        withContext(Dispatchers.IO) {
            val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext null
            val chat = chatsDao.getChatById(normalizedChatId) ?: return@withContext null
            val participantRows = contactsDao.getChatParticipantIdRows(normalizedChatId)
            val participants = mapParticipantIdRows(participantRows)[normalizedChatId].orEmpty()
            ChatWithParticipantsAsShortParticipantInfo(
                chat = chat,
                participants = participants,
            )
        }

    /**
     * Observes all chats with their participants as lightweight [ShortParticipantInfo] (id + type only).
     *
     * Combines the existing chats-with-participants Flow with a single bulk
     * participant-id projection. Re-emits whenever either source changes, but
     * never issues per-chat or per-participant secondary queries.
     */
    fun observeAllChatsWithShortParticipantInfo(): Flow<List<ChatWithParticipantsAsShortParticipantInfo>> =
        combine(
            chatsDao.getAllChatsWithParticipants(),
            contactsDao.observeAllChatParticipantIdRows(),
        ) { chatsWithContacts, idRows ->
            val byChatId = mapParticipantIdRows(idRows)
            chatsWithContacts.map { chatWithContacts ->
                ChatWithParticipantsAsShortParticipantInfo(
                    chat = chatWithContacts.chat,
                    participants = byChatId[chatWithContacts.chat.id].orEmpty(),
                )
            }
        }.flowOn(Dispatchers.IO)

    /** Deletes a chat by ID. Related messages/participants are deleted by FK cascade. */
    suspend fun deleteChat(chatId: String): Boolean = saveMutex.withLock {
        withContext(Dispatchers.IO) {
            val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext false
            val deletedRows = chatsDao.deleteChatById(normalizedChatId)
            if (deletedRows > 0) {
                chatIdsCacheMutex.withLock {
                    chatIdsCache = chatIdsCache.filterValues { it != normalizedChatId }
                }
            }
            deletedRows > 0
        }
    }

    suspend fun findNewChatIdByPreviousChatId(previousChatId: String): String? = withContext(Dispatchers.IO) {
        chatsDao.findNewChatIdByPreviousChatId(previousChatId.trim().lowercase())
    }

    /**
     * Resolves a list of previous chat IDs (groupId / deviceId / userId from the legacy DB)
     * to new chat IDs, preserving input order.
     *
     * Duplicate inputs are resolved with a single DB call each and the result is reused
     * for subsequent positions, so the list may contain the same value multiple times
     * without extra queries.
     *
     * @return positionally aligned list — `null` at index i means no chat was found for
     *         `previousChatIds[i]`.
     */
    suspend fun findNewChatIdsByPreviousChatIds(previousChatIds: List<String>): List<String?> =
        withContext(Dispatchers.IO) {
            if (previousChatIds.isEmpty()) return@withContext emptyList()

            // Normalize once; collect distinct non-empty keys for the bulk query.
            val normalized = previousChatIds.map { it.trim().lowercase() }
            val distinctKeys = normalized.filter { it.isNotEmpty() }.distinct()
            if (distinctKeys.isEmpty()) return@withContext List(previousChatIds.size) { null }

            // Single DB call instead of N round-trips. Rows are ordered DESC by
            // chat last-updated, so the first row per previous_id wins.
            val rows = chatsDao.findNewChatIdRowsByPreviousChatIds(distinctKeys)
            val resolved = HashMap<String, String>(rows.size)
            for (row in rows) {
                resolved.putIfAbsent(row.previousId, row.chatId)
            }

            normalized.map { key -> if (key.isEmpty()) null else resolved[key] }
        }

    suspend fun clearData(): Boolean = saveMutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context

            val newDbCleared = runCatching {
                AppDatabase.getDatabase(appContext).clearAllTables()
                true
            }.getOrDefault(false)

            resetCaches()

            val legacyCleared = clearLegacyDatabases(appContext)

            val migrationFlagUpdated = runCatching {
                appContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit {
                        putBoolean(KEY_MIGRATION_DONE, true)
                    }
                true
            }.getOrDefault(false)

            newDbCleared && legacyCleared && migrationFlagUpdated
        }
    }

    private suspend fun clearLegacyDatabases(appContext: Context): Boolean {
        var success = true

        runCatching {
            ChatsDatabase.getDatabase(appContext).also { db ->
                db.chatsDao().clearData()
                db.close()
            }
        }.onFailure { success = false }

        runCatching {
            ContactsDatabase.getDatabase(appContext).also { db ->
                db.contactsDao().clearData()
                db.close()
            }
        }.onFailure { success = false }

        runCatching {
            MessagesDatabase.getDatabase(appContext).also { db ->
                db.messagesDao().clearData()
                db.close()
            }
        }.onFailure { success = false }

        LEGACY_DATABASE_NAMES.forEach { dbName ->
            val deletedOrMissing = runCatching {
                val path = appContext.getDatabasePath(dbName)
                if (!path.exists()) true else appContext.deleteDatabase(dbName)
            }.getOrDefault(false)

            if (!deletedOrMissing) success = false
        }

        return success
    }

    private suspend fun resetCaches() {
        groupIdsCacheMutex.withLock {
            groupIdsCache = emptySet()
            isGroupIdsCacheInitialized = false
        }
        contactsCacheMutex.withLock {
            contactsCache = emptyMap()
            isContactsCacheInitialized = false
        }
        chatIdsCacheMutex.withLock {
            chatIdsCache = emptyMap()
        }
    }


    // ─────────────────────────────────────────────────────────────────────
    // One-time migration from legacy databases
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Copies every row from the three legacy databases (chats_database,
     * contacts_database, messages_database) into this unified [AppDatabase],
     * then deletes the old database files.
     *
     * The operation is guarded by a SharedPreferences flag so it runs exactly
     * once per installation.  Safe to call multiple times — subsequent calls
     * return immediately.
     */
    suspend fun migrateFromLegacyDatabases(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) return@withContext

        try {

            // ── 2. Contacts ───────────────────────────────────────────────
            val oldContactsDb = ContactsDatabase.getDatabase(context)
            val contacts: List<ChatContact> = oldContactsDb.contactsDao().getAllContact()
            if (contacts.isNotEmpty()) {
                val parsedContacts: List<FullContactData> = contacts.mapNotNull { it.toFullContactData() }
                insertContactsWithChats(parsedContacts)
                Timber.d("Migration: copied ${contacts.size} contact(s)")
            }
            oldContactsDb.close()

            // ── 3. Messages ───────────────────────────────────────────────
            val oldMessagesDb = MessagesDatabase.getDatabase(context)
            val messages: List<MessageItem> = oldMessagesDb.messagesDao().getAllMessages()
            if (messages.isNotEmpty()) {
                messagesDao.addMessages(messages.map { it.toAppMessageEntity() })
                Timber.d("Migration: copied ${messages.size} message(s)")
            }
            oldMessagesDb.close()

            // ── 4. Delete legacy database files ───────────────────────────
            context.deleteDatabase("chats_database")
            context.deleteDatabase("contacts_database")
            context.deleteDatabase("messages_database")
            Timber.d("Migration: legacy databases deleted")

            // ── 5. Mark done ──────────────────────────────────────────────
            prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
            Timber.d("Migration: completed successfully")

        } catch (e: Exception) {
            Timber.e(e, "Migration: failed — legacy data retained, will retry on next launch")
            // Do NOT set the flag — the migration will be attempted again next time
        }
    }




    companion object {
        const val PAGE_SIZE = 30
        private const val PREFS_NAME = "app_db_prefs"
        private const val KEY_MIGRATION_DONE = "migration_done"
        private val LEGACY_DATABASE_NAMES = listOf(
            "chats_database",
            "contacts_database",
            "messages_database",
        )
    }

}

