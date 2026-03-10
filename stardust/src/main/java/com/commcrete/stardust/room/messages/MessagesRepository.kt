package com.commcrete.stardust.room.messages

import android.content.Context
import androidx.lifecycle.LiveData
import com.commcrete.stardust.util.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MessagesRepository (
    private val messagesDao: MessagesDao,
    private val scope: CoroutineScope
) {

    private val messageChannel = Channel<MessageItem>(Channel.BUFFERED)

    init {
        scope.launch(Dispatchers.IO) {
            for (msg in messageChannel) {
                messagesDao.addMessage(msg)
            }
        }
    }

    suspend fun archiveMessages(chatId: String? = null,
                                startTimestamp: Long,
                                endTimestamp: Long,
                                isArchived : Boolean = true) {
        return messagesDao.updateMessagesArchivedState(
            chatId = chatId,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            isArchived = isArchived
        )
    }

    fun readAllMessagesByChatId(chatid : String) : LiveData<MutableList<MessageItem>> {
        return messagesDao.getAllMessagesByChatId(chatid)
    }


    fun readAllMessagesByChatIdPTT(chatid : String) : LiveData<MutableList<MessageItem>> {
        return messagesDao.getAllMessagesByChatIdPTT(chatid)
    }

    suspend fun getAllMessagesByChatId(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): List<MessageItem> {
        return messagesDao.getAllMessagesByChatId(
            chatId = chatId,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp
        )
    }

    suspend fun getMessagesByChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int
    ): List<MessageItem> {
        return messagesDao.getMessagesByChatInRange(
            chatId = chatId,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            limit = limit
        )
    }

    suspend fun getPTTMessagesForChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int
    ): List<MessageItem> {
        return messagesDao.getPTTMessagesForChatInRange(
            chatId = chatId,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            limit = limit
        )
    }

    suspend fun addMessages(messageItems: List<MessageItem>) {
        messagesDao.addMessages(messageItems)
    }

    fun saveMessage(context: Context, messageItem: MessageItem, isPTT: Boolean = false) {
        if(isPTT && !DataManager.getSavePTTFilesRequired(context)) return
        messageChannel.trySend(messageItem)
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

    suspend fun clearChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long
    ) {
        return messagesDao.clearChatInRange(
            chatId = chatId,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp
        )
    }
}