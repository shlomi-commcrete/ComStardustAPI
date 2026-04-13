package com.commcrete.stardust.room.legacy_db.chats

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ChatsDao {
    @Query("SELECT * FROM chats_table ORDER BY epochTimeMs DESC")
    fun getAllChats() : LiveData<List<ChatItem>>

    @Query("DELETE FROM chats_table")
    suspend fun clearData()

}