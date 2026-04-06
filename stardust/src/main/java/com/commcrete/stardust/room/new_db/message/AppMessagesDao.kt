package com.commcrete.stardust.room.new_db.message

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus

// SeenStatus int values: SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5

data class MessageSenderContact(
    val senderId: String,
    val contactId: Int?,
    val senderName: String?,
)

@Dao
interface AppMessagesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessage(messageItem: AppMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessages(messageItems: List<AppMessageEntity>): List<Long>

    @Query("SELECT * FROM new_messages_table ORDER BY id ASC")
    fun getAllMessages(): MutableList<MessageItem>

    @Update
    suspend fun updateMessage(messageItem: AppMessageEntity)

    @Query("""
        SELECT * FROM new_messages_table
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND state != 5
        ORDER BY epoch_time_ms ASC
    """)
    fun getAllMessagesByChatId(chatId: String): LiveData<MutableList<MessageItem>>

    @Query("""
        SELECT * FROM new_messages_table
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND state != 5
          AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epoch_time_ms ASC
    """)
    suspend fun getAllMessagesByChatId(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): List<MessageItem>

    @Query("""
        SELECT * FROM new_messages_table
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND state != 5
          AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epoch_time_ms DESC
        LIMIT :limit
    """)
    suspend fun getMessagesByChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): List<MessageItem>

    @Query("""
        SELECT * FROM new_messages_table
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND state != 5
          AND type = 'AUDIO'
        ORDER BY epoch_time_ms ASC
    """)
    fun getAllMessagesByChatIdPTT(chatId: String): LiveData<MutableList<MessageItem>>

    @Query("""
        SELECT * FROM new_messages_table
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND state != 5
          AND type = 'AUDIO'
          AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epoch_time_ms DESC
        LIMIT :limit
    """)
    suspend fun getPTTMessagesForChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): List<MessageItem>

    @Query("""
        SELECT * FROM new_messages_table
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND state != 5
          AND type = 'AUDIO'
        ORDER BY epoch_time_ms ASC
        LIMIT 1
    """)
    fun getLastPttMessage(chatId: String): AppMessageEntity?

    @Query("DELETE FROM new_messages_table WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    fun clearChat(chatId: String)

    @Query("""
        DELETE FROM new_messages_table
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND epoch_time_ms >= :startTimestamp
          AND epoch_time_ms < :endTimestamp
    """)
    suspend fun clearChatInRange(chatId: String, startTimestamp: Long, endTimestamp: Long)

    @Query("""
        UPDATE new_messages_table
        SET state = :isSeen
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
    """)
    suspend fun updateSeenMessages(chatId: String, isSeen: SeenStatus = SeenStatus.SEEN)

    /**
     * Marks every RECEIVED (2) and RECEIVING (4) message in [chatId] as SEEN (1).
     * Call when the user opens a chat.
     * Every [ChatSummaryDao.getAllChats] observer re-emits with unseenCount = 0
     * for that chat automatically.
     */
    @Query("""
        UPDATE new_messages_table
        SET state = 1
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND state IN (2, 4)
    """)
    suspend fun markAllAsSeen(chatId: String)

    /** Archive all messages in a time range by setting state = ARCHIVED (5). */
    @Query("""
        UPDATE new_messages_table
        SET state = 5
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
    """)
    suspend fun archiveMessages(chatId: String?, startTimestamp: Long, endTimestamp: Long)

    @Query("""
        UPDATE new_messages_table
        SET state = :ackState
        WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
          AND id_number = :messageNumber
    """)
    suspend fun updateAckReceived(
        chatId: String,
        messageNumber: Long,
        ackState: SeenStatus = SeenStatus.RECEIVED
    )

    @Query("DELETE FROM new_messages_table")
    fun clearData()

    /**
     * Resolve message sender to contact by checking both user_id and device_id.
     * Returns contact_id if sender is found in either identity table.
     */
    @Query(
        """
        SELECT COALESCE(u.contact_id, d.contact_id) AS contactId
        FROM new_messages_table m
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
     * Resolve message sender to full contact with sender name.
     * Returns sender identity (user_id or device_id) and resolved contact_id.
     */
    @Query(
        """
        SELECT
            m.sender_id AS senderId,
            COALESCE(u.contact_id, d.contact_id) AS contactId,
            m.sender_name AS senderName
        FROM new_messages_table m
        LEFT JOIN app_contact_user_ids u
               ON TRIM(LOWER(u.user_id)) = TRIM(LOWER(m.sender_id))
        LEFT JOIN app_contact_devices d
               ON TRIM(LOWER(d.device_id)) = TRIM(LOWER(m.sender_id))
        WHERE m.id = :messageId
        LIMIT 1
        """
    )
    suspend fun getMessageSenderInfo(messageId: Int): MessageSenderContact?

    /**
     * Find all messages from a specific contact (by either user_id or device_id).
     */
    @Query(
        """
        SELECT m.*
        FROM new_messages_table m
        WHERE TRIM(LOWER(m.sender_id)) IN (
            SELECT TRIM(LOWER(u.user_id))
            FROM app_contact_user_ids u
            WHERE u.contact_id = :contactId
            UNION
            SELECT TRIM(LOWER(d.device_id))
            FROM app_contact_devices d
            WHERE d.contact_id = :contactId
        )
        ORDER BY m.epoch_time_ms DESC
        """
    )
    suspend fun getMessagesBySender(contactId: Int): List<MessageItem>
}
