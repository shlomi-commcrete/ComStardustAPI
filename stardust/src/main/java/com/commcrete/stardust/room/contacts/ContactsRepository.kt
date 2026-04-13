package com.commcrete.stardust.room.contacts


class ContactsRepository (private val contactsDao: ContactsDao) {

    suspend fun addAllContacts(chatContact: List<ChatContact>) {
        contactsDao.addAllContacts(chatContact)
    }

    suspend fun clearData () : Boolean {
        contactsDao.clearData()
        return true
    }
}