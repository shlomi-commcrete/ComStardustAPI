package com.commcrete.stardust.room.new_db


import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.contacts.ContactConflictEngine
import com.commcrete.stardust.contacts.ContactConflicts
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.room.new_db.audit.ContactIdentityLogDao
import com.commcrete.stardust.room.new_db.audit.ContactIdentityLogEntity
import com.commcrete.stardust.room.new_db.audit.IdentityLogSource
import com.commcrete.stardust.room.new_db.internal.IdentityLogRecorder
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
import com.commcrete.stardust.room.new_db.message.FileSummary
import com.commcrete.stardust.room.new_db.message.FileTransferCancellation
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
import com.commcrete.stardust.util.FileReceiver
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.room.RepositoryProvider
import com.commcrete.stardust.room.new_db.chat.ChatTypeUnseen
import com.commcrete.stardust.room.new_db.message.MessageType
import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
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
    /**
     * Audit trail of identity ownership changes. Defaulted so the existing
     * three-argument construction keeps compiling; [RepositoryProvider] passes
     * it explicitly alongside the other DAOs.
     */
    private val identityLogDao: ContactIdentityLogDao =
        AppDatabase.getDatabase().appContactIdentityLogDao(),
    /**
     * The database the DAOs above belong to — what a multi-step contact write runs its
     * transaction on (see [inTransaction]). Defaulted like [identityLogDao].
     */
    private val database: RoomDatabase = AppDatabase.getDatabase(),
) {

    // ─────────────────────────────────────────────────────────────────────
    // Sub-repositories (declaration order = dependency order)  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    private val caches: RepositoryCaches = RepositoryCaches(contactsDao)

    private val identityLog: IdentityLogRecorder = IdentityLogRecorder(identityLogDao)

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
        identityLog = identityLog,
        registeredAppIdProvider = {
            RegisteredUserUtils.currentUserFlow.value?.appId?.takeIf { it.isNotEmpty() }
        },
        archiveChatMessages = { chatId -> messagesDao.archiveChatMessages(chatId) },
    )

    private val messages: MessagesRepository = MessagesRepository(
        messagesDao = messagesDao,
        chatsDao = chatsDao,
        contactsDao = contactsDao,
        caches = caches,
        registeredUserIdsProvider = ::registeredUserIds,
        savePttRequired = { DataManager.getSavePTTFilesRequired() },
        insertContactWithChat = { contact ->
            // An unknown sender's contact and its chat land together or not at all.
            inTransaction { contacts.insertContactWithChat(contact, IdentityLogSource.AUTO_CREATE) }
        },
    )

    private val legacyMigrator: LegacyMigrator = LegacyMigrator(
        messagesDao = messagesDao,
        chatsDao = chatsDao,
        insertContacts = { contacts ->
            this.contacts.insertContactsWithChats(contacts, IdentityLogSource.LEGACY_MIGRATION)
        },
    )

    private val cachedContacts: StateFlow<List<FullContactData>?> =
        observeAllContacts().stateIn(
            RepositoryProvider.AppScopes.applicationScope, SharingStarted.Eagerly, null,
        )

    /**
     * Runs [block] as one database transaction. A contact save is several writes — ids, kind,
     * chat, participants, messages archived or moved — and a refused step or a killed process
     * part-way used to keep everything written before it: a contact with its messages archived
     * but its kind unchanged, a device taken from one contact and given to nobody.
     *
     * On failure the id lookups are cleared as well: entries cached inside [block] can point
     * at rows the rollback removed, and a rollback raises no change for the observer to see.
     */
    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        try {
            database.withTransaction { block() }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { caches.invalidateIdentity() }
            throw failure
        }

    init {
        // Keeps the in-memory id lookups in line with every database change to the tables they
        // come from — see RepositoryCaches.invalidateIdentity. For the life of the process, like
        // cachedContacts above.
        RepositoryProvider.AppScopes.applicationScope.launch {
            contactsDao.observeIdentityTables()
                // Cause only: a database error message can quote the row it failed on.
                .catch { Log.w(TAG, "Id lookup sync stopped: ${it.javaClass.simpleName}") }
                .collect { caches.invalidateIdentity() }
        }
    }

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
    suspend fun insertContactsWithChats(
        contactsToInsert: List<FullContactData>,
        source: String = IdentityLogSource.LOGIN_IMPORT,
    ) = inTransaction { contacts.insertContactsWithChats(contactsToInsert, source) }

    suspend fun insertContactWithChat(
        contact: FullContactData,
        source: String = IdentityLogSource.UNKNOWN,
    ) = inTransaction { contacts.insertContactWithChat(contact, source) }

    // ─────────────────────────────────────────────────────────────────────
    // Contact conflicts  [= UNCHANGED]
    // ─────────────────────────────────────────────────────────────────────

    fun observeCachedContacts(): StateFlow<List<FullContactData>?> = cachedContacts

    /*
     * The draft projections below convert the WHOLE roster, which is real work on
     * a large one. `map` alone runs on the CALLER's dispatcher — for a collector
     * or a caller on `Dispatchers.Main` that is a main-thread pass over every
     * contact. The conversion is pure CPU (no DB), so it belongs on Default, and
     * pinning it here means no call site has to remember to wrap it.
     */

    fun observeCachedContactDrafts(): Flow<List<ContactDraft>> =
        cachedContacts.filterNotNull()
            .map { it.map(ContactDraft.Companion::fromFullContactData) }
            .flowOn(Dispatchers.Default)

    suspend fun cachedContactDrafts(): List<ContactDraft> =
        // getAllContacts() hops to IO on its own; only the projection needs Default.
        (cachedContacts.value ?: getAllContacts()).let { contacts ->
            withContext(Dispatchers.Default) {
                contacts.map(ContactDraft.Companion::fromFullContactData)
            }
        }

    suspend fun getAllContactDrafts(): List<ContactDraft> =
        getAllContacts().let { contacts ->
            withContext(Dispatchers.Default) {
                contacts.map(ContactDraft.Companion::fromFullContactData)
            }
        }

    fun observeAllContactDrafts(): Flow<List<ContactDraft>> =
        observeAllContacts()
            .map { list -> list.map(ContactDraft.Companion::fromFullContactData) }
            .flowOn(Dispatchers.Default)

    suspend fun findContactConflicts(incoming: ContactDraft): ContactConflicts =
        cachedContactDrafts().let { candidates ->
            withContext(Dispatchers.Default) {
                ContactConflictEngine.detect(incoming, candidates)
            }
        }

    /**
     * How many messages in [draft]'s chat would be archived by changing it between user and
     * group (see [ContactOperation.UpdateExisting]) — for the confirmation that names the
     * cost before the operator agrees to it. 0 when the contact or its chat does not exist.
     */
    suspend fun archivableMessageCount(draft: ContactDraft): Int = withContext(Dispatchers.IO) {
        contacts.chatIdForContactDraft(draft)?.let { messagesDao.countArchivableChatMessages(it) } ?: 0
    }

    /**
     * Applies resolver output in the order that keeps identity swaps and message
     * re-parenting observable: UpdateExisting -> RenameExisting -> Insert ->
     * DeleteExisting (re-parenting first when `reparentTo` is set).
     *
     * Every row this writes to `contact_identity_log` shares one
     * [IdentityLogRecorder.nextBatchId], so the strip half and the assign half of
     * a swap can be read back as the single move they were — see
     * [identityLogBatch].
     *
     * All of [ops] land in one transaction, or none do — see [inTransaction]. A failure is
     * thrown with nothing written, so a caller can report it and offer the same save again.
     */
    suspend fun applyContactOperations(ops: List<ContactOperation>) {
        inTransaction { applyOperations(ops) }
        // A command to the radio, so after the commit: a rollback cannot take it back.
        if (ops.any { it.touchesGroup() }) GroupsUtils.sendDeleteAllGroups()
    }

    private suspend fun applyOperations(ops: List<ContactOperation>) {
        val updates = ops.filterIsInstance<ContactOperation.UpdateExisting>()
        val renames = ops.filterIsInstance<ContactOperation.RenameExisting>()
        val inserts = ops.filterIsInstance<ContactOperation.Insert>()
        val deletes = ops.filterIsInstance<ContactOperation.DeleteExisting>()

        val source = IdentityLogSource.CONFLICT_RESOLUTION
        val batchId = identityLog.nextBatchId()
        // Who holds each id this batch touches, before it runs — what the message moves and
        // archiving below are decided against.
        val ownersBefore = ownersOf(idsIn(ops))

        // Every delete target is resolved BEFORE anything is written. A target
        // is a draft and resolves by its ids — and a delete is scheduled exactly
        // when those ids have been moved to another contact earlier in this
        // batch. Resolved afterwards, a device contact emptied by a Move finds
        // the contact that just received its device, and deletes that instead.
        val deleteTargets = deletes.map { d ->
            val contactId = contacts.contactIdForDraft(d.target)
            val chatId = contactId?.let { contacts.chatIdForContactId(d.target.type, it) }
            Triple(d, contactId, chatId)
        }

        for (u in updates) contacts.updateExistingContact(u.original, u.updated, source, batchId)
        for (r in renames) contacts.renameContactAndChat(r.target, r.newName, source, batchId)

        val toInsert = inserts.mapNotNull { it.contact.toFullContactData() }
        if (toInsert.isNotEmpty()) contacts.insertContactsWithChats(toInsert, source, batchId)

        // Before the deletes: a contact emptied by a move still has its chat to move from.
        val touchedChats = moveMessagesOfMovedIds(ownersBefore, source, batchId)

        for ((d, fromContactId, sourceChatId) in deleteTargets) {
            // Gone already, or never there: nothing of THIS contact to delete.
            if (fromContactId == null) continue
            // The insert this delete pairs with MERGED into the target: addContact
            // matches an existing contact by the ids it brings, and those are the
            // ids the target held. The target's row is now the incoming contact,
            // so deleting it would delete what was just saved.
            val reparentToContactId = d.reparentTo?.let { contacts.contactIdForDraft(it) }
            if (reparentToContactId == fromContactId) continue
            val reparentToChatId = d.reparentTo?.let { contacts.chatIdForContactDraft(it) }
            if (reparentToChatId != null) {
                if (sourceChatId != null && sourceChatId != reparentToChatId) {
                    val toContactId = reparentToContactId
                    val moved = messagesDao.reassignChat(sourceChatId, reparentToChatId)
                    identityLog.recordReparent(
                        fromChatId = sourceChatId,
                        toChatId = reparentToChatId,
                        affectedRows = moved,
                        fromContactId = fromContactId,
                        toContactId = toContactId,
                        fromName = d.target.name,
                        toName = d.reparentTo?.name,
                        source = source,
                        batchId = batchId,
                    )
                }
            }
            contacts.deleteContact(d.target, source, batchId, resolvedContactId = fromContactId)
            reparentToChatId?.let { touchedChats += it }
        }

        archiveMessagesOfFormerIds(touchedChats)
    }

    /** Every id named anywhere in [ops], normalized. */
    private fun idsIn(ops: List<ContactOperation>): Set<String> = ops.flatMap { op ->
        when (op) {
            is ContactOperation.UpdateExisting -> listOf(op.original, op.updated)
            is ContactOperation.RenameExisting -> listOf(op.target)
            is ContactOperation.Insert -> listOf(op.contact)
            is ContactOperation.DeleteExisting -> listOfNotNull(op.target, op.reparentTo)
            ContactOperation.Noop -> emptyList()
        }
    }.flatMap { listOf(it.appId, it.deviceId) }
        .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
        .toSet()

    /** Which contact holds each of [ids] now; ids nobody holds are absent. */
    private suspend fun ownersOf(ids: Set<String>): Map<String, Int> {
        if (ids.isEmpty()) return emptyMap()
        return contactsDao.getAllIdToContactRows()
            .mapNotNull { row -> row.normalizedId.trim().lowercase().takeIf { it in ids }?.let { it to row.contactId } }
            .toMap()
    }

    /** The chat of [contactId] — private, or its group chat for a GROUP. */
    private suspend fun chatOf(contactId: Int): String? {
        val type = contactsDao.getContactById(contactId)?.type ?: return null
        return contacts.chatIdForContactId(type, contactId)
    }

    /**
     * An id that moved to another contact in this batch takes its messages with it: rows in
     * the former holder's chat exchanged with that id move to the new holder's chat.
     *
     * Returns every chat whose contact held an involved id before or holds one now — the
     * chats [archiveMessagesOfFormerIds] then cleans up.
     */
    private suspend fun moveMessagesOfMovedIds(
        ownersBefore: Map<String, Int>,
        source: String,
        batchId: Long,
    ): MutableSet<String> {
        val ownersAfter = ownersOf(ownersBefore.keys)
        val touched = mutableSetOf<String>()
        (ownersBefore.values + ownersAfter.values).toSet().forEach { contactId -> chatOf(contactId)?.let { touched += it } }
        ownersBefore.forEach { (id, fromContactId) ->
            val toContactId = ownersAfter[id] ?: return@forEach
            if (toContactId == fromContactId) return@forEach
            val fromChatId = chatOf(fromContactId) ?: return@forEach
            val toChatId = chatOf(toContactId) ?: return@forEach
            if (fromChatId == toChatId) return@forEach
            val moved = messagesDao.moveChatMessagesOfPeer(fromChatId, toChatId, id)
            if (moved > 0) {
                identityLog.recordReparent(
                    fromChatId = fromChatId,
                    toChatId = toChatId,
                    affectedRows = moved,
                    fromContactId = fromContactId,
                    toContactId = toContactId,
                    fromName = contactsDao.getContactById(fromContactId)?.name,
                    toName = contactsDao.getContactById(toContactId)?.name,
                    source = source,
                    batchId = batchId,
                )
            }
        }
        return touched
    }

    /**
     * In each of [chatIds] that is a private chat, archives the messages exchanged with ids its
     * contact no longer holds — an id removed or replaced, or the rest of a deleted contact's
     * chat that moved in whole. The chat loaders never show those rows; archiving says so
     * instead of leaving them as unexplained gaps. Group chats show every message and keep them.
     */
    private suspend fun archiveMessagesOfFormerIds(chatIds: Set<String>) {
        chatIds.forEach { chatId ->
            val chat = chatsDao.getChatByChatId(chatId) ?: return@forEach
            if (chat.type != ChatType.PRIVATE) return@forEach
            val contactId = contactsDao.getChatUserAndDeviceContactRows(chatId).firstOrNull()?.contact?.id
                ?: return@forEach
            val currentIds = contactsDao.getOwnedIdRows(listOf(contactId)).map { it.idValue.trim().lowercase() }
            messagesDao.archiveChatMessagesNotOfPeers(chatId, currentIds)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Contact identity audit log
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Newest identity changes, newest first — a swap/rename/delete feed for a
     * diagnostics screen or a bug report attachment.
     */
    suspend fun recentIdentityChanges(limit: Int = 200): List<ContactIdentityLogEntity> =
        identityLog.recent(limit)

    /** Live variant of [recentIdentityChanges]. */
    fun observeRecentIdentityChanges(limit: Int = 200): Flow<List<ContactIdentityLogEntity>> =
        identityLogDao.observeRecent(limit)

    /** Full ownership history of one userId / groupId / deviceId, oldest first. */
    suspend fun identityHistoryForId(id: String): List<ContactIdentityLogEntity> =
        identityLog.historyForId(id)

    /**
     * Which contact owned [id] at [atMs]. See
     * [ContactIdentityLogDao.ownershipAt] for how to read the result — the row's
     * `kind` decides whether `to_contact_id` is an owner or a strip.
     */
    suspend fun identityOwnerAt(id: String, atMs: Long): ContactIdentityLogEntity? =
        identityLog.ownershipAt(id, atMs)

    /**
     * Who owned [message]'s sender id when it arrived. Null when the id has not
     * changed hands since logging began — i.e. the current contact is the right
     * attribution.
     */
    suspend fun identityOwnerWhenReceived(message: MessageEntity): ContactIdentityLogEntity? =
        identityLog.ownershipAt(message.senderID, message.epochTimeMs)

    /** Everything that ever touched [contactId], on either side of a change. */
    suspend fun identityHistoryForContact(contactId: Int): List<ContactIdentityLogEntity> =
        identityLog.historyForContact(contactId)

    /** Every row written by one [applyContactOperations] call, in write order. */
    suspend fun identityLogBatch(batchId: Long): List<ContactIdentityLogEntity> =
        identityLog.batch(batchId)

    /**
     * Bulk `chat_id` rewrites into or out of [chatId] — explains a chat whose
     * history stops abruptly, or one that gained older messages.
     */
    suspend fun messageReparentsForChat(chatId: String): List<ContactIdentityLogEntity> =
        identityLog.reparentsForChat(chatId)

    /**
     * Drops audit rows older than [retentionMs]. Returns the number deleted.
     * Nothing calls this on a timer — the host app decides when to run it.
     */
    suspend fun pruneIdentityLog(retentionMs: Long = IDENTITY_LOG_RETENTION_MS): Int =
        identityLog.prune(System.currentTimeMillis() - retentionMs)

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

    suspend fun updateMessageState(messageId: Long, state: MessageState) =
        messages.updateMessageState(messageId, state)

    /**
     * Records that [ackedBy] acknowledged an SOS this user sent, appending them to the
     * ack list on that SOS's own row rather than storing the ack as a message of its
     * own. Returns false when the ack matched no SOS, or when that acker is already
     * recorded on it.
     *
     * [chatId] is the chat the ack packet resolved to; pass it even when blank, the
     * match then falls back to the acker's chat membership so a group SOS still settles.
     * See `MessagesRepository.recordSosAck`.
     */
    suspend fun recordSosAck(
        chatId: String?,
        ackedBy: String,
        ackedAtMs: Long = System.currentTimeMillis(),
    ): Boolean = messages.recordSosAck(chatId, ackedBy, ackedAtMs)

    /**
     * Records a file/image transfer failure on an existing OUTGOING message row (where
     * the row was created when the send started): state FAILED plus the reason merged
     * into the row's extra_data, and the row restamped with the moment it gave up.
     * Returns false when the row had already settled and the failure was refused.
     *
     * The incoming side has its own pair, [markIncomingTransferFailed] and
     * [markIncomingTransferReceived], which leave the timestamp alone.
     */
    suspend fun markFileTransferFailed(
        messageId: Long,
        failure: FileReceiver.FileFailure,
    ): Boolean = messages.markFileTransferFailed(messageId, failure)

    /**
     * Writes the text assembled so far onto an incoming multi-part text's RECEIVING row.
     * Returns false when the row had already settled and the write was refused.
     */
    suspend fun updateIncomingTextInFlight(messageId: Long, text: String): Boolean =
        messages.updateIncomingTextInFlight(messageId, text)

    /**
     * Settles an incoming multi-part text's RECEIVING row as RECEIVED with its whole [text].
     * Returns false when the row had already settled and the write was refused.
     */
    suspend fun markIncomingTextReceived(messageId: Long, text: String): Boolean =
        messages.markIncomingTextReceived(messageId, text)

    /**
     * Settles an incoming multi-part text that ended without its LAST part — gap timeout
     * ([FileReceiver.FileFailure.MISSING]) or disconnect ([FileReceiver.FileFailure.DISCONNECTED])
     * — as RECEIVED with [failure] saying why it is incomplete, keeping the parts that arrived:
     * part of a message did arrive, and it counts as unread. Returns false when the row had
     * already settled and the write was refused.
     */
    suspend fun markIncomingTextIncomplete(
        messageId: Long,
        text: String,
        failure: FileReceiver.FileFailure,
    ): Boolean = messages.markIncomingTextIncomplete(messageId, text, failure)

    /**
     * Records an outgoing send's progress — [extraData] carrying its updated
     * [com.commcrete.stardust.room.new_db.message.SendProgress] — on its SENDING row. Returns
     * false when the row had already settled and the write was refused.
     */
    suspend fun updateSendProgress(messageId: Long, extraData: MessageExtraData): Boolean =
        messages.updateSendProgress(messageId, extraData)

    /**
     * Settles a SENDING row as [state] (SENT or FAILED), merging [extraData] when given.
     * Returns false when the row had already settled — an ACK or a cancel got there first.
     */
    suspend fun settleOutgoing(
        messageId: Long,
        state: MessageState,
        extraData: MessageExtraData? = null,
    ): Boolean = messages.settleOutgoing(messageId, state, extraData)

    /**
     * Settles a send that stopped before its own end by the progress recorded on its row: SENT
     * if every package the peer needs had gone out, FAILED for [failure] otherwise. Returns the
     * state written, or null when the row had already settled.
     */
    suspend fun settleInterruptedSend(
        messageId: Long,
        failure: FileReceiver.FileFailure,
    ): MessageState? = messages.settleInterruptedSend(messageId, failure)

    /**
     * Settles an incoming transfer's in-flight row as RECEIVED, filling in the [path] the
     * file landed at and its [fileSummary]. Returns false when the row had already
     * settled and the write was refused.
     */
    suspend fun markIncomingTransferReceived(
        messageId: Long,
        path: String,
        fileSummary: FileSummary?,
    ): Boolean = messages.markIncomingTransferReceived(messageId, path, fileSummary)

    /**
     * Settles an incoming transfer's in-flight row as FAILED, recording [failure] and
     * clearing the path. Returns false when the row had already settled and the write
     * was refused.
     */
    suspend fun markIncomingTransferFailed(
        messageId: Long,
        failure: FileReceiver.FileFailure,
    ): Boolean = messages.markIncomingTransferFailed(messageId, failure)

    /**
     * Removes an in-flight row for a transfer that was abandoned because its sender
     * restarted it. Returns false when the row had already settled, in which case it is
     * history and stays.
     */
    suspend fun deleteInFlightMessage(messageId: Long): Boolean =
        messages.deleteInFlightMessage(messageId)

    /**
     * Settles transfers the last run of the app left in flight, and returns how many.
     * Run once per process, at startup, by [com.commcrete.stardust.room.RepositoryProvider].
     *
     * [asOfMs] is when this process built the repository — NOT when the sweep happens to
     * run, which may be minutes later if the legacy migration is still going. Anything
     * stamped after it belongs to something running now. [STALE_IN_FLIGHT_GRACE_MS] is
     * subtracted on top, because the very first received packet of this run is what builds
     * the repository, so a live transfer's row can be stamped a moment before [asOfMs].
     */
    suspend fun sweepStaleInFlight(asOfMs: Long = System.currentTimeMillis()): Int =
        messages.sweepStaleInFlight(asOfMs - STALE_IN_FLIGHT_GRACE_MS)

    /**
     * Records a user-cancelled outgoing transfer on its message row: state CANCELLED
     * plus [cancellation] — which says how far the send had got, and so whether the
     * receiver could still end up with the file. Returns false when the row had already
     * settled and the cancel was refused.
     */
    suspend fun markFileSendCancelled(
        messageId: Long,
        cancellation: FileTransferCancellation,
    ): Boolean = messages.markFileSendCancelled(messageId, cancellation)

    suspend fun clearChatMessages(chatId: String) = messages.clearChatMessages(chatId)

    suspend fun clearChatMessagesInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
    ) = messages.clearChatMessagesInRange(chatId, startTimestamp, endTimestamp)

    suspend fun archiveMessage(messageId: Int): Boolean = messages.archiveMessage(messageId)

    suspend fun archiveMessages(messageIds: Collection<Int>): Int =
        messages.archiveMessages(messageIds)

    /** Archives every settled message in [chatId]; in-flight ones are left to settle. */
    suspend fun archiveChatMessages(chatId: String): Int = messages.archiveChatMessages(chatId)

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

    /**
     * Re-points message rows at media that `StardustStorage` moved into the
     * media subtree on this or an earlier launch, and returns how many rows
     * were rewritten. Idempotent — see
     * [com.commcrete.stardust.room.new_db.internal.MessagesRepository.rewriteRelocatedMediaPaths].
     */
    suspend fun rewriteRelocatedMediaPaths(): Int = messages.rewriteRelocatedMediaPaths()

    companion object {
        private const val TAG = "AppRepository"

        const val PAGE_SIZE = 30

        /** Default retention for `contact_identity_log`: 90 days. */
        const val IDENTITY_LOG_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        /**
         * How far back of "in flight" the startup sweep leaves alone. Covers the transfer
         * whose own first packet built the repository — its row is stamped within
         * milliseconds of the timestamp the sweep measures from, and it is very much alive.
         */
        const val STALE_IN_FLIGHT_GRACE_MS = 60_000L
    }
}