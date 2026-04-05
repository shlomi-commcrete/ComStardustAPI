package com.commcrete.stardust.room.new_db.contact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.commcrete.stardust.room.contacts.ChatContact
import kotlinx.coroutines.flow.Flow

@Dao
interface AppContactsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addContact(chatContact: AppContactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAllContacts(chatContact: List<AppContactEntity>)

    @Query("SELECT * FROM new_contacts_table ORDER BY contactId ASC")
    fun getAllContact(): Flow<List<ChatContact>>

    @Query("SELECT * FROM new_contacts_table ORDER BY contactId ASC")
    suspend fun readAllContacts(): List<ChatContact>

    @Query("""
      SELECT display_name
      FROM new_contacts_table
      WHERE TRIM(LOWER(chat_user_id)) = TRIM(LOWER(:userId))
      LIMIT 1
    """)
    fun getUserName(userId: String): String

    @Query("""
      SELECT *
      FROM new_contacts_table
      WHERE TRIM(LOWER(chat_user_id)) = TRIM(LOWER(:userId))
      LIMIT 1
    """)
    fun getChatContactById(userId: String): ChatContact

    @Query("""
      SELECT *
      FROM new_contacts_table
      WHERE number = :phone COLLATE NOCASE
      LIMIT 1
    """)
    fun getChatContactByPhone(phone: String): ChatContact

    @Query("""
      SELECT *
      FROM new_contacts_table
      WHERE TRIM(LOWER(bittel_id)) = TRIM(LOWER(:bittelID))
      LIMIT 1
    """)
    fun getChatContactByBittelID(bittelID: String): ChatContact?

    @Query("""
      SELECT *
      FROM new_contacts_table
      WHERE TRIM(LOWER(smartphone_bittel_id)) = TRIM(LOWER(:bittelID))
      LIMIT 1
    """)
    fun getChatContactByAppBittelID(bittelID: String): ChatContact?

    @Query("""
      SELECT *
      FROM new_contacts_table
      WHERE TRIM(LOWER(bittel_id)) = TRIM(LOWER(:userId))
      LIMIT 1
    """)
    fun getChatContactByBittelId(userId: String): ChatContact

    @Query("UPDATE new_contacts_table SET display_name = :name WHERE TRIM(LOWER(chat_user_id)) = TRIM(LOWER(:chatId))")
    suspend fun updateChatName(chatId: String, name: String)

    @Query("DELETE FROM new_contacts_table")
    fun clearData()

    @Query("DELETE FROM new_contacts_table WHERE TRIM(LOWER(chat_user_id)) = TRIM(LOWER(:userId))")
    fun deleteContact(userId: String)
}


