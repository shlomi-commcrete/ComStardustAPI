package com.commcrete.stardust.room.new_db.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessageState
import kotlinx.coroutines.flow.Flow

// SeenStatus int values: SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessage(messageItem: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessages(messageItems: List<MessageEntity>): List<Long>

    @Update
    suspend fun updateMessage(messageItem: MessageEntity)

    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND state != 5
        ORDER BY epoch_time_ms ASC
    """)
    fun getAllMessagesByChatId(chatId: String): Flow<List<MessageItem>>

    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND sender_id = :senderId AND state != 5
        ORDER BY epoch_time_ms ASC
    """)
    fun getChatMessagesBySenderId(chatId: String, senderId: String): Flow<List<MessageItem>>


    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND state != 5 
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epoch_time_ms DESC
        LIMIT :limit
    """)
    suspend fun getMessagesByChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): Flow<List<MessageItem>>

    @Query("""
        SELECT * FROM messages
        WHERE sender_id = :senderId AND state != 5
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epoch_time_ms DESC
        LIMIT :limit
    """)
    suspend fun getMessagesBySenderInRange(
        senderId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): Flow<List<MessageItem>>

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    fun clearChatMessages(chatId: String)

    @Query("DELETE FROM messages WHERE sender_id = :senderId")
    fun clearSenderMessages(senderId: String)

    @Query("""
        DELETE FROM messages
        WHERE chat_id = :chatId
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
    """)
    suspend fun clearChatInRange(chatId: String, startTimestamp: Long, endTimestamp: Long)

    @Query("UPDATE messages SET state = :state WHERE chat_id = :chatId")
    suspend fun updateMessageState(chatId: String, state: MessageState)

    /**
     * Marks every RECEIVED (2) and RECEIVING (4) message in [chatId] as SEEN (1).
     * Call when the user opens a chat.
     * Every [ChatSummaryDao.getAllChats] observer re-emits with unseenCount = 0
     * for that chat automatically.
     */
    @Query("""
        UPDATE messages
        SET state = 1
        WHERE chat_id = :chatId AND state IN (2, 4)
    """)
    suspend fun markAllMessagesAsSeen(chatId: String)

    @Query("""
        UPDATE messages
        SET state = 1
        WHERE id = :messageId AND state IN (0, 4)
    """)
    suspend fun markMessageAsSeen(messageId: String)

    /** Archive all messages in a time range by setting state = ARCHIVED (5). */
    @Query("""
        UPDATE messages
        SET state = 5
        WHERE chat_id = :chatId
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
    """)
    suspend fun archiveMessagesInRange(chatId: String?, startTimestamp: Long, endTimestamp: Long)

    @Query("""
        UPDATE messages
        SET state = :ackState
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND id_number = :messageNumber
    """)
    suspend fun updateAckReceived(
        chatId: String,
        messageNumber: Long,
        ackState: MessageState = MessageState.RECEIVED
    )

    @Query("DELETE FROM messages")
    fun clearData()

    // ── Live feed ─────────────────────────────────────────────────────────

    /**
     * Emits the [limit] most-recent non-archived messages in [chatId],
     * ordered chronologically (oldest first). Re-emits on every insert/update/delete
     * in the messages table — use for both group and private chats.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    fun getLatestMessages(chatId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * Same as [getLatestMessages] but only includes messages where [participantId]
     * is the sender OR receiver. Use for the filtered view in private chats.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (sender_id = :participantId OR receiver_id = :participantId)
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    fun getLatestMessagesByParticipant(
        chatId: String,
        participantId: String,
        limit: Int,
    ): Flow<List<MessageEntity>>

    // ── Pagination ────────────────────────────────────────────────────────

    /**
     * Returns the [limit] messages that come just before [beforeEpochMs],
     * ordered chronologically. Call when the user scrolls to the top of the
     * chat to prepend the next page to the existing list.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND epoch_time_ms < :beforeEpochMs
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    suspend fun loadOlderMessages(
        chatId: String,
        beforeEpochMs: Long,
        limit: Int,
    ): List<MessageEntity>

    /**
     * Same as [loadOlderMessages] but scoped to [participantId] as sender or receiver.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (sender_id = :participantId OR receiver_id = :participantId)
              AND epoch_time_ms < :beforeEpochMs
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    suspend fun loadOlderMessagesByParticipant(
        chatId: String,
        participantId: String,
        beforeEpochMs: Long,
        limit: Int,
    ): List<MessageEntity>

    /**
     * Resolve message sender to contact by checking both user_id and device_id.
     * Returns contact_id if sender is found in either identity table.
     */
    @Query(
        """
        SELECT COALESCE(u.contact_id, d.contact_id) AS contactId
        FROM messages m
        LEFT JOIN app_contact_user_ids u
               ON TRIM(LOWER(u.user_id)) = TRIM(LOWER(m.sender_id))
        LEFT JOIN app_contact_devices d
               ON TRIM(LOWER(d.device_id)) = TRIM(LOWER(m.sender_id))
        WHERE m.id = :messageId
          AND (u.contact_id IS NOT NULL OR d.contact_id IS NOT NULL)
        LIMIT 1
        """
    )
    suspend fun getMessageSenderContactId(messageId: Int): Int?


    /**
     * Find all messages from a specific contact (by either user_id or device_id).
     */
    @Query(
        """
        SELECT m.*
        FROM messages m
        WHERE sender_id IN (
            SELECT u.user_id
            FROM app_contact_user_ids u
            WHERE u.contact_id = :contactId
            UNION
            SELECT d.device_id
            FROM app_contact_devices d
            WHERE d.contact_id = :contactId
        )
        ORDER BY m.epoch_time_ms DESC
        """
    )
    suspend fun getMessagesBySender(contactId: Int): List<MessageItem>
}
