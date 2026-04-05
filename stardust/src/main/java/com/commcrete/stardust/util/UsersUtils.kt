package com.commcrete.stardust.util

import android.content.Context
import android.location.Location
import androidx.lifecycle.MutableLiveData
import com.commcrete.bittell.util.text_utils.getCharValue
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.request_objects.User
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.room.new_db.AppDatabase
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustSOSPackage
import com.commcrete.stardust.util.DataManager.cleanAllDatabases
import com.commcrete.stardust.util.DataManager.unpairDeviceBLE
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.*
import java.util.Date

object UsersUtils {

    var onUserUpdatedListener: OnUserUpdated? = null
    var user : User? = null

    var mRegisterUser : RegisterUser? = null
        internal set(value) {
            field = value
            onUserUpdatedListener?.onUpdated(value)
        }

    val messageReceived : MutableLiveData<MessageItem> = MutableLiveData()


    suspend fun getUserName(context: Context, senderId: String) : String{
        var senderIdToReturn = senderId
        val getSenderName = CoroutineScope(Dispatchers.IO).async {
            val chat = DataManager.getAppRepo(context).getChatByDeviceId(senderId)
            chat?.user?.displayName?.let {
                senderIdToReturn = it
                return@async it
            }
            return@async senderIdToReturn
        }
        getSenderName.await()
        return senderIdToReturn
    }

    fun createNewBittelUserSender(appContext: Context, bittelPackage: StardustPackage): ChatItem {
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
            DataManager.getAppRepo(appContext).addChat(chatItem)
        }
        return chatItem
    }

    fun createNewBittelUserPTTSender(appContext: Context, bittelPackage: StardustPackage): ChatItem {
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
            DataManager.getAppRepo(appContext).addChat(chatItem)
        }
        return chatItem
    }

    fun saveBittelUserSOS(bittelPackage: StardustPackage, bittelSOSPackage: StardustSOSPackage, isCreateNewUser : Boolean = true){
        Scopes.getDefaultCoroutine().launch {
            val chatContact = contactsRepository.getChatContactByBittelID(bittelPackage.getSourceAsString())
            val chatsRepo = ChatsRepository(AppDatabase.getDatabase(DataManager.context).chatsDao())
            var sender : ChatItem? = null
            var receiver : ChatItem? = null
            if(chatContact != null) {
                chatContact.let {
                    var contact : ChatContact? = it
                    var whoSent = ""
                    var displayName: String? = null
                    val sentAsUserInGroup = GroupsUtils.isGroup(bittelPackage.getSourceAsString()) && !bittelPackage.getDestAsString().equals(mRegisterUser?.appId, ignoreCase = true)
                    if(sentAsUserInGroup){
                        whoSent = bittelPackage.getDestAsString()
                        sender = chatsRepo.getChatByBittelID(whoSent)
                        receiver = chatsRepo.getChatByBittelID(bittelPackage.getSourceAsString())
                        sender?.let {
                            displayName = it.name
                        }
                    } else {
                        displayName = contact?.displayName
                        whoSent = bittelPackage.getSourceAsString()
                        sender = chatsRepo.getChatByBittelID(whoSent)
                    }
                    contact?.lastUpdateTS = Date().time
                    contact?.lat = bittelSOSPackage.latitude.toDouble()
                    contact?.lon = bittelSOSPackage.longitude.toDouble()
                    contact?.isSOS = true
                    contact?.let { it1 -> contactsRepository.addContact(it1) }
                    Scopes.getMainCoroutine().launch {
//                        Toast.makeText(DataManager.context, "SOS Received From : ${contact?.displayName  }", Toast.LENGTH_LONG ).show()
                    }
                    val text = "latitude : ${bittelSOSPackage.latitude}\n" +
                            "longitude : ${bittelSOSPackage.longitude}\naltitude : ${bittelSOSPackage.height}"
                    val message = MessageItem(senderID = whoSent, text = text, epochTimeMs =  Date().time ,
                        senderName = displayName ?: whoSent, chatId = bittelPackage.getSourceAsString(),  isSOS = true,
                        sosType = bittelSOSPackage.sosType)
                    DataManager.getAppRepo(DataManager.context).saveMessage(context = DataManager.context, messageItem = message)


                    val location = Location(whoSent).apply {
                        latitude = bittelSOSPackage.latitude.toDouble()
                        longitude = bittelSOSPackage.longitude.toDouble()
                        altitude = bittelSOSPackage.height.toDouble()
                    }
                    PlayerUtils.playNotificationSound (DataManager.context)

                    DataManager.getCallbacks()?.receiveSOS(StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString(),),
                        location, bittelSOSPackage.sosType)


                    if(sentAsUserInGroup) {
                        receiver?.let {
                            saveChatItemSOS(it, whoSent, bittelSOSPackage, chatsRepo)
                        }
                    } else {
                        sender?.let {
                            saveChatItemSOS(it, whoSent, bittelSOSPackage, chatsRepo)
                        }
                    }
                }
            } else if(isCreateNewUser) {
                LocationUtils.createNewContact(bittelPackage)
                saveBittelUserSOS(bittelPackage, bittelSOSPackage, false)
            }
        }
    }

    fun saveChatItemSOS (
        chatItem: ChatItem,
        senderID: String,
        bittelSOSPackage: StardustSOSPackage,
        chatsRepo: ChatsRepository
    ) {
        Scopes.getDefaultCoroutine().launch {
            val text = when (bittelSOSPackage.sosType) {
                SOSUtils.SOS_TYPES_ARMY.HOSTILE.type -> {
                    "Reporting ${SOSUtils.SOS_TYPES_ARMY.HOSTILE.sosName} Event" }
                SOSUtils.SOS_TYPES_ARMY.MAN_DOWN.type -> {
                    "Reporting ${SOSUtils.SOS_TYPES_ARMY.MAN_DOWN.sosName} Event"}
                SOSUtils.SOS_TYPES_ARMY.LOST.type -> {
                    "Reporting ${SOSUtils.SOS_TYPES_ARMY.LOST.sosName} Event"}
                else -> {
                    "Reporting S.O.S"}
            }
            chatItem.message = Message(
                senderID = senderID,
                text = "$text",
                seen = false
            )
            chatItem.let { chatsRepo.addChat(it) }
            val numOfUnread = chatItem.numOfUnseenMessages
            chatsRepo.updateNumOfUnseenMessages(chatItem.chat_id, numOfUnread+1)
        }
    }

    fun saveBittelUserSOSReal(context: Context, bittelPackage: StardustPackage, bittelSOSPackage: StardustSOSPackage, isCreateNewUser : Boolean = true){
        Scopes.getDefaultCoroutine().launch {
            val chatContact = contactsRepository.getChatContactByBittelID(bittelPackage.getSourceAsString())
            val chatsRepo = ChatsRepository(AppDatabase.getDatabase(context).chatsDao())
            var chatItem : ChatItem? = null

            val packageToPass = StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString(),
                carrier = CarriersUtils.getCarrierByControl(bittelPackage.stardustControlByte.stardustDeliveryType))
            if(chatContact != null) {
                chatContact.let {
                    var contact : ChatContact? = it
                    val realSource = packageToPass.getRealSourceId()
                    val displayName: String = chatsRepo.getChatByBittelID(realSource)?.name ?: realSource
                    chatItem = if(packageToPass.isGroup){
                        chatsRepo.getChatByBittelID(bittelPackage.getSourceAsString())
                    } else {
                        chatsRepo.getChatByBittelID(realSource)
                    }
                    contact?.lastUpdateTS = Date().time
                    contact?.lat = bittelSOSPackage.latitude.toDouble()
                    contact?.lon = bittelSOSPackage.longitude.toDouble()
                    contact?.isSOS = true
                    contact?.let { it1 -> contactsRepository.addContact(it1) }
                    val text = "latitude : ${bittelSOSPackage.latitude}\n" +
                            "longitude : ${bittelSOSPackage.longitude}\naltitude : ${bittelSOSPackage.height}"
                    val message = MessageItem(senderID = realSource, text = text, epochTimeMs =  Date().time ,
                        senderName = displayName, chatId = bittelPackage.getSourceAsString(), isSOS = true, sosType = 0)
                    DataManager.getAppRepo(context).saveMessage(context = context, messageItem = message)
                    val location = Location(realSource)
                    location.latitude = bittelSOSPackage.latitude.toDouble()
                    location.longitude = bittelSOSPackage.longitude.toDouble()
                    location.altitude = bittelSOSPackage.height.toDouble()
                    DataManager.getCallbacks()?.receiveRealSOS(packageToPass, location)

                    chatItem?.let { chatItem ->
                        chatItem.message = Message(
                            senderID = realSource,
                            text = "Reporting S.O.S",
                            seen = false
                        )
                        chatsRepo.addChat(chatItem)
                        val numOfUnread = chatItem.numOfUnseenMessages
                        chatsRepo.updateNumOfUnseenMessages(chatItem.chat_id, numOfUnread+1)
                    }

                }
            } else if(isCreateNewUser) {
                LocationUtils.createNewContact(bittelPackage)
                saveBittelUserSOS(bittelPackage, bittelSOSPackage, false)
            }
        }
    }
    fun saveBittelMessageToDatabase(context: Context, bittelPackage: StardustPackage, isSOS : Boolean = false){
        CoroutineScope(Dispatchers.IO).launch {
            if(bittelPackage.getSourceAsString().isNotEmpty()){
                val chatsRepo = ChatsRepository(AppDatabase.getDatabase(context).chatsDao())
                var chatItem = chatsRepo.getChatByBittelID(bittelPackage.getSourceAsString())
                if(chatItem == null) {
                    chatItem = createNewBittelUserSender(chatsRepo, bittelPackage)
                }
                chatItem.let { chat ->
                    val chatContact = chat.user
                    chatContact?.let { contact ->
                        contact.appId?.let { appIdArray ->
                            var whoSent = ""
                            var displayName: String? = null
                            if(chat.isGroup && !bittelPackage.getDestAsString().equals(mRegisterUser?.appId, ignoreCase = true)){
                                whoSent = bittelPackage.getDestAsString()
                                val sender = chatsRepo.getChatByBittelID(whoSent)
                                sender?.let {
                                    displayName = it.name
                                }
                            } else {
                                displayName = contact.displayName
                                whoSent = appIdArray[0]
                            }
                            if(appIdArray.isNotEmpty()){
                                val text = getCharValue(bittelPackage.getDataAsString())
                                chat.message = Message(senderID = whoSent, text = text,
                                    seen = true)
                                chatsRepo.addChat(chat)
                                val messageItem =
                                    MessageItem(senderID = whoSent, text = text, epochTimeMs = Date().time,
                                        chatId = appIdArray[0], seen = SeenStatus.SEEN, senderName = displayName ?: whoSent, isSOS = isSOS)
                                saveMessageToDatabase(context, appIdArray[0], messageItem)
                                val numOfUnread = chat.numOfUnseenMessages
                                chatsRepo.updateNumOfUnseenMessages(bittelPackage.getSourceAsString(), numOfUnread+1)
                                PlayerUtils.playNotificationSound (context)
                                Scopes.getMainCoroutine().launch {
                                    messageReceived.value = messageItem
                                }
                                DataManager.getCallbacks()?.receiveMessage(StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString(),), text)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
            coroutineScope {
                // BLE unpair first (side-effect, usually must complete)
                unpairDeviceBLE(DataManager.context)
                val databases = async {
                    GroupsUtils.clearData()
                    cleanAllDatabases(DataManager.context)
                }
                val phone = async { SharedPreferencesUtil.removePhone(DataManager.context) }
                val password = async { SharedPreferencesUtil.removePassword(DataManager.context) }
                val appUser = async { SharedPreferencesUtil.removeAppUser(DataManager.context) }
                val user = async { SharedPreferencesUtil.removeUser(DataManager.context) }

                databases.await() &&
                        phone.await() &&
                        password.await() &&
                        appUser.await() &&
                        user.await()
            }
        }



    fun saveMessageToDatabase(context: Context, chatID : String, message: MessageItem){
        DataManager.getAppRepo(context).saveMessage(context = context, messageItem = message)
    }

    private fun getSenderName(chatID : String, senderID: String): String{
        val mUserId = SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let {
            if(!chatID.contains(it) && senderID != it ){
                val contactsRepository = ContactsRepository(AppDatabase.getDatabase(DataManager.context).contactsDao())
                return contactsRepository.getUserNameByUserId(senderID)
            }
        }

        return ""
    }


    fun onUserAcquired() {
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
        val chatItem = ChatItem(chat_id = appId, name = name, message = null,
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