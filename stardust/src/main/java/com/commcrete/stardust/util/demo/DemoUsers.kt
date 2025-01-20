package com.commcrete.bittell.util.demo

import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.request_objects.model.user_list.User
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.model.getSrcDestMin4Bytes
import com.commcrete.stardust.util.FolderReader
import com.google.gson.JsonObject
import java.util.Date

class DemoUsers {

    val mutableUserList : MutableList<ChatItem> = mutableListOf()
    val mutableMessagesList : MutableList<MessageItem> = mutableListOf()
    val mutableContactsList : MutableList<ChatContact> = mutableListOf()

    fun initUserList (jsonObject: JsonObject, userId: String) {
        val userList = jsonObject.getAsJsonArray("chats")
        var loop = 0
        for (chat in userList) {
            val chatObj = chat as JsonObject
            for ((key, value) in chatObj.entrySet()) {
                val appId = key
                val data = value as JsonObject
                val bittelId = data.get("bittelId").asString
                val name = data.get("chat_name").asString
                val isSniffer = data.get("sniffer").asBoolean
                var isGroup = false
                if(data.has("isGroup")){
                    isGroup = data.get("isGroup").asBoolean
                }
                var isBittel = false
                if(data.has("isBittel")){
                    isBittel = data.get("isBittel").asBoolean
                }
                var image = ""
                if(data.has("image")){
                    image = data.get("image").asString
                }
                mutableUserList.add(getChatItem(appId.getSrcDestMin4Bytes(), name , bittelId, userId, isSniffer,isGroup, isBittel, image))
//                mutableMessagesList.add(getMessageItem(appId, appId, loop, userId ))
                mutableContactsList.add(getContact(appId, name, loop, userId ,isSniffer, isGroup, isBittel))
                loop++
            }
        }
    }

    fun initUserList (demoList : List<FolderReader.ExcelUser>, userId: String) {
        var loop = 0
        for (chat in demoList) {
            val appId = chat.id
            val bittelId = chat.deviceId
            val name = chat.name
            val isSniffer = chat.type == "sniffer"
            var isGroup = chat.type == "group"
            var isBittel = (chat.type.toLowerCase() == "bittel" || chat.type.toLowerCase() == "stardust")
            var image = chat.image
            if(chat.deviceId.isNotEmpty()) {
                createDeviceUser(chat, userId, loop)
            }
            mutableUserList.add(getChatItem(appId.getSrcDestMin4Bytes(), name , bittelId.getSrcDestMin4Bytes(), userId, isSniffer,isGroup, isBittel, image))
//            mutableMessagesList.add(getMessageItem(appId, appId, loop, userId ))
            mutableContactsList.add(getContact(appId, name, loop, userId ,isSniffer, isGroup, isBittel))
            loop++
        }
    }

    private fun createDeviceUser (chat: FolderReader.ExcelUser, userId: String, loop: Int) {
        val appId = chat.deviceId
        val name = chat.name
        val isSniffer = false
        var isGroup = false
        var isBittel = true
        var image = chat.image
        mutableUserList.add(getChatItem(appId.getSrcDestMin4Bytes(), name , "", userId, isSniffer,isGroup, isBittel, image))
        mutableContactsList.add(getContact(appId, name, loop, userId ,isSniffer, isGroup, isBittel))
    }

    private fun getContact (
        appId: String,
        name: String,
        loop: Int,
        userId: String,
        isSniffer: Boolean,
        isGroup: Boolean,
        isBittel: Boolean
    ) : ChatContact {
        return ChatContact(displayName = getName(userId, appId, name), number = "$loop" , bittelId = appId, smartphoneBittelId = appId,
            isSniffer = isSniffer, isGroup = isGroup, isBittel = isBittel)
    }

    private fun getChatItem(
        appId: String,
        name: String,
        bittelId: String,
        userId: String,
        isSniffer: Boolean,
        isGroup: Boolean,
        isBittel: Boolean,
        image: String
    ): ChatItem {
        val message = Message(senderID = appId, text = "Hi",
            seen = true)
        val user = User(
            phone = appId, displayName = getName(userId, appId, name) , appId = arrayOf(appId), bittelId = arrayOf(bittelId)
        )
        val chatItem = ChatItem(chat_id = appId, name = getName(userId, appId, name), message = message, bittelIDS = bittelId,
            user = user, isSniffer = isSniffer, isGroup = isGroup, isBittel = isBittel, imageName = image
        )
        return chatItem
    }

    private fun getMessageItem(appId: String, name: String, loop: Int, userId: String): MessageItem {
        val messageItem =
            MessageItem(senderID = appId, text = "Hi", epochTimeMs = Date().time+loop,
                chatId = appId, seen = SeenStatus.SEEN, senderName = getName(userId, appId, name))
        return messageItem
    }

    private fun getName (userId : String, appId : String, name: String): String {
//        when (userId) {
//            "10000004" -> {return "$appId $name"}
//            "10000006" -> {return name }
//            "10000002" -> {return name }
//        }
        return name
    }
}