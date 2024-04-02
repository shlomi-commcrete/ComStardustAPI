package com.commcrete.stardust.room.sos_messages

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.commcrete.stardust.room.sos_messages.SOSMessageItem

@Dao
interface SOSMessagesDao {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addMessage(messageItem: SOSMessageItem)

    @Update
    suspend fun updateMessage(messageItem: SOSMessageItem)

    @Delete
    suspend fun removeMessage(messageItem: SOSMessageItem)

    @Query("SELECT * FROM sos_messages_table ORDER BY id ASC")
    fun getAllMessages() : LiveData<MutableList<SOSMessageItem>>

}