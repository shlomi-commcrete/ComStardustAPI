package com.commcrete.stardust.room.sos_messages

import androidx.lifecycle.LiveData
import com.commcrete.stardust.room.sos_messages.SOSMessageItem
import com.commcrete.stardust.room.sos_messages.SOSMessagesDao

class SOSMessagesRepository (private val messagesDao: SOSMessagesDao) {

    suspend fun addSOSMessage(messageItem: SOSMessageItem) {
        messagesDao.addMessage(messageItem)
    }

    suspend fun updateMessage (messageItem: SOSMessageItem) {
        messagesDao.updateMessage(messageItem)
    }

    suspend fun removeSOSMessage(messageItem: SOSMessageItem) {
        messagesDao.removeMessage(messageItem)
    }

    fun getSOSMessages() : LiveData<MutableList<SOSMessageItem>> {
        return messagesDao.getAllMessages()
    }
}