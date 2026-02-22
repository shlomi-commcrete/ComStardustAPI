package com.commcrete.stardust.room.contacts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.commcrete.stardust.room.contacts.ChatContact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addContact(chatContact: ChatContact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAllContacts(chatContact: List<ChatContact>)

    @Query("SELECT * FROM contacts_table ORDER BY contactId ASC")
    fun getAllContact(): Flow<List<ChatContact>>

    @Query("""
      SELECT display_name
      FROM contacts_table
      WHERE LOWER(chat_user_id) = LOWER(:userId)
      LIMIT 1
    """)
    fun getUserName(userId: String): String

    @Query("""
      SELECT *
      FROM contacts_table
      WHERE LOWER(chat_user_id) = LOWER(:userId)
      LIMIT 1
    """)
    fun getChatContactById(userId: String): ChatContact

    // if you ever need phone matching to ignore letter‐case (e.g. country code “+” vs “＋”)
    @Query("""
      SELECT *
      FROM contacts_table
      WHERE number = :phone COLLATE NOCASE
      LIMIT 1
    """)
    fun getChatContactByPhone(phone: String): ChatContact

    @Query("""
      SELECT *
      FROM contacts_table
      WHERE LOWER(bittel_id) = LOWER(:bittelID)
      LIMIT 1
    """)
    fun getChatContactByBittelID(bittelID: String): ChatContact?

    @Query("""
      SELECT *
      FROM contacts_table
      WHERE LOWER(smartphone_bittel_id) = LOWER(:bittelID) COLLATE NOCASE
      LIMIT 1
    """)
    fun getChatContactByAppBittelID(bittelID: String): ChatContact?

    @Query("""
      SELECT *
      FROM contacts_table
      WHERE bittel_id LIKE '%' || :userId || '%' COLLATE NOCASE
      LIMIT 1
    """)
    fun getChatContactByBittelId(userId: String): ChatContact

    @Query("UPDATE contacts_table SET display_name = :name WHERE LOWER(chat_user_id) = LOWER(:chatId)")
    suspend fun updateChatName(chatId: String, name: String)

    @Query("DELETE FROM contacts_table")
    fun clearData()

    @Query("DELETE FROM contacts_table WHERE LOWER(chat_user_id) = LOWER(:userId)")
    fun deleteContact(userId: String)
}