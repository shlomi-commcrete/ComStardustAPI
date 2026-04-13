package com.commcrete.stardust.room.legacy_db.contacts

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactsDao {

    @Query("SELECT * FROM contacts ORDER BY contactId ASC")
    fun getAllContact(): Flow<List<ChatContact>>

    @Query("DELETE FROM chats")
    fun clearData()

}