package com.commcrete.stardust.room.new_db

import android.content.Context
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatParticipantEntity
import com.commcrete.stardust.room.new_db.chat.ChatSummary
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.ContactUserIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactGroupIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
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

    // ─────────────────────────────────────────────────────────────────────
    // Contacts + Chats
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inserts all contacts and creates a chat for each one.
     * See [insertContactWithChat] for per-contact logic.
     */
    suspend fun insertContactsWithChats(contacts: List<FullContactData>) =
        withContext(Dispatchers.IO) {
            val inserted = contactsDao.addContactsAndGetIds(contacts)
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
            val contactId = contactsDao.addContactAndGetId(contact)
            
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

    // ── Private helpers ──────────────────────────────────────────────────

    private suspend fun createPrivateChat(contact: ContactEntity, contactId: Int) {
        chatsDao.insertChatWithParticipants(
            ChatEntity(name = contact.name, image = contact.image, type = ChatType.PRIVATE),
            listOf(contactId)
        )
    }

    /** Creates a GROUP chat. [groupContactId] is the GROUP contact itself — stored as a
     *  participant so the chat can later be looked up via [ChatDao.findGroupChatIdByContactId]. */
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

    private suspend fun addToExistingGroupChats(contactId: Int, groupChatIds: List<Int>) {
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
        chatId: Int,
        participantId: String? = null,
        limit: Int = PAGE_SIZE,
    ): Flow<List<MessageEntity>> {
        val id = chatId.toString()
        return if (participantId != null)
            messagesDao.getLatestMessagesByParticipant(id, participantId.trim().lowercase(), limit)
        else
            messagesDao.getLatestMessages(id, limit)
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
        chatId: Int,
        beforeEpochMs: Long,
        participantId: String? = null,
        limit: Int = PAGE_SIZE,
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val id = chatId.toString()
        if (participantId != null)
            messagesDao.loadOlderMessagesByParticipant(id, participantId.trim().lowercase(), beforeEpochMs, limit)
        else
            messagesDao.loadOlderMessages(id, beforeEpochMs, limit)
    }

    /**
     * Resolves chat context then inserts [message]:
     * 1. If [message.senderID] is unknown in DB — auto-inserts a USER contact via [insertContactWithChat].
     * 2. If [groupId] is not null — finds (or creates) the GROUP chat for that group ID.
     * 3. If [groupId] is null — finds the sender's private chat.
     */
    suspend fun saveMessage(message: MessageEntity, groupId: String? = null) =
        saveMutex.withLock {
            if(!canMessageBeSaved(message)) { return }

            withContext(Dispatchers.IO) {
                val senderId = message.senderID.trim().lowercase()
                val contactId = ensureSenderExists(senderId) ?: return@withContext
                val chatId = if (groupId != null) resolveGroupChatId(groupId) else resolvePrivateChatId(contactId)
                messagesDao.addMessage(message.copy(chatId = chatId?.toString()))
            }
        }

    private fun canMessageBeSaved(message: MessageEntity): Boolean {
        // Only save PTT messages if the user has enabled the setting to save them.
        return !((message.type == MessageType.PTT_AI || message.type == MessageType.PTT_CODEC) && !DataManager.getSavePTTFilesRequired(context))
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
            insertContactWithChat(
                FullContactData(
                    contact = ContactEntity(name = senderId, type = ContactType.USER),
                    userId = ContactUserIdEntity(userId = normalizedSenderId, contactId = 0),
                    groupId = null,
                    devices = emptyList(),
                )
            )
        }
        return contactsDao.findContactIdByUserId(normalizedSenderId)
            ?: contactsDao.findContactIdByDeviceId(normalizedSenderId)
    }

    /** Finds the private chat ID for [contactId]. */
    private suspend fun resolvePrivateChatId(contactId: Int): Int? =
        chatsDao.findPrivateChatIdByContactId(contactId)

    /**
     * Finds or creates the GROUP chat whose GROUP contact has group_id == [groupId].
     * Returns the chat ID, or null if resolution fails.
     */
    private suspend fun resolveGroupChatId(groupId: String): Int? {
        val normalizedGroupId = normalizeId(groupId)

        // Fast path for hot message insert loop.
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

    /** Finds the group chat for an existing GROUP contact, creating one if it doesn't exist yet. */
    private suspend fun findOrCreateGroupChat(groupContactId: Int, groupId: String): Int? {
        return chatsDao.findGroupChatIdByContactId(groupContactId)
            ?: run {
                val groupContact = contactsDao.getContactById(groupContactId)
                    ?: ContactEntity(name = groupId, type = ContactType.GROUP)
                createGroupChat(groupContact, groupContactId, contactsDao.getAllMemberContactIds())
                chatsDao.findGroupChatIdByContactId(groupContactId)
            }
    }

    /** Creates a new GROUP contact + its group chat when neither exists yet. Returns the new chat ID. */
    private suspend fun createGroupContactAndChat(normalizedGroupId: String, groupId: String): Int? {
        insertContactWithChat(
            FullContactData(
                contact = ContactEntity(name = groupId, type = ContactType.GROUP),
                userId = null,
                groupId = ContactGroupIdEntity(groupId = normalizedGroupId, contactId = 0),
                devices = emptyList(),
            )
        )
        addGroupIdToCache(normalizedGroupId)
        val newGroupContactId = contactsDao.findContactIdByGroupId(normalizedGroupId)
        return newGroupContactId?.let { chatsDao.findGroupChatIdByContactId(it) }
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
            // Map the userId to this contactId when present.
            contact.userId?.let { appId ->
                updates[normalizeId(appId.userId)] = contactId
            }
            // Map all deviceIds to this contactId
            contact.devices.forEach { device ->
                device.device?.let { deviceEntity ->
                    updates[normalizeId(deviceEntity.deviceId)] = contactId
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

    suspend fun updateMessageReceived(messageId: String) {
        messagesDao.updateMessageState(messageId, MessageState.RECEIVED)
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
//    suspend fun migrateFromLegacyDatabases(context: Context) = withContext(Dispatchers.IO) {
//        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
//        if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) return@withContext
//
//        try {
//            // ── 1. Chats ──────────────────────────────────────────────────
//            val oldChatsDb = ChatsDatabase.getDatabase(context)
//            val chats: List<ChatItem> = oldChatsDb.chatsDao().readChats()
//            if (chats.isNotEmpty()) {
//                chatsDao.addChats(chats.map { it.toAppChatEntity() })
//                Timber.d("Migration: copied ${chats.size} chat(s)")
//            }
//            oldChatsDb.close()
//
//            // ── 2. Contacts ───────────────────────────────────────────────
//            val oldContactsDb = ContactsDatabase.getDatabase(context)
//            val contacts: List<ChatContact> = oldContactsDb.contactsDao().readAllContacts()
//            if (contacts.isNotEmpty()) {
//                contactsDao.addA(contacts)
//                Timber.d("Migration: copied ${contacts.size} contact(s)")
//            }
//            oldContactsDb.close()
//
//            // ── 3. Messages ───────────────────────────────────────────────
//            val oldMessagesDb = MessagesDatabase.getDatabase(context)
//            val messages: List<MessageItem> = oldMessagesDb.messagesDao().getAllMessages()
//            if (messages.isNotEmpty()) {
//                messagesDao.addMessages(messages.map { it.toAppMessageEntity() })
//                Timber.d("Migration: copied ${messages.size} message(s)")
//            }
//            oldMessagesDb.close()
//
//            // ── 4. Delete legacy database files ───────────────────────────
//            context.deleteDatabase("chats_database")
//            context.deleteDatabase("contacts_database")
//            context.deleteDatabase("messages_database")
//            Timber.d("Migration: legacy databases deleted")
//
//            // ── 5. Mark done ──────────────────────────────────────────────
//            prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
//            Timber.d("Migration: completed successfully")
//
//        } catch (e: Exception) {
//            Timber.e(e, "Migration: failed — legacy data retained, will retry on next launch")
//            // Do NOT set the flag — the migration will be attempted again next time
//        }
//    }




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
