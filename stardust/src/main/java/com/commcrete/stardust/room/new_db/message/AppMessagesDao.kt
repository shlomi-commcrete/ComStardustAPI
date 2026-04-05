package com.commcrete.stardust.room.new_db.message

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.commcrete.stardust.room.messages.MessageItem

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

    @Query("SELECT * FROM new_messages_table WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId)) AND isArchived = 0 ORDER BY epochTimeMs ASC")
    fun getAllMessagesByChatId(chatId: String): LiveData<MutableList<MessageItem>>

    @Query("""
        SELECT *
        FROM new_messages_table
        WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))
        AND isArchived = 0
        AND epochTimeMs BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epochTimeMs ASC
    """)
    suspend fun getAllMessagesByChatId(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): List<MessageItem>

    @Query("""
        SELECT *
        FROM new_messages_table
        WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))
        AND isArchived = 0
        AND epochTimeMs BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epochTimeMs DESC
        LIMIT :limit
    """)
    suspend fun getMessagesByChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): List<MessageItem>

    @Query("""
        SELECT *
        FROM new_messages_table
        WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))
        AND isArchived = 0
        AND is_audio = 1
        ORDER BY epochTimeMs ASC
    """)
    fun getAllMessagesByChatIdPTT(chatId: String): LiveData<MutableList<MessageItem>>

    @Query("""
        SELECT *
        FROM new_messages_table
        WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))
        AND isArchived = 0
        AND is_audio = 1
        AND epochTimeMs BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epochTimeMs DESC LIMIT :limit
    """)
    suspend fun getPTTMessagesForChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): List<MessageItem>

    @Query("""
        SELECT *
        FROM new_messages_table
        WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))
        AND isArchived = 0
        AND is_audio = 1
        ORDER BY epochTimeMs ASC LIMIT 1
    """)
    fun getLastPttMessage(chatId: String): AppMessageEntity?

    @Query("DELETE FROM new_messages_table WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))")
    fun clearChat(chatId: String)

    @Query("DELETE FROM new_messages_table WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId)) AND epochTimeMs >= :startTimestamp AND epochTimeMs < :endTimestamp")
    suspend fun clearChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long
    )

    @Query("UPDATE new_messages_table SET seen=:isSeen WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))")
    suspend fun updateSeenMessages(chatId: String, isSeen: Boolean = true)

    @Query("""
        UPDATE new_messages_table
        SET isArchived=:isArchived
        WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId))
        AND epochTimeMs BETWEEN :startTimestamp AND :endTimestamp
    """)
    suspend fun updateMessagesArchivedState(
        chatId: String?,
        startTimestamp: Long,
        endTimestamp: Long,
        isArchived: Boolean = true
    )

    @Query("UPDATE new_messages_table SET seen=2 WHERE TRIM(LOWER(chatId)) = TRIM(LOWER(:chatId)) AND id_number=:messageNumber")
    suspend fun updateAckReceived(chatId: String, messageNumber: Long)

    @Query("DELETE FROM new_messages_table")
    fun clearData()
}


