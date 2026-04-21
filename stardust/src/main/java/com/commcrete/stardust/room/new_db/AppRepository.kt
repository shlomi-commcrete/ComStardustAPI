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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.legacy_db.messages.MessageItem
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipants
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.runBlocking
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
        val selfId = RegisteredUserUtils.mRegisterUser?.appId
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        
        if (selfId == null) {
            // No registered user — return all app + group contacts
            val appRows = contactsDao.getAllAppContactRows()
            val groupRows = contactsDao.getAllGroupContactRows()
            mapAppContactRowsToFullContactData(appRows) + mapGroupContactRowsToFullContactData(groupRows)
        } else {
            // Exclude self from app contacts
            val appRows = contactsDao.getAllAppContactRowsExceptUser(selfId)
            val groupRows = contactsDao.getAllGroupContactRows()
            mapAppContactRowsToFullContactData(appRows) + mapGroupContactRowsToFullContactData(groupRows)
        }
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
                val senderId = message.senderID.trim().lowercase()
                val contactId = ensureSenderExists(senderId) ?: return@withContext null
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
        val cachedContactId = getContactIdFromCache(normalizedSenderId)
        if (cachedContactId != null) {
            return cachedContactId
        }
        
        val existing = contactsDao.findContactIdByUserId(normalizedSenderId)
            ?: contactsDao.findContactIdByDeviceId(normalizedSenderId)
        if (existing == null) {
            FullContactData.createUserContact(
                name = senderId,
                image = null,
                userId = normalizedSenderId,
            )?.let { insertContactWithChat(it) }
        }
        return contactsDao.findContactIdByUserId(normalizedSenderId)
            ?: contactsDao.findContactIdByDeviceId(normalizedSenderId)
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
    fun getChatIdForReceivedPackage(participantId: String, groupId: String?): String {
        val normalizedParticipantId = normalizeIdOrNull(participantId) ?: return ""
        val normalizedGroupId = normalizeIdOrNull(groupId)
        val cacheKey = buildReceivedChatCacheKey(
            groupId = normalizedGroupId,
            participantId = normalizedParticipantId,
        )

        chatIdsCache[cacheKey]?.let { return it }

        return runBlocking(Dispatchers.IO) {
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
    fun getChatForReceivedPackage(participantId: String, groupId: String?): ChatEntity? {
        val chatId = getChatIdForReceivedPackage(participantId, groupId)
        if (chatId.isBlank()) return null
        return runBlocking(Dispatchers.IO) {
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
                // Build a map of all userId/deviceId -> contactId from the database
                contactsCache = buildCachedContacts()
                isContactsCacheInitialized = true
            }
            contactsCache
        }
    }

    private fun buildCachedContacts(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        // TODO: Note: This assumes DAO methods exist to fetch all contact mappings.
        // For now, this lazy-initializes empty and builds up as contacts are added.
        return result
    }


    suspend fun updateMessageReceived(messageId: Long) {
        messagesDao.updateMessageState(messageId, MessageState.RECEIVED)
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
            // Resolve each unique ID once, then map positions back
            val cache = mutableMapOf<String, String?>()
            previousChatIds.map { raw ->
                val key = raw.trim().lowercase()
                if (key.isEmpty()) return@map null
                cache.getOrPut(key) {
                    chatsDao.findNewChatIdByPreviousChatId(key)
                }
            }
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

