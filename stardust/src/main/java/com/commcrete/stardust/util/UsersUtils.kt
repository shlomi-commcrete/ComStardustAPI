package com.commcrete.stardust.util

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.request_objects.User
import com.commcrete.stardust.room.beetle_users.BittelUser
import com.commcrete.stardust.room.beetle_users.BittelUserDatabase
import com.commcrete.stardust.room.beetle_users.BittelUserRepository
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.contacts.ContactsRepository
import com.commcrete.stardust.room.friends.FriendsDatabase
import com.commcrete.stardust.room.friends.FriendsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustSOSPackage
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.*
import java.util.Date

object UsersUtils {

    private val bittelUserDoa = BittelUserDatabase.getDatabase(DataManager.context).bittelUserDao()
    private val bittelUserRepository = BittelUserRepository(bittelUserDoa)

    val contactsDao = ContactsDatabase.getDatabase(DataManager.context).contactsDao()
    private val contactsRepository = ContactsRepository(contactsDao)

    val updatedUsersList = MutableLiveData<MutableList<User>>()
    val chatContactList : MutableList<ChatContact> = mutableListOf()

    val bittelVersion = MutableLiveData<String>()
    val bittelConfiguration = MutableLiveData<StardustConfigurationPackage>()

    var user : User? = null

    val mRegisterUser : MutableLiveData<RegisterUser> = MutableLiveData()

    suspend fun getBittelUserList() : List<BittelUser>{
        val bittelUserList = GlobalScope.async {
            return@async bittelUserRepository.readBittelUsers()
        }
        return bittelUserList.await()
    }

    val messageReceived : MutableLiveData<MessageItem> = MutableLiveData()

    suspend fun addAllBittelUsers(userList: MutableList<BittelUser>) {
        bittelUserRepository.addAllBittelUsers(userList)
    }


    suspend fun getUserName(senderId: String) : String{
        var senderIdToReturn = senderId
        val getSenderName = CoroutineScope(Dispatchers.IO).async {
            val chat = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
                .getChatByBittelID(senderId)
            chat?.user?.displayName?.let {
                senderIdToReturn = it
                return@async it
            }
            return@async senderIdToReturn
        }
        getSenderName.await()
        return senderIdToReturn
    }

    private fun createNewBittelUserSender(chatsRepo: ChatsRepository, bittelPackage: StardustPackage): ChatItem {
        val chatId = bittelPackage.getSourceAsString()
        val message = Message(senderID = chatId, text = bittelPackage.getDataAsString()?:"",
            seen = true)
        val user = com.commcrete.stardust.request_objects.model.user_list.User(
            phone = chatId, displayName = chatId , appId = arrayOf(chatId)
        )
        val chatItem = ChatItem(chat_id = chatId, name = chatId, message = message,
            user = user
        )
        Scopes.getDefaultCoroutine().launch {
            chatsRepo.addChat(chatItem)
        }
        return chatItem
    }

    fun createNewBittelUserPTTSender(chatsRepo: ChatsRepository, bittelPackage: StardustPackage): ChatItem {
        val chatId = bittelPackage.getSourceAsString()
        val message = Message(senderID = chatId, text = "Ptt message",
            seen = true)
        val user = com.commcrete.stardust.request_objects.model.user_list.User(
            phone = chatId, displayName = chatId , appId = arrayOf(chatId)
        )
        val chatItem = ChatItem(chat_id = chatId, name = chatId, message = message,
            user = user
        )
        Scopes.getDefaultCoroutine().launch {
            chatsRepo.addChat(chatItem)
        }
        return chatItem
    }

    fun saveBittelUserSOS(bittelPackage: StardustPackage, bittelSOSPackage: StardustSOSPackage, isCreateNewUser : Boolean = true){
        Scopes.getDefaultCoroutine().launch {
            val chatContact = contactsRepository.getChatContactByBittelID(bittelPackage.getSourceAsString())
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
            if(chatContact != null) {
                chatContact.let {
                    var contact : ChatContact? = it
                    var whoSent = ""
                    var displayName = contact?.displayName
                    if(bittelPackage.getSourceAsString() == "00000002"){
                        whoSent = bittelPackage.getDestAsString()
                        val sender = chatsRepo.getChatByBittelID(whoSent)
                        sender?.let {
                            displayName = it.name
                        }
                        contact = contactsRepository.getChatContactByBittelID(whoSent)
                    }else {
                        whoSent = bittelPackage.getSourceAsString()
                    }
                    contact?.lastUpdateTS = Date().time
                    contact?.lat = bittelSOSPackage.latitude.toDouble()
                    contact?.lon = bittelSOSPackage.longitude.toDouble()
                    contact?.isSOS = true
                    contact?.let { it1 -> contactsRepository.addContact(it1) }
                    Scopes.getMainCoroutine().launch {
                        Toast.makeText(DataManager.context, "SOS Received From : ${contact?.displayName  }", Toast.LENGTH_LONG ).show()
                    }
                    val text = "latitude : ${bittelSOSPackage.latitude}\n" +
                            "longitude : ${bittelSOSPackage.longitude}\naltitude : ${bittelSOSPackage.height}"
                    val message = MessageItem(senderID = whoSent, text = text, epochTimeMs =  Date().time ,
                        senderName = displayName, chatId = bittelPackage.getSourceAsString(), isLocation = true, isSOS = true)
                    MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao()).addContact(message)
                }
            } else if(isCreateNewUser) {
                LocationUtils.createNewContact(bittelPackage)
                saveBittelUserSOS(bittelPackage, bittelSOSPackage, false)
            }
        }
    }
    fun saveBittelMessageToDatabase(bittelPackage: StardustPackage, isSOS : Boolean = false){
        Scopes.getDefaultCoroutine().launch {
            if(bittelPackage.getSourceAsString().isNotEmpty()){
                val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
                var chatItem = chatsRepo.getChatByBittelID(bittelPackage.getSourceAsString())
                if(chatItem == null) {
                    chatItem = createNewBittelUserSender(chatsRepo, bittelPackage)
                }
                chatItem.let { chat ->
                    val chatContact = chat.user
                    chatContact?.let { contact ->
                        contact.appId?.let { appIdArray ->
                            var whoSent = ""
                            var displayName = contact.displayName
                            if(bittelPackage.getSourceAsString() == "00000002"){
                                whoSent = bittelPackage.getDestAsString()
                                val sender = chatsRepo.getChatByBittelID(whoSent)
                                sender?.let {
                                    displayName = it.name
                                }
                            }else {
                                whoSent = appIdArray[0]
                            }
                            if(appIdArray.isNotEmpty()){
                                chat.message = Message(senderID = whoSent, text = bittelPackage.getDataAsString()?:"",
                                    seen = true)
                                chatsRepo.addChat(chat)
                                val messageItem =
                                    MessageItem(senderID = whoSent, text = bittelPackage.getDataAsString()?:"", epochTimeMs = Date().time,
                                        chatId = appIdArray[0], seen = SeenStatus.SEEN, senderName = displayName, isSOS = isSOS)
                                saveMessageToDatabase(appIdArray[0], messageItem)
                                val numOfUnread = chat.numOfUnseenMessages
                                chatsRepo.updateNumOfUnseenMessages(bittelPackage.getSourceAsString(), numOfUnread+1)
                                PlayerUtils.playNotificationSound (DataManager.context)
                                Scopes.getMainCoroutine().launch {
                                    messageReceived.value = messageItem
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun logout () : Boolean{
        val bittelUserList = GlobalScope.async {
            val clearBittelUserRepository = BittelUserRepository(BittelUserDatabase.getDatabase(DataManager.context).bittelUserDao()).clearData()
            val clearChatsRepository = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao()).clearData()
            val clearContactsRepository = ContactsRepository(ContactsDatabase.getDatabase(DataManager.context).contactsDao()).clearData()
            val clearFriendsRepository = FriendsRepository(FriendsDatabase.getDatabase(DataManager.context).friendsDao()).clearData()
            val clearMessagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao()).clearData()
            val clearPhone = SharedPreferencesUtil.removePhone(DataManager.context)
            val clearPassword = SharedPreferencesUtil.removePassword(DataManager.context)
            val clearBittelDevice = SharedPreferencesUtil.removeBittelDevice(DataManager.context)
            val clearBittelDeviceName = SharedPreferencesUtil.removeBittelDeviceName(DataManager.context)
            val clearAppUser = SharedPreferencesUtil.removeAppUser(DataManager.context)
            val clearUser = SharedPreferencesUtil.removeUser(DataManager.context)
            return@async clearBittelUserRepository && clearChatsRepository && clearContactsRepository && clearFriendsRepository && clearMessagesRepository
                    && clearPhone && clearPassword && clearBittelDevice && clearBittelDeviceName && clearAppUser && clearUser
        }
        return bittelUserList.await()
    }

    private fun saveMessageToDatabase(chatID : String, message: MessageItem){
        Scopes.getDefaultCoroutine().launch {
//            message.senderName = getSenderName(chatID, message.senderID)
            MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao()).addContact(message)
        }
    }

    private fun getSenderName(chatID : String, senderID: String): String{
        val mUserId = SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let {
            if(!chatID.contains(it) && senderID != it ){
                val contactsRepository = ContactsRepository(ContactsDatabase.getDatabase(DataManager.context).contactsDao())
                return contactsRepository.getUserNameByUserId(senderID)
            }
        }

        return ""
    }

    fun updateRegisteredUser (registerUser: RegisterUser) {
        Scopes.getMainCoroutine().launch {
            mRegisterUser.value = registerUser
        }

    }

    fun onUserAcquired() {
    }

    fun addNewUser (appId: String, name: String) {
        Scopes.getDefaultCoroutine().launch {
            ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao()).addChat(
                getChatItem(appId, name, appId)
            )
            MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao()).addContact(
                getMessageItem(appId, name, 0)
            )
            ContactsRepository(ContactsDatabase.getDatabase(DataManager.context).contactsDao()).addContact(
                getContact(appId, name, 0)
            )
        }
    }

    fun removeUser(selectedUser: ChatItem?, deleteMessages: Boolean) {
        selectedUser?.let {
            Scopes.getDefaultCoroutine().launch {
                ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao()).deleteUser(it.chat_id)
                ContactsRepository(ContactsDatabase.getDatabase(DataManager.context).contactsDao()).deleteContact(it.chat_id)
            }
        }
    }

    private fun getContact (appId: String, name: String, loop: Int) : ChatContact {
        return ChatContact(displayName = name, number = "$loop" , bittelId = appId, smartphoneBittelId = appId)
    }

    private fun getChatItem(appId: String, name: String, bittelId: String): ChatItem {
        val message = Message(senderID = appId, text = "Hi",
            seen = true)
        val user = com.commcrete.stardust.request_objects.model.user_list.User(
            phone = appId, displayName = name, appId = arrayOf(appId), bittelId = arrayOf(bittelId)
        )
        val chatItem = ChatItem(chat_id = appId, name = name, message = message,
            user = user
        )
        return chatItem
    }

    private fun getMessageItem(appId: String, name: String, loop: Int): MessageItem {
        val messageItem =
            MessageItem(senderID = appId, text = "Hi", epochTimeMs = Date().time+loop,
                chatId = appId, seen = SeenStatus.SEEN, senderName = name)
        return messageItem
    }
}