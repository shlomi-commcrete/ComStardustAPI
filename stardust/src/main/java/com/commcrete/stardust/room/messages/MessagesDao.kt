package com.commcrete.stardust.room.messages

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.commcrete.stardust.room.messages.MessageItem

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

    @Query("SELECT * FROM messages_table WHERE chatId COLLATE NOCASE = :chatId AND is_audio = 0 ORDER BY epochTimeMs ASC")
    fun getAllMessagesByChatId(chatId : String) : LiveData<MutableList<MessageItem>>


    @Query("SELECT * FROM messages_table WHERE chatId COLLATE NOCASE = :chatId AND is_audio = 1 ORDER BY epochTimeMs ASC")
    fun getAllMessagesByChatIdPTT(chatId : String) : LiveData<MutableList<MessageItem>>

    @Query("SELECT * FROM messages_table WHERE chatId=:chatId AND is_audio=1 ORDER BY epochTimeMs ASC LIMIT 1")
    fun getLastPttMessage(chatId : String) : MessageItem?

    @Query("DELETE FROM messages_table WHERE chatId COLLATE NOCASE = :chatId")
    fun clearChat(chatId : String)

    @Query("UPDATE messages_table SET seen=:isSeen WHERE  chatId=:chatId")
    suspend fun updateSeenMessages(chatId: String, isSeen : Boolean = true)

    @Query("UPDATE messages_table SET seen=2 WHERE  chatId=:chatid AND id_number=:messageNumber")
    suspend fun updateAckReceived (chatid: String, messageNumber: Long)

    @Query("DELETE FROM messages_table")
    fun clearData()

}