package com.commcrete.stardust.room.chats

import androidx.lifecycle.LiveData
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDao
import com.commcrete.stardust.util.GroupsUtils

class ChatsRepository (private val chatsDao: ChatsDao) {

    val readAllChats : LiveData<List<ChatItem>> = chatsDao.getAllChats()
    suspend fun  getAllChats () : List<ChatItem> {
        return chatsDao.readChats()
    }

    fun getAllChatsIds(): List<String> {
        return chatsDao.getAllChatsIds()
    }

    fun readChat(chatID : String) : LiveData<ChatItem>{
        return chatsDao.getChat(chatID)
    }

    suspend fun getChatByBittelID(bittelID : String) : ChatItem?{
        return chatsDao.getChatContactByBittelID(bittelID)
    }
    suspend fun addChat(chatItem: ChatItem) {
        chatsDao.addChat(chatItem)
        if(chatItem.isGroup) GroupsUtils.addGroupIds(listOf(chatItem.chat_id))
    }

    suspend fun deleteUser (chatID : String) {
        chatsDao.deleteUser(chatID)
    }

    suspend fun addChats(chatItems: List<ChatItem>) {
        chatsDao.addChats(chatItems)
        chatItems
            .mapNotNull { if (it.isGroup) it.chat_id else null }
            .also { GroupsUtils.addGroupIds(it) }
    }

    suspend fun updateChatName(chatId: String, name : String) = chatsDao.updateChatName(chatId, name)
    suspend fun updateDisplayName(chatId: String, name : String) = chatsDao.updateDisplayName(chatId, name)

    suspend fun updateAudioReceived(chatId: String, isAudioReceived : Boolean) = chatsDao.updateChatAudioReceived(chatId, isAudioReceived)
    suspend fun updateEnableBackgroundPTT(chatId: String, enableBackgroundPtt : Boolean) = chatsDao.updateChatBackgroundPttEnable(chatId, enableBackgroundPtt)

    suspend fun updateNumOfUnseenMessages(chatId: String, numOfUnseenMessages: Int) = chatsDao.updateNumOfUnseenMessages(chatId, numOfUnseenMessages)
    suspend fun clearData () : Boolean {
        chatsDao.clearData()
        GroupsUtils.clearData()
        return true
    }

    suspend fun resetChatsMessages() {
        chatsDao.resetChatsMessages()
    }

    suspend fun getAllGroupIds() : List<String> {
        return chatsDao.getAllGroupIds()
    }

//    suspend fun updateOnlineStatus(chatId: String, isOnline : Boolean) = chatsDao.updateOnlineStatus(chatId, isOnline)

//    suspend fun updateBittelID(chatId: String, bittelID : String) = chatsDao.updateBittelID(chatId, bittelID)

//    suspend fun updateIsPTTEnableStatus(chatId: String, isPTTEnable : Boolean) = chatsDao.updateEnablePtt(chatId, isPTTEnable)
}