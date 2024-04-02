package com.commcrete.stardust.room.messages

import androidx.lifecycle.LiveData
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDao

class MessagesRepository (private val messagesDao: MessagesDao) {

    fun readAllMessagesByChatId(chatid : String) : LiveData<MutableList<MessageItem>> {
        return messagesDao.getAllMessagesByChatId(chatid)
    }


    fun readAllMessagesByChatIdPTT(chatid : String) : LiveData<MutableList<MessageItem>> {
        return messagesDao.getAllMessagesByChatIdPTT(chatid)
    }


    suspend fun addContact(messageItem: MessageItem) {
        messagesDao.addMessage(messageItem)
    }

    suspend fun addMessages(messageItems: List<MessageItem>) {
        messagesDao.addMessages(messageItems)
    }

    suspend fun savePttMessage(messageItem: MessageItem) {
        messagesDao.addMessage(messageItem)
    }

    suspend fun updatePttMessage(chatID : String, messageItem: MessageItem) {
        val lastPttMessage = messagesDao.getLastPttMessage(chatID)
        if(lastPttMessage!=null){
//            lastPttMessage.epochTimeMs = messageItem.epochTimeMs
            lastPttMessage.isAudioComplete = true
            messagesDao.updateMessage(lastPttMessage)
        }else {
            messagesDao.addMessage(messageItem)
        }
    }

    suspend fun updateMessageSeenByChatId (chatId : String) {
        messagesDao.updateSeenMessages(chatId)
    }

    fun deleteAllMessagesByChatId (chatID : String) {
        messagesDao.clearChat(chatID)
    }

    suspend fun updateAckReceived (chatid: String, messageNumber: Long) {
        messagesDao.updateAckReceived(chatid, messageNumber)
    }

    suspend fun clearData () : Boolean {
        messagesDao.clearData()
        return true
    }
}