package com.commcrete.stardust.room.new_db

import android.content.Context
import androidx.lifecycle.LiveData
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDao
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.contacts.ContactsDao
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDao
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.GroupsUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Unified repository that combines chats, contacts and messages operations
 * backed by the single [AppDatabase].
 */
class AppRepository(
    private val chatsDao: ChatsDao,
    private val contactsDao: ContactsDao,
    private val messagesDao: MessagesDao,
    private val scope: CoroutineScope,
) {

    // ─────────────────────────────────────────────────────────────────────
    // Message queue (fire-and-forget writes via channel)
    // ─────────────────────────────────────────────────────────────────────

    private val messageChannel = Channel<MessageItem>(Channel.BUFFERED)

    init {
        scope.launch(Dispatchers.IO) {
            for (msg in messageChannel) {
                messagesDao.addMessage(msg)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Chats
    // ─────────────────────────────────────────────────────────────────────

    val allChats: LiveData<List<ChatItem>> = chatsDao.getAllChats()

    suspend fun getAllAppChats(): List<ChatItem> = chatsDao.getAllAppChats()

    suspend fun getAllChats(): List<ChatItem> = withContext(Dispatchers.IO) { chatsDao.readChats() }

    fun getAllChatsIds(): List<String> = chatsDao.getAllChatsIds()

    fun readChat(chatId: String): LiveData<ChatItem> = chatsDao.getChat(chatId)

    suspend fun getChatName(chatId: String): String? = chatsDao.getChatName(chatId)

    suspend fun getChatByDeviceId(bittelId: String): ChatItem? =
        chatsDao.getChatContactByBittelID(bittelId)

    suspend fun addChat(chatItem: ChatItem) {
        chatsDao.addChat(chatItem)
        if (chatItem.isGroup) GroupsUtils.addGroupIds(listOf(chatItem.chat_id))
    }

    suspend fun addChats(chatItems: List<ChatItem>) {
        chatsDao.addChats(chatItems)
        chatItems
            .mapNotNull { if (it.isGroup) it.chat_id else null }
            .also { GroupsUtils.addGroupIds(it) }
    }

    suspend fun updateChatName(chatId: String, name: String) =
        chatsDao.updateChatName(chatId, name)

    suspend fun updateDisplayName(chatId: String, name: String) =
        chatsDao.updateDisplayName(chatId, name)

    suspend fun updateAudioReceived(chatId: String, isAudioReceived: Boolean) =
        chatsDao.updateChatAudioReceived(chatId, isAudioReceived)

    suspend fun updateEnableBackgroundPtt(chatId: String, enable: Boolean) =
        chatsDao.updateChatBackgroundPttEnable(chatId, enable)

    suspend fun updateNumOfUnseenMessages(chatId: String, count: Int) =
        chatsDao.updateNumOfUnseenMessages(chatId, count)

    suspend fun getAllGroupIds(): List<String> = chatsDao.getAllGroupIds()

    suspend fun resetChatsMessages() = chatsDao.resetChatsMessages()

    suspend fun deleteChat(chatId: String) = chatsDao.deleteUser(chatId)

    suspend fun clearChats(): Boolean {
        chatsDao.clearData()
        GroupsUtils.clearData()
        return true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Contacts
    // ─────────────────────────────────────────────────────────────────────

    fun readAllContacts(): Flow<List<ChatContact>> = contactsDao.getAllContact()

    fun getUserNameByUserId(userId: String): String = contactsDao.getUserName(userId)

    fun getChatContactById(userId: String): ChatContact =
        contactsDao.getChatContactById(userId)

    fun getChatContactByPhone(phone: String): ChatContact =
        contactsDao.getChatContactByPhone(phone)

    fun getChatContactByBittelId(bittelId: String): ChatContact =
        contactsDao.getChatContactByBittelId(bittelId)

    fun getChatContactByBittelIdOrNull(bittelId: String): ChatContact? =
        contactsDao.getChatContactByAppBittelID(bittelId)
            ?: contactsDao.getChatContactByBittelID(bittelId)

    fun getChatContactByAppBittelId(bittelId: String): ChatContact? =
        contactsDao.getChatContactByAppBittelID(bittelId)

    suspend fun addContact(chatContact: ChatContact) = contactsDao.addContact(chatContact)

    suspend fun addAllContacts(contacts: List<ChatContact>) =
        contactsDao.addAllContacts(contacts)

    suspend fun updateContactName(chatId: String, name: String) =
        contactsDao.updateChatName(chatId, name)

    suspend fun deleteContact(userId: String) = withContext(Dispatchers.IO) { contactsDao.deleteContact(userId) }

    suspend fun clearContacts(): Boolean = withContext(Dispatchers.IO) {
        contactsDao.clearData()
        true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Messages
    // ─────────────────────────────────────────────────────────────────────

    fun readAllMessagesByChatId(chatId: String): LiveData<MutableList<MessageItem>> =
        messagesDao.getAllMessagesByChatId(chatId)

    fun readAllMessagesByChatIdPtt(chatId: String): LiveData<MutableList<MessageItem>> =
        messagesDao.getAllMessagesByChatIdPTT(chatId)

    suspend fun getAllMessagesByChatId(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
    ): List<MessageItem> = messagesDao.getAllMessagesByChatId(chatId, startTimestamp, endTimestamp)

    suspend fun getMessagesByChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int,
    ): List<MessageItem> =
        messagesDao.getMessagesByChatInRange(chatId, startTimestamp, endTimestamp, limit)

    suspend fun getPttMessagesForChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int,
    ): List<MessageItem> =
        messagesDao.getPTTMessagesForChatInRange(chatId, startTimestamp, endTimestamp, limit)

    suspend fun addMessages(messageItems: List<MessageItem>) =
        messagesDao.addMessages(messageItems)

    /**
     * Non-blocking message save – goes through the internal buffered channel.
     * Pass [isPTT] = true to honour the "save PTT files" setting.
     */
    fun saveMessage(context: Context, messageItem: MessageItem, isPTT: Boolean = false) {
        if (isPTT && !DataManager.getSavePTTFilesRequired(context)) return
        messageChannel.trySend(messageItem)
    }

    suspend fun updatePttMessage(chatId: String, messageItem: MessageItem) {
        val last = messagesDao.getLastPttMessage(chatId)
        if (last != null) {
            last.isAudioComplete = true
            messagesDao.updateMessage(last)
        } else {
            messagesDao.addMessage(messageItem)
        }
    }

    suspend fun updateMessageSeenByChatId(chatId: String) =
        messagesDao.updateSeenMessages(chatId)

    suspend fun updateAckReceived(chatId: String, messageNumber: Long) =
        messagesDao.updateAckReceived(chatId, messageNumber)

    fun deleteAllMessagesByChatId(chatId: String) = messagesDao.clearChat(chatId)

    suspend fun archiveMessages(
        chatId: String?,
        startTimestamp: Long,
        endTimestamp: Long,
        isArchived: Boolean = true,
    ) = messagesDao.updateMessagesArchivedState(chatId, startTimestamp, endTimestamp, isArchived)

    suspend fun clearChatMessagesInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
    ) = messagesDao.clearChatInRange(chatId, startTimestamp, endTimestamp)

    suspend fun clearMessages(): Boolean = withContext(Dispatchers.IO) {
        messagesDao.clearData()
        true
    }

    // ─────────────────────────────────────────────────────────────────────
    // Combined helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Clears all three tables at once. */
    suspend fun clearAllData(): Boolean {
        clearChats()
        clearContacts()
        clearMessages()
        return true
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
            // ── 1. Chats ──────────────────────────────────────────────────
            val oldChatsDb = ChatsDatabase.getDatabase(context)
            val chats: List<ChatItem> = oldChatsDb.chatsDao().readChats()
            if (chats.isNotEmpty()) {
                chatsDao.addChats(chats)
                Timber.d("Migration: copied ${chats.size} chat(s)")
            }
            oldChatsDb.close()

            // ── 2. Contacts ───────────────────────────────────────────────
            val oldContactsDb = ContactsDatabase.getDatabase(context)
            val contacts: List<ChatContact> = oldContactsDb.contactsDao().readAllContacts()
            if (contacts.isNotEmpty()) {
                contactsDao.addAllContacts(contacts)
                Timber.d("Migration: copied ${contacts.size} contact(s)")
            }
            oldContactsDb.close()

            // ── 3. Messages ───────────────────────────────────────────────
            val oldMessagesDb = MessagesDatabase.getDatabase(context)
            val messages: List<MessageItem> = oldMessagesDb.messagesDao().getAllMessages()
            if (messages.isNotEmpty()) {
                messagesDao.addMessages(messages)
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
        private const val PREFS_NAME = "app_db_prefs"
        private const val KEY_MIGRATION_DONE = "migration_done"
    }
}





