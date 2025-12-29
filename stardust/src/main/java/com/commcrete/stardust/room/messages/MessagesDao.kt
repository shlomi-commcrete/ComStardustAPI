package com.commcrete.stardust.room.messages

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.commcrete.stardust.room.messages.MessageItem
import java.sql.Timestamp

@Dao
interface MessagesDao {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addMessage(messageItem: MessageItem) : Long

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addMessages(messageItems: List<MessageItem>) : List<Long>

    @Query("SELECT * FROM messages_table ORDER BY id ASC")
    fun getAllMessages() : MutableList<MessageItem>

    @Update
    suspend fun updateMessage(messageItem: MessageItem)

    @Query("SELECT * FROM messages_table WHERE chatId COLLATE NOCASE = :chatId  ORDER BY epochTimeMs ASC")
    fun getAllMessagesByChatId(chatId : String) : LiveData<MutableList<MessageItem>>

    @Query("SELECT * FROM messages_table WHERE chatId COLLATE NOCASE = :chatId AND epochTimeMs >= :startTimestamp AND epochTimeMs < :endTimestamp ORDER BY epochTimeMs ASC ")
    suspend fun getAllMessagesByChatId(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): List<MessageItem>

    @Query("SELECT * FROM messages_table WHERE chatId COLLATE NOCASE = :chatId AND epochTimeMs >= :startTimestamp AND epochTimeMs < :endTimestamp ORDER BY epochTimeMs DESC LIMIT :limit")
    suspend fun getMessagesByChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): List<MessageItem>

    @Query("SELECT * FROM messages_table WHERE chatId COLLATE NOCASE = :chatId AND is_audio = 1 ORDER BY epochTimeMs ASC")
    fun getAllMessagesByChatIdPTT(chatId : String) : LiveData<MutableList<MessageItem>>

    @Query("SELECT * FROM messages_table WHERE chatId COLLATE NOCASE = :chatId AND is_audio = 1 AND epochTimeMs >= :startTimestamp AND epochTimeMs < :endTimestamp ORDER BY epochTimeMs DESC LIMIT :limit")
    suspend fun getPTTMessagesForChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): List<MessageItem>

    @Query("SELECT * FROM messages_table WHERE chatId=:chatId AND is_audio=1 ORDER BY epochTimeMs ASC LIMIT 1")
    fun getLastPttMessage(chatId : String) : MessageItem?

    @Query("DELETE FROM messages_table WHERE chatId COLLATE NOCASE = :chatId")
    fun clearChat(chatId : String)

    @Query("DELETE FROM messages_table WHERE chatId COLLATE NOCASE = :chatId AND epochTimeMs >= :startTimestamp AND epochTimeMs < :endTimestamp")
    suspend fun clearChatInRange(chatId : String,
                         startTimestamp: Long,
                         endTimestamp: Long)

    @Query("UPDATE messages_table SET seen=:isSeen WHERE  chatId=:chatId")
    suspend fun updateSeenMessages(chatId: String, isSeen : Boolean = true)

    @Query("UPDATE messages_table SET seen=2 WHERE  chatId=:chatid AND id_number=:messageNumber")
    suspend fun updateAckReceived (chatid: String, messageNumber: Long)

    @Query("DELETE FROM messages_table")
    fun clearData()

}