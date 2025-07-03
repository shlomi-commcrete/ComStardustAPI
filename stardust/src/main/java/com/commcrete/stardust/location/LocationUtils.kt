package com.commcrete.stardust.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.request_objects.LocationMessage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustLocationPackage
import com.commcrete.stardust.util.CoordinatesUtil
import com.commcrete.stardust.util.LogUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.room.beetle_users.BittelUserDatabase
import com.commcrete.stardust.room.beetle_users.BittelUserRepository
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.contacts.ContactsDao
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.contacts.ContactsRepository
import com.commcrete.stardust.room.logs.LOG_EVENT
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.PermissionTracking
import com.commcrete.stardust.util.audio.PlayerUtils
import com.google.android.gms.location.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.EasyPermissions
import java.util.Date
import kotlin.random.Random

@SuppressLint("StaticFieldLeak")
object LocationUtils  {


    private lateinit var context: Context

    private var contactsDao : ContactsDao?= null
    private var contactsRepository : ContactsRepository? = null

    var lastLocation : Location? = null


    fun init(context: Context){
        this.context = context
        contactsDao = ContactsDatabase.getDatabase(this.context).contactsDao()
        contactsDao?.let { contactsRepository = ContactsRepository(it) }

    }
    private fun getSenderName(senderID: String): String{
        val contactsRepository = ContactsRepository(ContactsDatabase.getDatabase(context).contactsDao())
        return contactsRepository.getUserNameByUserId(senderID)
    }

    fun saveBittelUserLocation(bittelPackage: StardustPackage, bittelLocationPackage: StardustLocationPackage, isCreateNewUser : Boolean = true,
                               isSOS : Boolean = false){
        Scopes.getDefaultCoroutine().launch {
            val chatContact = contactsRepository?.getChatContactByBittelID(bittelPackage.getSourceAsString())
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
            var sender : ChatItem? = null
            if(chatContact != null) {
                chatContact.let { it ->
                    val contact = it
                    var whoSent = ""
                    var displayName = contact.displayName
                    if(GroupsUtils.isGroup(bittelPackage.getSourceAsString())){
                        whoSent = bittelPackage.getDestAsString()
                        sender = chatsRepo.getChatByBittelID(whoSent)
                        sender?.let {
                            displayName = it.name
                        }
                    }else {
                        whoSent = bittelPackage.getSourceAsString()
                        sender = chatsRepo.getChatByBittelID(whoSent)

                    }
                    contact.lastUpdateTS = Date().time
                    contact.lat = bittelLocationPackage.latitude.toDouble()
                    contact.lon = bittelLocationPackage.longitude.toDouble()
                    contact.isSOS = false
                    contactsRepository?.addContact(contact)
                    Scopes.getMainCoroutine().launch {
                        Toast.makeText(context, "Location Received From : ${contact.displayName  }", Toast.LENGTH_LONG ).show()
                    }
                    val text = "latitude : ${bittelLocationPackage.latitude}\n" +
                            "longitude : ${bittelLocationPackage.longitude}\naltitude : ${bittelLocationPackage.height}"
                    val message = MessageItem(senderID = whoSent, text = text, epochTimeMs =  Date().time , seen = SeenStatus.RECEIVED,
                        senderName = displayName, chatId = bittelPackage.getSourceAsString(), isLocation = true, isSOS = isSOS)
                    MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).addContact(message)
                    sender?.let {
                        it.message = Message(senderID = whoSent, text = "Location Received", seen = false)
                        chatsRepo.addChat(it)
                        val numOfUnread = it.numOfUnseenMessages
                        chatsRepo.updateNumOfUnseenMessages(bittelPackage.getSourceAsString(), numOfUnread+1)
                    }
                    val pollingUtils = DataManager.getPollingUtils(DataManager.context)
                    if(pollingUtils.isRunning) {
                        pollingUtils.handleResponse(bittelPackage)
                    }
                    var location = Location(whoSent)
                    location.latitude = bittelLocationPackage.latitude.toDouble()
                    location.longitude = bittelLocationPackage.longitude.toDouble()
                    location.altitude = bittelLocationPackage.height.toDouble()
                    PlayerUtils.playNotificationSound (DataManager.context)
                    DataManager.getCallbacks()?.receiveLocation(
                        StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString(), carrier = CarriersUtils.getCarrierByStardustPackage(bittelPackage)),
                        location)
                }
            } else if(isCreateNewUser) {
                createNewContact(bittelPackage)
                saveBittelUserLocation(bittelPackage, bittelLocationPackage, false)
            }
        }
    }
    suspend fun createNewContact(bittelPackage: StardustPackage){
        val contact = ChatContact(displayName = bittelPackage.getSourceAsString(), number = bittelPackage.getSourceAsString(), bittelId = bittelPackage.getSourceAsString())
        ContactsRepository(ContactsDatabase.getDatabase(context).contactsDao()).addContact(contact)
    }
    internal fun sendMyLocation(mPackage: StardustPackage, clientConnection: ClientConnection, isDemandAck : Boolean = false,
                       isHR : StardustControlByte.StardustDeliveryType = StardustControlByte.StardustDeliveryType.RD1, opCode : StardustPackageUtils.StardustOpCode? = null){
        if(lastLocation == null){
            sendMissingLocation(mPackage, clientConnection, isDemandAck,isHR,opCode)
        }else {
            sendLocation(mPackage, lastLocation!!, clientConnection, isDemandAck,isHR,opCode)
        }
    }

    fun getLocationForSOSMyLocation(): Array<Int> {
        if(lastLocation == null){
            return CoordinatesUtil().packEmptyLocation()
        }else {
            return CoordinatesUtil().packLocation(lastLocation!!)
        }
    }

    fun getLocationForSOSMyLocation(location: Location): Array<Int> {
        if(location == null){
            return CoordinatesUtil().packEmptyLocation()
        }else {
            return CoordinatesUtil().packLocation(location)
        }
    }

    private fun sendMissingLocation(mPackage: StardustPackage, clientConnection : ClientConnection, isDemandAck : Boolean = false,
                                    isHR : StardustControlByte.StardustDeliveryType = StardustControlByte.StardustDeliveryType.RD1, opCode : StardustPackageUtils.StardustOpCode? = null) {
        // TODO: send Cant find location ToPreviousDevice
        // TODO: change xor check
        Scopes.getDefaultCoroutine().launch {
            val bittelPackageToReturn = StardustPackageUtils.getStardustPackage(
                source = mPackage.getDestAsString() , destenation = mPackage.getSourceAsString(), stardustOpCode =
                if(opCode == null) mPackage.stardustOpCode else opCode, data =  CoordinatesUtil().packEmptyLocation()
            )

            bittelPackageToReturn.stardustControlByte.stardustAcknowledgeType = if(isDemandAck) StardustControlByte.StardustAcknowledgeType.DEMAND_ACK else StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            bittelPackageToReturn.stardustControlByte.stardustDeliveryType = isHR
            clientConnection.sendMessage(bittelPackageToReturn)
        }
    }

    internal fun sendLocation(mPackage: StardustPackage, location: Location, clientConnection : ClientConnection, isDemandAck : Boolean = false,
                             isHR : StardustControlByte.StardustDeliveryType = StardustControlByte.StardustDeliveryType.RD1, opCode : StardustPackageUtils.StardustOpCode? = null) {
        // TODO: change xor check

        Scopes.getDefaultCoroutine().launch {
            val bittelPackageToReturn = StardustPackageUtils.getStardustPackage(
                source = mPackage.getDestAsString() , destenation = mPackage.getSourceAsString(), stardustOpCode =
                if(opCode == null) StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION else opCode,
                data =
                CoordinatesUtil().packLocation(location)
            )
            val id = Random.nextLong(Long.MAX_VALUE)
            bittelPackageToReturn.stardustControlByte.stardustAcknowledgeType = if(isDemandAck) StardustControlByte.StardustAcknowledgeType.DEMAND_ACK else StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            bittelPackageToReturn.isDemandAck = isDemandAck
            bittelPackageToReturn.idNumber = id
            bittelPackageToReturn.stardustControlByte.stardustDeliveryType = isHR

            clientConnection.sendMessage(bittelPackageToReturn)

            val text = "latitude : ${location.latitude.getAfterDot(4)}\n" +
                    "longitude : ${location.longitude.getAfterDot(6)}\naltitude : ${location.altitude.getAfterDot(0)}"
            saveLocationSent(sender = mPackage.getDestAsString(), chatId = mPackage.getSourceAsString() , locationText = text, senderName = "" , idNumber = id)
            Scopes.getMainCoroutine().launch {
                val logObject = LogUtils.getLogObject(src = mPackage.getDestAsString(), dst = mPackage.getSourceAsString()
                    , event = LOG_EVENT.LOCATION_SENT.type ,location = location)
                LogUtils.saveLog(logObject, context)
            }
        }
    }

    suspend fun saveLocationSent (sender : String, locationText : String, senderName : String, chatId : String, isDemandAck: Boolean = false, idNumber : Long = 0) {
        val message = MessageItem(senderID = sender, text = locationText, epochTimeMs =  Date().time ,
            senderName = senderName, chatId = chatId, isLocation = true, seen = if(chatId != "00000002") SeenStatus.SENT else SeenStatus.SEEN)
        message.isAck = isDemandAck
        message.idNumber = idNumber
        message.senderName = getSenderName(message.senderID)
        MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).addContact(message)
        val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
        var senderObj : ChatItem? = null
        senderObj = chatsRepo.getChatByBittelID(chatId)
        senderObj?.let {
            message.senderName = it.name
//            MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).addContact(message)
            it.message = Message(senderID = sender, text = "Location Sent", seen = true)
            chatsRepo.addChat(it)
            val numOfUnread = it.numOfUnseenMessages
            chatsRepo.updateNumOfUnseenMessages(sender, numOfUnread+1)
        }
    }

    fun Double.getAfterDot (numAfterDot : Int): String {
        return String.format("%.${numAfterDot}f", this)
    }
}


