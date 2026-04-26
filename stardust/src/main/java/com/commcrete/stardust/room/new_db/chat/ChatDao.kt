package com.commcrete.stardust.room.new_db.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    /** (previousId -> resolved chatId) row produced by bulk previous-id lookup. */
    data class PreviousChatIdRow(
        @ColumnInfo(name = "previous_id") val previousId: String,
        @ColumnInfo(name = "chat_id") val chatId: String,
    )

    // ── Single chat ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChats(chats: List<ChatEntity>): List<Long>

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Upsert
    suspend fun upsertChat(chat: ChatEntity): Long

    @Upsert
    suspend fun upsertChats(chats: List<ChatEntity>)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String): Int

    // ── Chat + participants (transactional) ──────────────────────────────

    @Transaction
    suspend fun insertChatWithParticipants(chat: ChatEntity, participantIds: List<Int>) {
        upsertChat(chat)
        replaceParticipants(chat.id, participantIds)
    }

    @Transaction
    suspend fun insertChatsWithParticipants(chats: List<Pair<ChatEntity, List<Int>>>) {
        chats.forEach { (chat, participantIds) ->
            insertChatWithParticipants(chat, participantIds)
        }
    }

    // ── Participants ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addParticipant(ref: ChatParticipantEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addParticipants(refs: List<ChatParticipantEntity>)

    @Query("DELETE FROM chat_participants WHERE chat_id = :chatId")
    suspend fun clearParticipants(chatId: String)

    @Transaction
    suspend fun replaceParticipants(chatId: String, participantIds: List<Int>) {
        clearParticipants(chatId)
        if (participantIds.isNotEmpty()) {
            addParticipants(
                participantIds
                    .distinct()
                    .map { participantId ->
                        ChatParticipantEntity(chatId = chatId, contactId = participantId)
                    }
            )
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM chat_summary ORDER BY lastMessageEpochMs DESC")
    fun getAllChatsSummaries(): Flow<List<ChatSummary>>

    /** Single-chat summary — useful for a header while the user is inside a chat. */
    @Query("SELECT * FROM chat_summary WHERE chatId = :chatId LIMIT 1")
    fun getChatSummary(chatId: String): Flow<ChatSummary?>

    @Query("SELECT id FROM chats WHERE type = 'GROUP'")
    suspend fun getAllGroupChatIds(): List<String>

    @Query("""
        SELECT c.id FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE c.type = 'PRIVATE' AND cp.contact_id = :contactId
        LIMIT 1
    """)
    suspend fun findPrivateChatIdByContactId(contactId: Int): String?

    @Query("""
        SELECT c.id FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE c.type = 'GROUP' AND cp.contact_id = :contactId
        LIMIT 1
    """)
    suspend fun findGroupChatIdByContactId(contactId: Int): String?


    @Query("""
        SELECT c.id
        FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE (
            c.type = 'GROUP'
            AND cp.contact_id IN (
                SELECT cg.contact_id
                FROM app_contact_group_ids cg
                WHERE TRIM(LOWER(cg.group_id)) = TRIM(LOWER(:previousChatId))
            )
        )
        OR (
            c.type = 'PRIVATE'
            AND cp.contact_id IN (
                SELECT cu.contact_id
                FROM app_contact_user_ids cu
                WHERE TRIM(LOWER(cu.user_id)) = TRIM(LOWER(:previousChatId))
            )
        )
        OR (
            c.type = 'PRIVATE'
            AND cp.contact_id IN (
                SELECT cd.contact_id
                FROM app_contact_devices cd
                WHERE TRIM(LOWER(cd.device_id)) = TRIM(LOWER(:previousChatId))
            )
        )
        ORDER BY c.last_updated_ms DESC
        LIMIT 1
    """)
    suspend fun findNewChatIdByPreviousChatId(previousChatId: String): String?

    /**
     * Bulk version of [findNewChatIdByPreviousChatId]. Resolves multiple legacy
     * IDs in one query. Caller maps each input position back via the returned rows.
     * Lower-cased / trimmed IDs are expected (both inputs and stored data).
     */
    @Query("""
        SELECT prev.previous_id AS previous_id, prev.chat_id AS chat_id
        FROM (
            SELECT TRIM(LOWER(cg.group_id)) AS previous_id, cp.chat_id AS chat_id, c.last_updated_ms AS lu
            FROM app_contact_group_ids cg
            JOIN chat_participants cp ON cp.contact_id = cg.contact_id
            JOIN chats c ON c.id = cp.chat_id AND c.type = 'GROUP'
            UNION ALL
            SELECT TRIM(LOWER(cu.user_id)), cp.chat_id, c.last_updated_ms
            FROM app_contact_user_ids cu
            JOIN chat_participants cp ON cp.contact_id = cu.contact_id
            JOIN chats c ON c.id = cp.chat_id AND c.type = 'PRIVATE'
            UNION ALL
            SELECT TRIM(LOWER(cd.device_id)), cp.chat_id, c.last_updated_ms
            FROM app_contact_devices cd
            JOIN chat_participants cp ON cp.contact_id = cd.contact_id
            JOIN chats c ON c.id = cp.chat_id AND c.type = 'PRIVATE'
        ) prev
        WHERE prev.previous_id IN (:previousChatIds)
        ORDER BY prev.lu DESC
    """)
    suspend fun findNewChatIdRowsByPreviousChatIds(previousChatIds: List<String>): List<PreviousChatIdRow>

    @Query("SELECT id FROM chats")
    suspend fun getAllChatIds(): List<String>

    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatWithParticipants(chatId: String): ChatWithParticipants?

    @Transaction
    @Query("SELECT * FROM chats ORDER BY last_updated_ms DESC")
    fun getAllChatsWithParticipants(): Flow<List<ChatWithParticipants>>
}
