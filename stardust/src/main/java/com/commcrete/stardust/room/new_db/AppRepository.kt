package com.commcrete.stardust.room.new_db


import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.contacts.ContactConflictEngine
import com.commcrete.stardust.contacts.ContactConflicts
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatSummary
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipants
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipantsAsFullParticipantInfo
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipantsAsShortParticipantInfo
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.internal.ChatsRepository
import com.commcrete.stardust.room.new_db.internal.ContactsRepository
import com.commcrete.stardust.room.new_db.internal.LegacyMigrator
import com.commcrete.stardust.room.new_db.internal.MessagesRepository
import com.commcrete.stardust.room.new_db.internal.RepositoryCaches
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.room.RepositoryProvider
import com.commcrete.stardust.room.new_db.chat.ChatTypeUnseen
import com.commcrete.stardust.room.new_db.message.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.Int


/**
 * Unified repository facade. Delegates to five domain sub-repositories:
 * [RepositoryCaches], [ChatsRepository], [ContactsRepository],
 * [MessagesRepository], [LegacyMigrator].
 *
 * Field order follows the dependency DAG and must not be reshuffled:
 *   caches -> chats -> contacts -> messages -> legacyMigrator -> cachedContacts
 *
 * Message reads accept optional MessageType filters (`types` / `excludeTypes`).
 * This class deliberately knows nothing about lanes, streams, or seen-intervals —
 * those are plugin presentation concepts.
 */
class AppRepository(
    private val chatsDao: ChatDao,
    private val contactsDao: ContactsDao,
    private val messagesDao: MessageDao,
) {

    // ─────────────────────────────────────────────────────────────────────
    // Sub-repositories (declaration order = dependency order)  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    private val caches: RepositoryCaches = RepositoryCaches(contactsDao)

    private val chats: ChatsRepository = ChatsRepository(
        chatsDao = chatsDao,
        contactsDao = contactsDao,
        caches = caches,
        withSaveLock = { block -> messages.withSaveLock { block() } },
    )

    private val contacts: ContactsRepository = ContactsRepository(
        contactsDao = contactsDao,
        chatsDao = chatsDao,
        caches = caches,
        chats = chats,
        registeredAppIdProvider = {
            RegisteredUserUtils.currentUserFlow.value?.appId?.takeIf { it.isNotEmpty() }
        },
    )

    private val messages: MessagesRepository = MessagesRepository(
        messagesDao = messagesDao,
        chatsDao = chatsDao,
        contactsDao = contactsDao,
        caches = caches,
        registeredUserIdsProvider = ::registeredUserIds,
        savePttRequired = { DataManager.getSavePTTFilesRequired() },
        insertContactWithChat = contacts::insertContactWithChat,
    )

    private val legacyMigrator: LegacyMigrator = LegacyMigrator(
        messagesDao = messagesDao,
        insertContacts = contacts::insertContactsWithChats,
    )

    private val cachedContacts: StateFlow<List<FullContactData>?> =
        observeAllContacts().stateIn(
            RepositoryProvider.AppScopes.applicationScope, SharingStarted.Eagerly, null,
        )

    // ─────────────────────────────────────────────────────────────────────
    // Contacts — reads  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    suspend fun getAllContacts(): List<FullContactData> = contacts.getAllContacts()

    fun observeAllContacts(): Flow<List<FullContactData>> = contacts.observeAllContacts()

    suspend fun getUserAndGroupContactsExceptSelf(): List<FullContactData> =
        contacts.getUserAndGroupContactsExceptSelf()

    fun observeUserAndGroupContactsExceptSelf(): Flow<List<FullContactData>> =
        contacts.observeUserAndGroupContactsExceptSelf()

    suspend fun getUserAndDeviceContactsExceptSelf(): List<FullContactData> =
        contacts.getUserAndDeviceContactsExceptSelf()

    suspend fun getContactNameById(id: String): String? = contacts.getContactNameById(id)

    suspend fun getContactNameByIdOrId(id: String): String = contacts.getContactNameByIdOrId(id)

    suspend fun getGroupNameById(groupId: String): String? = contacts.getGroupNameById(groupId)

    suspend fun getGroupContactById(groupId: String): ContactEntity? =
        contacts.getGroupContactById(groupId)

    suspend fun isContactExistsByMainCommunicationId(mainCommunicationId: String?): Boolean =
        contacts.isContactExistsByMainCommunicationId(mainCommunicationId)

    suspend fun findContactIdByMainCommunicationId(mainCommunicationId: String?): Int? =
        contacts.findContactIdByMainCommunicationId(mainCommunicationId)

    suspend fun findUnknownMainCommunicationIds(mainCommunicationIds: List<String>): List<String> =
        contacts.findUnknownMainCommunicationIds(mainCommunicationIds)

    fun observeGroupIds(): Flow<List<String>> = contacts.observeGroupIds()

    suspend fun getAllGroupIds(): List<String> = contacts.getAllGroupIds()

    suspend fun isGroupId(id: String?): Boolean = contacts.isGroupId(id)

    suspend fun hasAnyGroupId(ids: Collection<String?>): Boolean = contacts.hasAnyGroupId(ids)

    // ─────────────────────────────────────────────────────────────────────
    // Contacts — writes  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inserts every contact and creates a fresh chat for each — no de-duplication.
     * Login-only; re-running on a populated DB duplicates chats. For post-login
     * edits use [applyContactOperations].
     */
    suspend fun insertContactsWithChats(contactsToInsert: List<FullContactData>) =
        contacts.insertContactsWithChats(contactsToInsert)

    suspend fun insertContactWithChat(contact: FullContactData) =
        contacts.insertContactWithChat(contact)

    // ─────────────────────────────────────────────────────────────────────
    // Contact conflicts  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    fun observeCachedContacts(): StateFlow<List<FullContactData>?> = cachedContacts

    fun observeCachedContactDrafts(): Flow<List<ContactDraft>> =
        cachedContacts.filterNotNull().map { it.map(ContactDraft.Companion::fromFullContactData) }

    suspend fun cachedContactDrafts(): List<ContactDraft> =
        (cachedContacts.value ?: getAllContacts()).map(ContactDraft.Companion::fromFullContactData)

    suspend fun getAllContactDrafts(): List<ContactDraft> =
        getAllContacts().map(ContactDraft.Companion::fromFullContactData)

    fun observeAllContactDrafts(): Flow<List<ContactDraft>> =
        observeAllContacts().map { list -> list.map(ContactDraft.Companion::fromFullContactData) }

    suspend fun findContactConflicts(incoming: ContactDraft): ContactConflicts =
        ContactConflictEngine.detect(incoming, cachedContactDrafts())

    /**
     * Applies resolver output in the order that keeps identity swaps and message
     * re-parenting observable: UpdateExisting -> RenameExisting -> Insert ->
     * DeleteExisting (re-parenting first when `reparentTo` is set).
     */
    suspend fun applyContactOperations(ops: List<ContactOperation>) {
        val updates = ops.filterIsInstance<ContactOperation.UpdateExisting>()
        val renames = ops.filterIsInstance<ContactOperation.RenameExisting>()
        val inserts = ops.filterIsInstance<ContactOperation.Insert>()
        val deletes = ops.filterIsInstance<ContactOperation.DeleteExisting>()

        for (u in updates) contacts.updateExistingContact(u.original, u.updated)
        for (r in renames) contacts.renameContactAndChat(r.target, r.newName)

        val toInsert = inserts.mapNotNull { it.contact.toFullContactData() }
        if (toInsert.isNotEmpty()) insertContactsWithChats(toInsert)

        for (d in deletes) {
            val reparentToChatId = d.reparentTo?.let { contacts.chatIdForContactDraft(it) }
            if (reparentToChatId != null) {
                val sourceChatId = contacts.chatIdForContactDraft(d.target)
                if (sourceChatId != null && sourceChatId != reparentToChatId) {
                    messagesDao.reassignChat(sourceChatId, reparentToChatId)
                }
            }
            contacts.deleteContact(d.target)
        }

        if (ops.any { it.touchesGroup() }) GroupsUtils.sendDeleteAllGroups()
    }

    private fun ContactOperation.touchesGroup(): Boolean = when (this) {
        is ContactOperation.Insert -> contact.type == ContactType.GROUP
        is ContactOperation.UpdateExisting ->
            original.type == ContactType.GROUP || updated.type == ContactType.GROUP
        is ContactOperation.DeleteExisting -> target.type == ContactType.GROUP
        is ContactOperation.RenameExisting -> false
        ContactOperation.Noop -> false
    }

    // ─────────────────────────────────────────────────────────────────────
    // Chats
    // ─────────────────────────────────────────────────────────────────────


    fun getChatSummaries(excludeTypes: List<MessageType> = emptyList()): Flow<List<ChatSummary>> =
        chats.getChatSummaries(excludeTypes)

    /** Single-chat summary (e.g. an in-chat header), with the same [excludeTypes] semantics. */
    fun getChatSummary(
        chatId: String,
        excludeTypes: List<MessageType> = emptyList(),
    ): Flow<ChatSummary?> = chats.getChatSummary(chatId, excludeTypes)

    /**
     * Live unseen counts for the split-out [splitTypes], grouped by chat and type — one entry per
     * (chatId, type) that currently has unseen messages. Drives the per-type "new X" indicators.
     */
    fun observeUnseenCountsBySplitType(splitTypes: List<MessageType>): Flow<List<ChatTypeUnseen>> =
        chats.observeUnseenCountsBySplitType(splitTypes)

    suspend fun getChatIds(): List<String> = chats.getChatIds()

    suspend fun getChatByChatId(chatId: String): ChatEntity? = chats.getChatByChatId(chatId)

    suspend fun getChatWithParticipantsByChatId(chatId: String): ChatWithParticipants? =
        chats.getChatWithParticipantsByChatId(chatId)

    suspend fun getChatWithParticipantsShortParticipantInfo(
        chatId: String,
    ): ChatWithParticipantsAsShortParticipantInfo? =
        chats.getChatWithParticipantsShortParticipantInfo(chatId)

    suspend fun getChatWithParticipantsFullParticipantInfo(
        chatId: String,
    ): ChatWithParticipantsAsFullParticipantInfo? =
        chats.getChatWithParticipantsFullParticipantInfo(chatId)

    /**
     * The single [FullContactData] a chat is "about": the lone participant of a
     * PRIVATE chat, or the GROUP contact of a GROUP chat. Null when unresolved.
     */
    suspend fun getContactForChat(chatId: String): FullContactData? =
        chats.getContactForChat(chatId)

    fun observeAllChatsWithShortParticipantInfo(): Flow<List<ChatWithParticipantsAsShortParticipantInfo>> =
        chats.observeAllChatsWithShortParticipantInfo()

    suspend fun deleteChat(chatId: String): Boolean = chats.deleteChat(chatId)

    suspend fun chatIdForContact(contact: FullContactData): String? =
        contacts.chatIdForContactDraft(ContactDraft.fromFullContactData(contact))

    suspend fun mainContactIdByChatId(chatId: String): String? {
        val data = getChatWithParticipantsShortParticipantInfo(chatId) ?: return null
        val main = if (data.chat.type == ChatType.GROUP) {
            data.participants.firstOrNull { it.type == ContactType.GROUP }
        } else {
            data.participants.firstOrNull()
        }
        return main?.id
    }

    suspend fun findNewChatIdByPreviousChatId(previousChatId: String): String? =
        chats.findNewChatIdByPreviousChatId(previousChatId)

    suspend fun findNewChatIdsByPreviousChatIds(previousChatIds: List<String>): List<String?> =
        chats.findNewChatIdsByPreviousChatIds(previousChatIds)

    // ─────────────────────────────────────────────────────────────────────
    // Messages — reads
    //
    // No LaneKey / LaneLayout here: lanes are a presentation concept and live in
    // the plugin. This layer sees only MessageType filters. Boundaries are
    // primitives; callers pass fields off a MessageEntity they already hold.
    // ─────────────────────────────────────────────────────────────────────

    fun observeMessages(
        chatId: String,
        participantId: String?,
        limit: Int = PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<List<MessageEntity>> =
        messages.observeMessages(chatId, participantId, limit, types, excludeTypes)

    suspend fun loadOlder(
        chatId: String,
        participantId: String?,
        beforeEpochMs: Long?,
        beforeId: Int?,
        limit: Int = PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity> = messages.loadOlder(
        chatId, participantId, beforeEpochMs, beforeId, limit, types, excludeTypes,
    )

    suspend fun loadNewer(
        chatId: String,
        participantId: String?,
        afterEpochMs: Long,
        afterId: Int,
        limit: Int = PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity> = messages.loadNewer(
        chatId, participantId, afterEpochMs, afterId, limit, types, excludeTypes,
    )

    suspend fun loadAround(
        chatId: String,
        participantId: String?,
        anchorEpochMs: Long,
        anchorId: Int,
        limit: Int = PAGE_SIZE,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity> = messages.loadAround(
        chatId, participantId, anchorEpochMs, anchorId, limit, types, excludeTypes,
    )

    suspend fun findFirstUnseen(
        chatId: String,
        participantId: String?,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): MessageEntity? = messages.findFirstUnseen(chatId, participantId, types, excludeTypes)

    suspend fun markSeenInRange(
        chatId: String,
        participantId: String?,
        fromEpochMs: Long,
        fromId: Int,
        toEpochMs: Long,
        toId: Int,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Int = messages.markSeenInRange(
        chatId, participantId, fromEpochMs, fromId, toEpochMs, toId, types, excludeTypes,
    )

    // ─────────────────────────────────────────────────────────────────────
    // Messages — writes  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    suspend fun saveMessage(message: MessageEntity, groupId: String? = null): Long? =
        messages.saveMessage(message, groupId)

    suspend fun saveMessage(
        pkg: StardustAPIPackage,
        extraData: MessageExtraData,
        state: MessageState,
        epochTimeMs: Long = System.currentTimeMillis(),
    ): Long? = messages.saveMessage(
        MessageEntity(
            chatId = pkg.chatId,
            senderID = pkg.senderId,
            receiverID = pkg.receiverId,
            extraData = extraData,
            state = state,
            epochTimeMs = epochTimeMs,
            carrierType = pkg.carrier?.type?.type,
            rd = pkg.carrier?.deliveryType?.value,
            // TODO: freqMhz / carrierRange once the package exposes them.
        ),
        pkg.groupId,
    )

    suspend fun getChatIdForReceivedPackage(participantId: String, groupId: String?): String =
        messages.getChatIdForReceivedPackage(participantId, groupId)

    suspend fun getChatForReceivedPackage(participantId: String, groupId: String?): ChatEntity? =
        messages.getChatForReceivedPackage(participantId, groupId)

    // ─────────────────────────────────────────────────────────────────────
    // Messages — state, deletion, archival  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    suspend fun updateMessageReceived(messageId: Long) = messages.updateMessageReceived(messageId)

    suspend fun clearChatMessages(chatId: String) = messages.clearChatMessages(chatId)

    suspend fun clearChatMessagesInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
    ) = messages.clearChatMessagesInRange(chatId, startTimestamp, endTimestamp)

    suspend fun archiveMessage(messageId: Int): Boolean = messages.archiveMessage(messageId)

    suspend fun archiveMessages(messageIds: Collection<Int>): Int =
        messages.archiveMessages(messageIds)

    suspend fun archiveMessagesInRange(startTimestamp: Long, endTimestamp: Long): Boolean =
        messages.archiveMessagesInRange(startTimestamp, endTimestamp)

    // ─────────────────────────────────────────────────────────────────────
    // Messages — unseen counters
    // ─────────────────────────────────────────────────────────────────────

    fun observeReceivedMessageCount(
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<Int> = messages.observeReceivedMessageCount(types, excludeTypes)

    /**
     * Live unseen counts keyed by chatId. Use for GROUP chats — the group target
     * is synthetic, so its unseen count is the chat's unseen count.
     */
    fun observeUnseenCountsForChats(
        chatIds: List<String>,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<Map<String, Int>> =
        messages.observeUnseenCountsForChats(chatIds, types, excludeTypes)

    /**
     * Live unseen counts keyed by chatId -> senderId. PRIVATE chats only:
     * targetIds match sender_id, and a group's synthetic target id is never a sender.
     */
    fun observeUnseenCountsForTargets(
        chatIds: List<String>,
        targetIds: List<String>,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<Map<String, Map<String, Int>>> =
        messages.observeUnseenCountsForTargets(chatIds, targetIds, types, excludeTypes)

    // ─────────────────────────────────────────────────────────────────────
    // Cross-cutting  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    private fun registeredUserIds(): List<String> {
        val user = RegisteredUserUtils.currentUserFlow.value ?: return emptyList()
        return listOfNotNull(
            user.appId.takeIf { it.isNotEmpty() },
            user.deviceId?.takeIf { it.isNotEmpty() },
        ).distinct()
    }

    suspend fun clearData(): Boolean = messages.withSaveLock {
        withContext(Dispatchers.IO) {
            val newDbCleared = runCatching {
                AppDatabase.getDatabase().clearAllTables()
                true
            }.getOrDefault(false)

            caches.resetAll()
            val legacyCleared = legacyMigrator.clearLegacy()

            newDbCleared && legacyCleared
        }
    }

    suspend fun migrateFromLegacyDatabases() = legacyMigrator.migrate()

    companion object {
        const val PAGE_SIZE = 30
    }
}