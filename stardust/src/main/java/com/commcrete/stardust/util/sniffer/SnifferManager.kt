package com.commcrete.bittell.util.sniffer

import android.content.Context
import com.commcrete.bittell.room.sniffer.SnifferDatabase
import com.commcrete.bittell.room.sniffer.SnifferItem
import com.commcrete.bittell.room.sniffer.SnifferRepository
import com.commcrete.bittell.util.text_utils.getCharValue
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.request_objects.model.user_list.User
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.contacts.ContactsRepository
import com.commcrete.stardust.stardust.model.StardustLocationPackage
import com.commcrete.stardust.stardust.model.StardustLocationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.usb.startsWith
import com.commcrete.stardust.util.AdminUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.launch
import java.util.Date

object SnifferManager {

    const val SNIFFER_ID = "99999999"

    fun saveText (context: Context, bittelPackage: StardustPackage){
        Scopes.getDefaultCoroutine().launch {

            if(bittelPackage.data !=null && bittelPackage.data!!.startsWith(arrayOf(83,79,83))) {
                saveSOS(context, bittelPackage)
            }else {
                val contacts = getChatContacts(context, bittelPackage.getSourceAsString(), bittelPackage.getDestAsString())
                val text = getCharValue(bittelPackage.getDataAsString())
                updateLastTsOfChatItem(text, contacts[0].displayName)
                var user1 = contacts[0].displayName
                var user2 = contacts[1].displayName
                if(contacts[0].isGroup) {
                    user1 = contacts[1].displayName
                    user2 = contacts[0].displayName
                }
                val snifferItem = SnifferItem(
                    senderID = bittelPackage.getSourceAsString(),
                    receiverID = bittelPackage.getDestAsString(),
                    text = text,
                    epochTimeMs = Date().time,
                    chatId = SNIFFER_ID,
                    senderName = user1,
                    receiverName = user2
                )

                val repo = SnifferRepository(SnifferDatabase.getDatabase(context).snifferDao())
                repo.addContact(snifferItem)
            }

        }
    }

    private fun saveSOS (context: Context, bittelPackage: StardustPackage){
        Scopes.getDefaultCoroutine().launch {
            val contacts = getChatContacts(context, bittelPackage.getSourceAsString(), bittelPackage.getDestAsString())
            val sosPackage = StardustLocationParser().parseSOS(bittelPackage)
            sosPackage?.let {

                val text = "latitude : ${it.latitude}\n" +
                        "longitude : ${it.longitude}\naltitude : ${it.height}"
                updateLastTsOfChatItem(text, contacts[0].displayName)
                val snifferItem = SnifferItem(
                    senderID = bittelPackage.getSourceAsString(),
                    receiverID = bittelPackage.getDestAsString(),
                    text = text,
                    epochTimeMs = Date().time,
                    chatId = SNIFFER_ID,
                    isSOS = true,
                    senderName = contacts[1].displayName,
                    receiverName = contacts[0].displayName
                )

                val repo = SnifferRepository(SnifferDatabase.getDatabase(context).snifferDao())
                repo.addContact(snifferItem)


            }
        }

    }

    fun savePTT (context: Context, bittelPackage: StardustPackage){
        //Save PTT, Play if you are in sniffer mode and you are not the target
        // (because then it will be played twice)
        Scopes.getDefaultCoroutine().launch {
            val contacts = getChatContacts(context, bittelPackage.getSourceAsString(), bittelPackage.getDestAsString())
            updateLastTsOfChatItem("Ptt message", contacts[0].displayName)
            var user1 = contacts[0].displayName
            var user2 = contacts[1].displayName
            if(contacts[0].isGroup) {
                user1 = contacts[1].displayName
                user2 = contacts[0].displayName
            }
            val message = "PTT From : $user1, To : $user2"

            SharedPreferencesUtil.getAppUser(context)?.appId?.let {
                if(!isMyId(it, bittelPackage.getSourceAsString(),bittelPackage.getDestAsString() ) &&
                    !isLocalGroup(contacts[0].bittelId,contacts[1].bittelId)
                ){
                    Scopes.getMainCoroutine().launch {
                        PlayerUtils.isPttReceived.value = message
                    }
                    PlayerUtils.handleSnifferMessage(bittelPackage, SNIFFER_ID, contacts)
                }
                else {
                    PlayerUtils.setTs()
                    PlayerUtils.initPttSnifferFile(context,SNIFFER_ID, contacts)
                }
            }


        }
    }

    fun saveLocationRequested (context: Context, bittelPackage: StardustPackage) {
        Scopes.getDefaultCoroutine().launch {
            val contacts = getChatContacts(context, bittelPackage.getSourceAsString(), bittelPackage.getDestAsString())
            val text = "Location Requested"
            updateLastTsOfChatItem(text, contacts[0].displayName)
            val snifferItem = SnifferItem(
                senderID = bittelPackage.getSourceAsString(),
                receiverID = bittelPackage.getDestAsString(),
                text = text,
                epochTimeMs = Date().time,
                chatId = SNIFFER_ID,
                senderName = contacts[0].displayName,
                receiverName = contacts[1].displayName
            )
            val repo = SnifferRepository(SnifferDatabase.getDatabase(context).snifferDao())
            repo.addContact(snifferItem)
        }
    }

    fun saveLocationReceived (context: Context, bittelPackage: StardustPackage) {
        Scopes.getDefaultCoroutine().launch {
            val contacts = getChatContacts(context, bittelPackage.getSourceAsString(), bittelPackage.getDestAsString())
            val locationPackage = StardustLocationParser().parseLocation(bittelPackage)

            locationPackage?.let {

                val text = "latitude : ${it.latitude}\n" +
                        "longitude : ${it.longitude}\naltitude : ${it.height}"
                updateLastTsOfChatItem(text, contacts[0].displayName)

                val snifferItem = SnifferItem(
                    senderID = bittelPackage.getSourceAsString(),
                    receiverID = bittelPackage.getDestAsString(),
                    text = text,
                    epochTimeMs = Date().time,
                    chatId = SNIFFER_ID,
                    isLocation = true,
                    senderName = contacts[0].displayName,
                    receiverName = contacts[1].displayName
                )
                saveLocation(contacts[0], locationPackage)
                val repo = SnifferRepository(SnifferDatabase.getDatabase(context).snifferDao())
                repo.addContact(snifferItem)
            }
        }
    }

    private fun saveLocation (contact: ChatContact, bittelPackage: StardustLocationPackage) {
        val contactsRepository = ContactsRepository(ContactsDatabase.getDatabase(DataManager.context).contactsDao())
        contact.lastUpdateTS = Date().time
        contact.lat = bittelPackage.latitude.toDouble()
        contact.lon = bittelPackage.longitude.toDouble()
        contact.isSOS = false
        Scopes.getDefaultCoroutine().launch {
            contactsRepository.addContact(contact)
        }
    }

    private fun getChatContacts (context: Context, sender: String, receiver: String): List<ChatContact> {
        val list : MutableList<ChatContact> = mutableListOf()
        val repo = ContactsRepository(ContactsDatabase.getDatabase(context).contactsDao())
        var senderUser = repo.getChatContactByBittelId(sender)
        if(senderUser == null) {
            senderUser = ChatContact(contactId = 0, displayName = sender, number = sender, chatUserId = sender)
        }
        var receiverUser = repo.getChatContactByBittelId(receiver)
        if(receiverUser == null) {
            receiverUser = ChatContact(contactId = 0, displayName = receiver, number = receiver, chatUserId = receiver)
        }
        list.add(senderUser)
        list.add(receiverUser)
        return  list
    }

    private fun updateLastTsOfChatItem (text : String, sender: String, isSOS : Boolean = false) {
        Scopes.getDefaultCoroutine().launch {
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
            var chatItem = chatsRepo.getChatByBittelID(SNIFFER_ID)
            chatItem?.let { chat ->
                chat.message = Message(senderID = sender, text = text,
                    seen = true)
                chatsRepo.addChat(chat)
            }
        }
    }

    fun addSnifferUser (name : String) {
        val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
        val adminLocal = AdminUtils.getAdminLocalMode(DataManager.context)
        val chatId = SNIFFER_ID
        val user = User(
            phone = chatId, displayName = name , appId = arrayOf(chatId)
        )
        val chatItem = ChatItem(chat_id = chatId, name = name,
            user = user
        )
        Scopes.getDefaultCoroutine().launch {
            chatsRepo.addChat(chatItem)
        }
    }

    fun removeSnifferUser () {
        val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
        Scopes.getDefaultCoroutine().launch {
            chatsRepo.deleteUser(SNIFFER_ID)
        }

    }
}

fun isMyId(myId : String, sender : String?, receiver : String?) : Boolean {
    return sender == myId || receiver == myId
}

fun isLocalGroup(sender : String?, receiver : String?) : Boolean {
    return GroupsUtils.isGroup(sender) || GroupsUtils.isGroup(receiver)
}