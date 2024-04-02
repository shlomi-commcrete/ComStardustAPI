package com.commcrete.stardust.room.contacts

import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.contacts.ContactsDao
import kotlinx.coroutines.flow.Flow

class ContactsRepository (private val contactsDao: ContactsDao) {

    fun  readAllContacts() : Flow<List<ChatContact>> = contactsDao.getAllContact()

    fun  getUserNameByUserId(userId : String) : String = contactsDao.getUserName(userId)

    fun  getChatContactById(userId : String) : ChatContact = contactsDao.getChatContactById(userId)

    fun  getChatContactByPhone(phone : String) : ChatContact = contactsDao.getChatContactByPhone(phone)


    fun  getChatContactByBittelID(bittelID : String) : ChatContact? = contactsDao.getChatContactByBittelID(bittelID)

    fun  getChatContactByAppBittelID(bittelID : String) : ChatContact? = contactsDao.getChatContactByAppBittelID(bittelID)


    fun  getUserNamesByUsersIds(userIds : List<String>) : List<ChatContact> = contactsDao.getUserNames(userIds)

    suspend fun addContact(chatContact: ChatContact) {
        contactsDao.addContact(chatContact)
    }
    suspend fun addAllContacts(chatContact: List<ChatContact>) {
        contactsDao.addAllContacts(chatContact)
    }

    fun  getContactsById(userIds : List<String>) : List<ChatContact> = contactsDao.getContactsById(userIds)

    suspend fun updateChatName(chatId: String, name : String) = contactsDao.updateChatName(chatId, name)

    suspend fun deleteContact (userId : String) = contactsDao.deleteContact(userId)
    suspend fun clearData () : Boolean {
        contactsDao.clearData()
        return true
    }
}