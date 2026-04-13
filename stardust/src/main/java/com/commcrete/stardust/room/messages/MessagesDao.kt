package com.commcrete.stardust.room.messages


import androidx.room.Dao
import androidx.room.Query

@Dao
interface MessagesDao {

    @Query("SELECT * FROM messages_table ORDER BY id ASC")
    fun getAllMessages() : MutableList<MessageItem>

    @Query("DELETE FROM messages_table")
    fun clearData()

}