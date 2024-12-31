package com.commcrete.bittell.room.sniffer

import androidx.lifecycle.LiveData

class SnifferRepository (private val messagesDao: SnifferDao) {

    fun readAllMessagesByChatId(chatid : String) : LiveData<MutableList<SnifferItem>> {
        return messagesDao.getAllMessagesByChatId(chatid)
    }


    fun readAllMessagesByChatIdPTT(chatid : String) : LiveData<MutableList<SnifferItem>> {
        return messagesDao.getAllMessagesByChatIdPTT(chatid)
    }


    suspend fun addContact(messageItem: SnifferItem) {
        messagesDao.addMessage(messageItem)
    }

    suspend fun addMessages(messageItems: List<SnifferItem>) {
        messagesDao.addMessages(messageItems)
    }

    suspend fun savePttMessage(messageItem: SnifferItem) {
        messagesDao.addMessage(messageItem)
    }

    suspend fun updatePttMessage(chatID : String, messageItem: SnifferItem) {
        val lastPttMessage = messagesDao.getLastPttMessage(chatID)
        if(lastPttMessage!=null){
//            lastPttMessage.epochTimeMs = messageItem.epochTimeMs
            lastPttMessage.isAudioComplete = true
            messagesDao.updateMessage(lastPttMessage)
        }else {
            messagesDao.addMessage(messageItem)
        }
    }


    fun deleteAllMessagesByChatId (chatID : String) {
        messagesDao.clearChat(chatID)
    }


    suspend fun clearData () : Boolean {
        messagesDao.clearData()
        return true
    }
}