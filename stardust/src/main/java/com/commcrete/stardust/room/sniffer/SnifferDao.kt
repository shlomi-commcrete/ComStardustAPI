package com.commcrete.bittell.room.sniffer

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SnifferDao {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addMessage(messageItem: SnifferItem)

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addMessages(messageItems: List<SnifferItem>)

    @Query("SELECT * FROM sniffer_table ORDER BY id ASC")
    fun getAllMessages() : MutableList<SnifferItem>

    @Update
    suspend fun updateMessage(messageItem: SnifferItem)

    @Query("SELECT * FROM sniffer_table WHERE chatId COLLATE NOCASE = :chatId  ORDER BY epochTimeMs ASC")
    fun getAllMessagesByChatId(chatId : String) : LiveData<MutableList<SnifferItem>>


    @Query("SELECT * FROM sniffer_table WHERE chatId COLLATE NOCASE = :chatId AND is_audio = 1 ORDER BY epochTimeMs ASC")
    fun getAllMessagesByChatIdPTT(chatId : String) : LiveData<MutableList<SnifferItem>>

    @Query("SELECT * FROM sniffer_table WHERE chatId=:chatId AND is_audio=1 ORDER BY epochTimeMs ASC LIMIT 1")
    fun getLastPttMessage(chatId : String) : SnifferItem?

    @Query("DELETE FROM sniffer_table WHERE chatId COLLATE NOCASE = :chatId")
    fun clearChat(chatId : String)


    @Query("DELETE FROM sniffer_table")
    fun clearData()

}