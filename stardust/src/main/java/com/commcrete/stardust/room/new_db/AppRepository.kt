package com.commcrete.stardust.room.new_db

import android.content.Context
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatSummary
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipants
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipantsAsShortParticipantInfo
import com.commcrete.stardust.room.new_db.chat.ShortParticipantInfo
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.internal.ChatsRepository
import com.commcrete.stardust.room.new_db.internal.ContactsRepository
import com.commcrete.stardust.room.new_db.internal.LegacyMigrator
import com.commcrete.stardust.room.new_db.internal.MessagesRepository
import com.commcrete.stardust.room.new_db.internal.RepositoryCaches
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.context
import com.commcrete.stardust.util.RegisteredUserUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Unified repository facade. Every public method delegates to one of five
 * domain-focused sub-repositories under `internal/`:
 *
 *  - [RepositoryCaches] — shared groupId / contact / chatId in-memory caches.
 *  - [ChatsRepository]  — chat reads, creation helpers, deletion, legacy-id
 *    resolution.
 *  - [ContactsRepository] — contact inserts (which trigger chat creation),
 *    name + lookup helpers, group-id queries, roster reads.
 *  - [MessagesRepository] — message reads, the `saveMessage` pipeline,
 *    unseen counters, archival, inbound chat resolution. Owns the
 *    save-mutex shared with `deleteChat` and `clearData`.
 *  - [LegacyMigrator] — one-shot migration from the three legacy databases.
 *
 * # Initialization order
 * Field declarations follow the dependency DAG strictly:
 *
 *   caches → chats → contacts → messages → legacyMigrator
 *
 * `chats` references `messages` only inside a lambda passed for save-lock
 * bridging, so the lambda's late lookup is fine. `messages` and
 * `legacyMigrator` reference `contacts` directly via method references that
 * resolve at call time, but they are declared **after** `contacts`, so the
 * member is initialized by the time those references are first invoked.
 */
class AppRepository(
    private val chatsDao: ChatDao,
    private val contactsDao: ContactsDao,
    private val messagesDao: MessageDao,
) {

    /**
     * In-memory caches for groupIds, contact-id resolution, and received-package
     * chatId resolution. See [RepositoryCaches] for the per-cache contract.
     */
    private val caches: RepositoryCaches = RepositoryCaches(contactsDao)

    /**
     * Chats domain. [deleteChat] runs under the same save-lock as
     * [MessagesRepository.saveMessage] via a lambda that defers the lookup
     * of [messages] until invocation time (so the forward reference is
     * resolved only after [messages] has been initialized below).
     */
    private val chats: ChatsRepository = ChatsRepository(
        chatsDao = chatsDao,
        contactsDao = contactsDao,
        caches = caches,
        withSaveLock = { block -> messages.withSaveLock { block() } },
    )

    /**
     * Contacts domain. Insert paths funnel chat creation through [chats], so
     * `chats` must be initialized first.
     */
    private val contacts: ContactsRepository = ContactsRepository(
        contactsDao = contactsDao,
        chatsDao = chatsDao,
        caches = caches,
        chats = chats,
        registeredAppIdProvider = {
            RegisteredUserUtils.mRegisterUser.value?.appId
                ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        },
    )

    /**
     * Messages domain. Owns `saveMutex` (exposed via [MessagesRepository.withSaveLock]
     * for `deleteChat` / `clearData`). Auto-create flow funnels back through
     * [contacts.insertContactWithChat][ContactsRepository.insertContactWithChat],
     * which is why `contacts` is declared first.
     */
    private val messages: MessagesRepository = MessagesRepository(
        messagesDao = messagesDao,
        chatsDao = chatsDao,
        contactsDao = contactsDao,
        caches = caches,
        registeredUserIdsProvider = ::registeredUserIds,
        savePttRequired = { DataManager.getSavePTTFilesRequired(context) },
        insertContactWithChat = contacts::insertContactWithChat,
    )

    /**
     * Legacy-DB migration + cleanup. Bulk-inserts go through
     * [contacts.insertContactsWithChats][ContactsRepository.insertContactsWithChats]
     * so the cache is warmed exactly as it is for live inserts.
     */
    private val legacyMigrator: LegacyMigrator = LegacyMigrator(
        messagesDao = messagesDao,
        insertContacts = contacts::insertContactsWithChats,
    )

    // ─────────────────────────────────────────────────────────────────────
    // Contacts — delegated to [ContactsRepository]
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inserts all contacts and creates a chat for each one.
     * See [insertContactWithChat] for per-contact logic.
     */
    suspend fun insertContactsWithChats(contactsToInsert: List<FullContactData>) =
        contacts.insertContactsWithChats(contactsToInsert)

    /**
     * Inserts a single contact and creates a chat for it:
     * - USER / DEVICE → private chat + added to every existing GROUP chat.
     * - GROUP → new group chat populated with all existing non-group members.
     */
    suspend fun insertContactWithChat(contact: FullContactData) =
        contacts.insertContactWithChat(contact)

    /** Live stream of group IDs from the dedicated group-id table, linked to GROUP chats. */
    fun observeGroupIds(): Flow<List<String>> = contacts.observeGroupIds()

    /** Returns all known group IDs from the dedicated group-id source. */
    suspend fun getAllGroupIds(): List<String> = contacts.getAllGroupIds()

    /** True when [id] belongs to a known GROUP contact. */
    suspend fun isGroupId(id: String?): Boolean = contacts.isGroupId(id)

    /** True when any value in [ids] belongs to a known GROUP contact. */
    suspend fun hasAnyGroupId(ids: Collection<String?>): Boolean = contacts.hasAnyGroupId(ids)

    /**
     * Returns the display name of the contact that owns [id], searching across
     * user IDs, group IDs, and device IDs. Returns null if no contact owns [id].
     */
    suspend fun getContactNameById(id: String): String? = contacts.getContactNameById(id)

    suspend fun getContactNameByIdOrId(id: String): String = contacts.getContactNameByIdOrId(id)

    /**
     * Returns the display name of the GROUP contact that owns [groupId].
     * Returns null if no group contact owns that ID.
     */
    suspend fun getGroupNameById(groupId: String): String? = contacts.getGroupNameById(groupId)

    /** Returns the GROUP contact that owns [groupId], or null when not found. */
    suspend fun getGroupContactById(groupId: String): ContactEntity? =
        contacts.getGroupContactById(groupId)

    /**
     * Returns true when a contact owns [mainCommunicationId] across USER / GROUP / DEVICE
     * identity tables — i.e. the value matches the semantics of
     * [FullContactData.getMainCommunicationId].
     *
     * Hits the in-memory contacts cache first (which is keyed by the same
     * normalized ids), and falls back to a single-shot DB lookup on miss.
     * Blank / null inputs return false.
     */
    suspend fun isContactExistsByMainCommunicationId(mainCommunicationId: String?): Boolean =
        contacts.isContactExistsByMainCommunicationId(mainCommunicationId)

    /**
     * Returns the contactId that owns [mainCommunicationId], or null when none does.
     * Same lookup path as [isContactExistsByMainCommunicationId] — cache first, then DB.
     */
    suspend fun findContactIdByMainCommunicationId(mainCommunicationId: String?): Int? =
        contacts.findContactIdByMainCommunicationId(mainCommunicationId)

    /**
     * Bulk variant of [isContactExistsByMainCommunicationId]: given a list of
     * MainCommunicationIds, returns the subset that has NO known contact in the DB.
     *
     * - Null / blank entries are filtered out.
     * - Duplicates in the input are collapsed; first-occurrence order preserved.
     * - Hits the in-memory contacts cache first; only ids that miss the cache
     *   trigger a single bulk DB query.
     */
    suspend fun findUnknownMainCommunicationIds(mainCommunicationIds: List<String>): List<String> =
        contacts.findUnknownMainCommunicationIds(mainCommunicationIds)

    /** Every USER + GROUP contact except the registered user themselves. */
    suspend fun getUserAndGroupContactsExceptSelf(): List<FullContactData> =
        contacts.getUserAndGroupContactsExceptSelf()

    /** Every USER and DEVICE contact except the registered user themselves. */
    suspend fun getUserAndDeviceContactsExceptSelf(): List<FullContactData> =
        contacts.getUserAndDeviceContactsExceptSelf()

    // ─────────────────────────────────────────────────────────────────────
    // Chats — delegated to [ChatsRepository]
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reactive chat list for the UI. Each [ChatSummary] contains the
     * last message text/sender and the current unseen count, computed live
     * from the messages table. The Flow re-emits on every insert/update/delete
     * in either `new_chats_table` or `new_messages_table`.
     */
    fun getChatSummaries(): Flow<List<ChatSummary>> = chats.getChatSummaries()

    /** Returns all chat IDs currently stored in the database. */
    suspend fun getChatIds(): List<String> = chats.getChatIds()

    /** Fetches just the ChatEntity by ID (lightweight, no participants). Returns null if not found. */
    suspend fun getShortChatDataByChatId(chatId: String): ChatEntity? =
        chats.getShortChatDataByChatId(chatId)

    /** Fetches a single chat by its ID with participants. Returns null if not found. */
    suspend fun getChatWithParticipantsByChatId(chatId: String): ChatWithParticipants? =
        chats.getChatWithParticipantsByChatId(chatId)

    /**
     * Retrieves a chat with its participants as lightweight [ShortParticipantInfo]
     * (id + type only). More efficient than fetching full FullContactData when
     * you only need to know the participant's communication ID and type.
     */
    suspend fun getChatWithParticipantsShortParticipantInfo(chatId: String): ChatWithParticipantsAsShortParticipantInfo? =
        chats.getChatWithParticipantsShortParticipantInfo(chatId)

    /**
     * Observes all chats with their participants as lightweight
     * [ShortParticipantInfo] (id + type only). One bulk participant-id
     * projection per emission — never per-chat or per-participant.
     */
    fun observeAllChatsWithShortParticipantInfo(): Flow<List<ChatWithParticipantsAsShortParticipantInfo>> =
        chats.observeAllChatsWithShortParticipantInfo()

    /** Deletes a chat by ID. Related messages/participants are deleted by FK cascade. */
    suspend fun deleteChat(chatId: String): Boolean = chats.deleteChat(chatId)

    suspend fun findNewChatIdByPreviousChatId(previousChatId: String): String? =
        chats.findNewChatIdByPreviousChatId(previousChatId)

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
        chats.findNewChatIdsByPreviousChatIds(previousChatIds)

    // ─────────────────────────────────────────────────────────────────────
    // Messages — delegated to [MessagesRepository]
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Live feed of the [limit] most-recent messages in [chatId], ordered
     * chronologically (oldest-first). Re-emits automatically on every insert,
     * update, or delete — suitable for both group and private chat views.
     *
     * For group chats pass [participantId] = null (default).
     * For private chats pass a [participantId] to restrict to messages where
     * that contact is sender **or** receiver.
     */
    fun getMessages(
        chatId: String,
        participantId: String? = null,
        limit: Int = PAGE_SIZE,
    ): Flow<List<MessageEntity>> = messages.getMessages(chatId, participantId, limit)

    /**
     * Fetches the [limit] messages that come just **before** [beforeEpochMs]
     * in [chatId], ordered chronologically. Pair with [getMessages] for
     * infinite upward pagination.
     */
    suspend fun loadOlderMessages(
        chatId: String,
        beforeEpochMs: Long,
        participantId: String? = null,
        limit: Int = PAGE_SIZE,
    ): List<MessageEntity> = messages.loadOlderMessages(chatId, beforeEpochMs, participantId, limit)

    /**
     * Loads a target-scoped page (sender OR receiver == [targetId]) inside one chat.
     * Returned list is chronological.
     */
    suspend fun loadPageForTarget(
        targetId: String,
        chatId: String,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): List<MessageEntity> = messages.loadPageForTarget(targetId, chatId, page, pageSize)

    /**
     * Loads a chat-scoped page (all messages in [chatId] involving the
     * registered user) for the requested [page] number.
     */
    suspend fun loadPageForChat(
        chatId: String,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): List<MessageEntity> = messages.loadPageForChat(chatId, page, pageSize)

    /** Live top-page stream for a single target in one chat. */
    fun observeLatestForTarget(
        targetId: String,
        chatId: String,
        limit: Int = PAGE_SIZE,
    ): Flow<List<MessageEntity>> = messages.observeLatestForTarget(targetId, chatId, limit)

    
    suspend fun saveMessage(message: MessageEntity, groupId: String? = null): Long? =
        messages.saveMessage(message, groupId)

    /**
     * Resolves chatId for an incoming package using participantId + optional groupId.
     * Uses cache for hot packet flow and DB lookup on cache miss.
     */
    suspend fun getChatIdForReceivedPackage(participantId: String, groupId: String?): String =
        messages.getChatIdForReceivedPackage(participantId, groupId)

    /**
     * Resolves chat for an incoming package using participantId + optional groupId.
     * Same cache + DB lookup flow as [getChatIdForReceivedPackage].
     */
    suspend fun getChatForReceivedPackage(participantId: String, groupId: String?): ChatEntity? =
        messages.getChatForReceivedPackage(participantId, groupId)

    suspend fun updateMessageReceived(messageId: Long) = messages.updateMessageReceived(messageId)

    /** Deletes all messages for [chatId]. */
    suspend fun clearChatMessages(chatId: String) = messages.clearChatMessages(chatId)

    /** Deletes messages for [chatId] whose epoch is within [startTimestamp]..[endTimestamp]. */
    suspend fun clearChatMessagesInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
    ) = messages.clearChatMessagesInRange(chatId, startTimestamp, endTimestamp)

    // ── Unseen counters ──────────────────────────────────────────────────

    /** Live unseen-message count for [chatId]. */
    fun observeUnseenCountForChat(chatId: String): Flow<Int> =
        messages.observeUnseenCountForChat(chatId)

    /** Live unseen-message count for messages in [chatId] where [targetId] is sender OR receiver. */
    fun observeUnseenCountForTarget(chatId: String, targetId: String): Flow<Int> =
        messages.observeUnseenCountForTarget(chatId, targetId)

    /**
     * Live unseen-message counts for every (chatId, targetId) combination
     * where `chatId IN [chatIds]` and `targetId IN [targetIds]`.
     */
    fun observeUnseenCountsForTargets(
        chatIds: List<String>,
        targetIds: List<String>,
    ): Flow<Map<String, Map<String, Int>>> =
        messages.observeUnseenCountsForTargets(chatIds, targetIds)

    /** Live unseen-message counts for every chat in [chatIds]. */
    fun observeUnseenCountsForChats(chatIds: List<String>): Flow<Map<String, Int>> =
        messages.observeUnseenCountsForChats(chatIds)

    // ── Archival ─────────────────────────────────────────────────────────

    /** Archives a single message by ID. Returns true when a row was updated. */
    suspend fun archiveMessage(messageId: Int): Boolean = messages.archiveMessage(messageId)

    /** Archives multiple messages by ID. Returns number of rows updated. */
    suspend fun archiveMessages(messageIds: Collection<Int>): Int = messages.archiveMessages(messageIds)

    suspend fun archiveMessagesInRange(startTimestamp: Long, endTimestamp: Long): Boolean =
        messages.archiveMessagesInRange(startTimestamp, endTimestamp)

    /**
     * Marks all RECEIVED / RECEIVING messages in [chatId] that arrived on or before
     * [untilEpochMs] as SEEN. Defaults to current system time.
     */
    suspend fun updateChatRead(chatId: String, untilEpochMs: Long = System.currentTimeMillis()) =
        messages.updateChatRead(chatId, untilEpochMs)

    // ─────────────────────────────────────────────────────────────────────
    // Cross-cutting orchestration
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Lower-cased identifiers (appId + deviceId) of the registered user, or
     * empty when absent. Wired into [MessagesRepository] for [loadPageForChat].
     */
    private fun registeredUserIds(): List<String> {
        val user = RegisteredUserUtils.mRegisterUser.value ?: return emptyList()
        return listOfNotNull(
            user.appId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            user.deviceId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        ).distinct()
    }

    /**
     * Wipes the unified database and the legacy databases. Runs under the
     * save-lock so no [saveMessage] can be mid-flight when the tables are
     * cleared.
     */
    suspend fun clearData(): Boolean = messages.withSaveLock {
        withContext(Dispatchers.IO) {
            val appContext = context

            val newDbCleared = runCatching {
                AppDatabase.getDatabase(appContext).clearAllTables()
                true
            }.getOrDefault(false)

            caches.resetAll()

            val legacyCleared = legacyMigrator.clearLegacy(appContext)

            newDbCleared && legacyCleared
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // One-time migration from legacy databases
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Copies every row from the three legacy databases into this unified
     * [AppDatabase], then deletes the old database files. Delegates to
     * [LegacyMigrator.migrate].
     *
     * Guarded by a SharedPreferences flag so it runs exactly once per
     * installation. Safe to call multiple times.
     */
    suspend fun migrateFromLegacyDatabases(context: Context) {
        legacyMigrator.migrate(context)
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}
