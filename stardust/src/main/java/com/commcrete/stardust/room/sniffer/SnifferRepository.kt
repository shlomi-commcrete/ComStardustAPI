package com.commcrete.bittell.room.sniffer

import android.content.Context
import androidx.lifecycle.LiveData
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil

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

    suspend fun savePttMessage(context: Context, messageItem: SnifferItem) {
        if(DataManager.getSavePTTFilesRequired(context)) messagesDao.addMessage(messageItem)
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