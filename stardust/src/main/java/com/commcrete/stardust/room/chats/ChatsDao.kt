package com.commcrete.stardust.room.chats

import androidx.lifecycle.LiveData
import androidx.room.*
import com.commcrete.stardust.room.chats.ChatItem

@Dao
interface ChatsDao {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addChat(chatItem: ChatItem)

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addChats(chatItems: List<ChatItem>)


    @Query("SELECT * FROM chats_table ORDER BY epochTimeMs DESC")
    fun getAllChats() : LiveData<List<ChatItem>>

    @Query("SELECT * FROM chats_table ORDER BY epochTimeMs DESC")
    fun readChats() : List<ChatItem>
    @Query("SELECT * FROM chats_table WHERE chat_id=:chatId LIMIT 1")
    fun getChat(chatId: String) : LiveData<ChatItem>

    @Query("UPDATE chats_table SET audio_received=:isAudioReceived WHERE  chat_id=:chatId")
    suspend fun updateChatAudioReceived(chatId: String, isAudioReceived : Boolean)

    @Query("UPDATE chats_table SET chat_name=:name WHERE  chat_id=:chatId")
    suspend fun updateChatName(chatId: String, name : String)

    @Query("UPDATE chats_table SET displayName=:name WHERE  chat_id=:chatId")
    suspend fun updateDisplayName(chatId: String, name : String)

    @Query("UPDATE chats_table SET numOfUnseenMessages=:numOfUnsentMessages WHERE  chat_id=:chatId")
    suspend fun updateNumOfUnseenMessages(chatId: String, numOfUnsentMessages: Int)
    @Query("UPDATE chats_table SET enable_background_ptt=:enableBackgroundPtt WHERE  chat_id=:chatId")
    suspend fun updateChatBackgroundPttEnable(chatId: String, enableBackgroundPtt : Boolean)

//    @Query("UPDATE chats_table SET online=:isOnline WHERE  chat_id=:chatId")
//    suspend fun updateOnlineStatus(chatId: String, isOnline : Boolean)


//    @Query("UPDATE chats_table SET pttEnabled=:isPTTEnable WHERE  chat_id=:chatId")
//    suspend fun updateEnablePtt(chatId: String, isPTTEnable : Boolean)

    @Query("SELECT * FROM chats_table WHERE appId LIKE '%'||:bittelID||'%' LIMIT 1")
    fun getChatContactByBittelID(bittelID : String) : ChatItem?

    @Query("DELETE FROM chats_table")
    fun clearData()

    @Query("DELETE FROM chats_table WHERE chat_id=:chatId")
    fun deleteUser(chatId: String)
}