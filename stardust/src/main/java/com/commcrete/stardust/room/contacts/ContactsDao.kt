package com.commcrete.stardust.room.contacts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.commcrete.stardust.room.contacts.ChatContact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactsDao {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addContact(chatContact: ChatContact)

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addAllContacts(chatContact: List<ChatContact>)

    @Query("SELECT * FROM contacts_table ORDER BY contactId ASC")
    fun getAllContact() : Flow<List<ChatContact>>


    @Query("SELECT display_name FROM contacts_table WHERE chat_user_id=:userId LIMIT 1")
    fun getUserName(userId : String) : String


    @Query("SELECT * FROM contacts_table WHERE chat_user_id=:userId LIMIT 1")
    fun getChatContactById(userId : String) : ChatContact

    @Query("SELECT * FROM contacts_table WHERE number=:phone LIMIT 1")
    fun getChatContactByPhone(phone : String) : ChatContact

    @Query("SELECT * FROM contacts_table WHERE bittel_id=:bittelID LIMIT 1")
    fun getChatContactByBittelID(bittelID : String) : ChatContact?

    @Query("SELECT * FROM contacts_table WHERE smartphone_bittel_id=:bittelID LIMIT 1")
    fun getChatContactByAppBittelID(bittelID : String) : ChatContact?
    @Query("SELECT * FROM contacts_table WHERE chat_user_id IN (:userIds)")
    fun getUserNames(userIds : List<String>) : List<ChatContact>

    @Query("SELECT * FROM contacts_table WHERE chat_user_id IN (:userIds)")
    fun getContactsById(userIds : List<String>) : List<ChatContact>

    @Query("UPDATE contacts_table SET display_name=:name WHERE  display_name=:chatId")
    suspend fun updateChatName(chatId: String, name : String)


    @Query("DELETE FROM contacts_table")
    fun clearData()

    @Query("DELETE FROM contacts_table WHERE chat_user_id=:userId")
    fun deleteContact(userId : String)
}