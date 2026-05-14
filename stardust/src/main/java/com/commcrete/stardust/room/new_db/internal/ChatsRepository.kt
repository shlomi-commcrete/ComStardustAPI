package com.commcrete.stardust.room.new_db.internal

import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatParticipantEntity
import com.commcrete.stardust.room.new_db.chat.ChatSummary
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipants
import com.commcrete.stardust.room.new_db.chat.ChatWithParticipantsAsShortParticipantInfo
import com.commcrete.stardust.room.new_db.chat.ShortParticipantInfo
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.ContactsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Owns every chats-domain operation backed by [ChatDao]:
 *
 *  - **Reads** — chat lists and summaries
 *    ([getChatSummaries], [getChatIds], [getShortChatDataByChatId],
 *    [getChatWithParticipantsByChatId], [getChatWithParticipantsShortParticipantInfo],
 *    [observeAllChatsWithShortParticipantInfo]).
 *  - **Writes** — chat creation helpers shared with contacts-insertion paths
 *    ([createPrivateChat], [createGroupChat], [addToExistingGroupChats]) and
 *    chat deletion ([deleteChat]).
 *  - **Cross-DB lookups** — legacy → new chat-id resolution
 *    ([findNewChatIdByPreviousChatId], [findNewChatIdsByPreviousChatIds]).
 *
 * # Cross-domain dependencies
 *  - [contactsDao] is needed for participant-id projections and the bulk
 *    participant-rows observer used by [observeAllChatsWithShortParticipantInfo]
 *    and [getChatWithParticipantsShortParticipantInfo]. Read-only.
 *  - [caches] is needed only by [deleteChat] to drop received-package
 *    chatId entries that resolve to the deleted chat.
 *  - [withSaveLock] funnels [deleteChat] through the same mutex that guards
 *    [MessagesRepository.saveMessage], so a chat cannot be deleted while a
 *    save is mid-flight (which would orphan the message it is about to insert).
 */
internal class ChatsRepository(
    private val chatsDao: ChatDao,
    private val contactsDao: ContactsDao,
    private val caches: RepositoryCaches,
    private val withSaveLock: suspend (suspend () -> Boolean) -> Boolean,
) {

    // ─────────────────────────────────────────────────────────────────────
    // Reads
    // ─────────────────────────────────────────────────────────────────────
    suspend fun getChatByChatId(chatId: String): ChatEntity? =
        chatsDao.getChatByChatId(chatId)

    /** See `AppRepository.getChatSummaries`. */
    fun getChatSummaries(): Flow<List<ChatSummary>> = chatsDao.getAllChatsSummaries()

    /** See `AppRepository.getChatIds`. */
    suspend fun getChatIds(): List<String> = withContext(Dispatchers.IO) {
        chatsDao.getAllChatIds()
    }


    suspend fun getChatWithParticipantsByChatId(chatId: String): ChatWithParticipants? =
        withContext(Dispatchers.IO) {
            val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext null
            chatsDao.getChatWithParticipants(normalizedChatId)
        }

    /** See `AppRepository.getChatWithParticipantsShortParticipantInfo`. */
    suspend fun getChatWithParticipantsShortParticipantInfo(
        chatId: String,
    ): ChatWithParticipantsAsShortParticipantInfo? = withContext(Dispatchers.IO) {
        val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext null
        val chat = chatsDao.getChatByChatId(normalizedChatId) ?: return@withContext null
        val participantRows = contactsDao.getChatParticipantIdRows(normalizedChatId)
        val participants = mapParticipantIdRows(participantRows)[normalizedChatId].orEmpty()
        ChatWithParticipantsAsShortParticipantInfo(
            chat = chat,
            participants = participants,
        )
    }

    /** See `AppRepository.observeAllChatsWithShortParticipantInfo`. */
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

    /**
     * Builds [ShortParticipantInfo] entries directly from raw DAO rows, avoiding
     * any per-contact secondary queries. USER rows produce both userId + deviceId
     * entries; DEVICE/GROUP produce a single entry. De-dups at insert time so
     * the result map never needs a second pass.
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
            if (list.none { it.id == row.participantId && it.type == type }) {
                list += ShortParticipantInfo(id = row.participantId, type = type)
            }
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────
    // Writes — creation helpers (shared with contacts-insertion paths)
    // ─────────────────────────────────────────────────────────────────────

    /** Inserts a PRIVATE chat owned by [contactId], named after [contact]. */
    suspend fun createPrivateChat(contact: ContactEntity, contactId: Int) {
        chatsDao.insertChatWithParticipants(
            ChatEntity(name = contact.name, image = contact.image, type = ChatType.PRIVATE),
            listOf(contactId),
        )
    }

    /**
     * Inserts a GROUP chat for [groupContactId], populating it with
     * [memberIds] (de-duplicated together with [groupContactId]).
     */
    suspend fun createGroupChat(
        contact: ContactEntity,
        groupContactId: Int,
        memberIds: List<Int>,
    ) {
        chatsDao.insertChatWithParticipants(
            ChatEntity(name = contact.name, image = contact.image, type = ChatType.GROUP),
            (memberIds + groupContactId).distinct(),
        )
    }

    /**
     * Adds [contactId] as a participant to every chat in [groupChatIds].
     * No-op when [groupChatIds] is empty.
     */
    suspend fun addToExistingGroupChats(contactId: Int, groupChatIds: List<String>) {
        if (groupChatIds.isEmpty()) return
        chatsDao.addParticipants(
            groupChatIds.map { chatId -> ChatParticipantEntity(chatId = chatId, contactId = contactId) },
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Deletion
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Deletes a chat by ID under the shared save-lock so it cannot race with
     * an in-flight [MessagesRepository.saveMessage]. Related messages and
     * participants are removed by FK cascade. Returns true when a row was
     * actually deleted.
     */
    suspend fun deleteChat(chatId: String): Boolean = withSaveLock {
        withContext(Dispatchers.IO) {
            val normalizedChatId = normalizeIdOrNull(chatId) ?: return@withContext false
            val deletedRows = chatsDao.deleteChatById(normalizedChatId)
            if (deletedRows > 0) {
                caches.removeChatIdsMappedTo(normalizedChatId)
            }
            deletedRows > 0
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Legacy → new chat-id resolution
    // ─────────────────────────────────────────────────────────────────────

    /** See `AppRepository.findNewChatIdByPreviousChatId`. */
    suspend fun findNewChatIdByPreviousChatId(previousChatId: String): String? =
        withContext(Dispatchers.IO) {
            chatsDao.findNewChatIdByPreviousChatId(previousChatId.trim().lowercase())
        }

    /**
     * Resolves a list of previous chat IDs to new chat IDs, preserving input
     * order and de-duplicating DB calls. See `AppRepository.findNewChatIdsByPreviousChatIds`.
     */
    suspend fun findNewChatIdsByPreviousChatIds(previousChatIds: List<String>): List<String?> =
        withContext(Dispatchers.IO) {
            if (previousChatIds.isEmpty()) return@withContext emptyList()

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
}

