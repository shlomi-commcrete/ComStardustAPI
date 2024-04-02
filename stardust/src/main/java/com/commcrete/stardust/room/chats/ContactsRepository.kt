package com.commcrete.stardust.room.chats

import androidx.lifecycle.LiveData
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDao

class ChatsRepository (private val chatsDao: ChatsDao) {

    val readAllChats : LiveData<List<ChatItem>> = chatsDao.getAllChats()
    suspend fun  getAllChats () : List<ChatItem> {
        return chatsDao.readChats()
    }

    fun readChat(chatID : String) : LiveData<ChatItem>{
        return chatsDao.getChat(chatID)
    }

    fun getChatByBittelID(bittelID : String) : ChatItem?{
        return chatsDao.getChatContactByBittelID(bittelID)
    }
    suspend fun addChat(chatItem: ChatItem) {
        chatsDao.addChat(chatItem)
    }

    suspend fun deleteUser (chatID : String) {
        chatsDao.deleteUser(chatID)
    }

    suspend fun addChats(chatItems: List<ChatItem>) {
        chatsDao.addChats(chatItems)
    }

    suspend fun updateChatName(chatId: String, name : String) = chatsDao.updateChatName(chatId, name)
    suspend fun updateDisplayName(chatId: String, name : String) = chatsDao.updateDisplayName(chatId, name)

    suspend fun updateAudioReceived(chatId: String, isAudioReceived : Boolean) = chatsDao.updateChatAudioReceived(chatId, isAudioReceived)
    suspend fun updateEnableBackgroundPTT(chatId: String, enableBackgroundPtt : Boolean) = chatsDao.updateChatBackgroundPttEnable(chatId, enableBackgroundPtt)

    suspend fun updateNumOfUnseenMessages(chatId: String, numOfUnseenMessages: Int) = chatsDao.updateNumOfUnseenMessages(chatId, numOfUnseenMessages)
    suspend fun clearData () : Boolean {
        chatsDao.clearData()
        return true
    }

//    suspend fun updateOnlineStatus(chatId: String, isOnline : Boolean) = chatsDao.updateOnlineStatus(chatId, isOnline)

//    suspend fun updateBittelID(chatId: String, bittelID : String) = chatsDao.updateBittelID(chatId, bittelID)

//    suspend fun updateIsPTTEnableStatus(chatId: String, isPTTEnable : Boolean) = chatsDao.updateEnablePtt(chatId, isPTTEnable)
}