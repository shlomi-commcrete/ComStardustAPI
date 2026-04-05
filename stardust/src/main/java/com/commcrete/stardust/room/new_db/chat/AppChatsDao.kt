package com.commcrete.stardust.room.new_db.chat

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.commcrete.stardust.room.chats.ChatItem

@Dao
interface AppChatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addChat(chatItem: AppChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addChats(chatItems: List<AppChatEntity>)

    @Query("SELECT * FROM new_chats_table ORDER BY epochTimeMs DESC")
    fun getAllChats(): LiveData<List<ChatItem>>

    @Query("SELECT * FROM new_chats_table WHERE is_bittel=0 ORDER BY epochTimeMs DESC")
    suspend fun getAllAppChats(): List<ChatItem>

    @Query("SELECT chat_id FROM new_chats_table")
    fun getAllChatsIds(): List<String>

    @Query("SELECT * FROM new_chats_table ORDER BY epochTimeMs DESC")
    fun readChats(): List<ChatItem>

    @Query("""
      SELECT *
      FROM new_chats_table
      WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))
      LIMIT 1
    """)
    fun getChat(chatId: String): LiveData<ChatItem>

    @Query("UPDATE new_chats_table SET audio_received=:isAudioReceived WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    suspend fun updateChatAudioReceived(chatId: String, isAudioReceived: Boolean)

    @Query("SELECT chat_name FROM new_chats_table WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    suspend fun getChatName(chatId: String): String?

    @Query("UPDATE new_chats_table SET chat_name=:name WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    suspend fun updateChatName(chatId: String, name: String)

    @Query("UPDATE new_chats_table SET displayName=:name WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    suspend fun updateDisplayName(chatId: String, name: String)

    @Query("UPDATE new_chats_table SET numOfUnseenMessages=:numOfUnsentMessages WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    suspend fun updateNumOfUnseenMessages(chatId: String, numOfUnsentMessages: Int)

    @Query("UPDATE new_chats_table SET enable_background_ptt=:enableBackgroundPtt WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    suspend fun updateChatBackgroundPttEnable(chatId: String, enableBackgroundPtt: Boolean)

    @Query("""
        UPDATE new_chats_table
        SET last_message_id = '',
            audio_received = 0,
            numOfUnseenMessages = 0,
            senderID = NULL,
            text = NULL,
            epochTimeMs = NULL,
            seen = NULL
           """)
    suspend fun resetChatsMessages()

    @Query("SELECT chat_id FROM new_chats_table WHERE is_group = 1")
    suspend fun getAllGroupIds(): List<String>

    @Query("""
      SELECT *
        FROM new_chats_table
       WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:bittelID))
       LIMIT 1
    """)
    suspend fun getChatContactByBittelID(bittelID: String): ChatItem?

    @Query("DELETE FROM new_chats_table")
    suspend fun clearData()

    @Query("DELETE FROM new_chats_table WHERE TRIM(LOWER(chat_id)) = TRIM(LOWER(:chatId))")
    suspend fun deleteUser(chatId: String)
}


