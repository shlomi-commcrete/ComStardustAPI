package com.commcrete.stardust.room.new_db.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

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

    // ── Chat + participants (transactional) ──────────────────────────────

    @Transaction
    suspend fun insertChatWithParticipants(chat: ChatEntity, participantIds: List<Int>) {
        val chatId = upsertChat(chat).toInt()
        replaceParticipants(chatId, participantIds)
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
    suspend fun clearParticipants(chatId: Int)

    @Transaction
    suspend fun replaceParticipants(chatId: Int, participantIds: List<Int>) {
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
    fun getChatSummary(chatId: Int): Flow<ChatSummary?>

    @Query("SELECT id FROM chats WHERE type = 'GROUP'")
    suspend fun getAllGroupChatIds(): List<Int>

    @Query("""
        SELECT c.id FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE c.type = 'PRIVATE' AND cp.contact_id = :contactId
        LIMIT 1
    """)
    suspend fun findPrivateChatIdByContactId(contactId: Int): Int?

    @Query("""
        SELECT c.id FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE c.type = 'GROUP' AND cp.contact_id = :contactId
        LIMIT 1
    """)
    suspend fun findGroupChatIdByContactId(contactId: Int): Int?

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatWithParticipants(chatId: Int): ChatWithParticipants?

    @Transaction
    @Query("SELECT * FROM chats ORDER BY last_updated_ms DESC")
    fun getAllChatsWithParticipants(): Flow<List<ChatWithParticipants>>
}
