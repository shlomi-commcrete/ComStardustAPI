package com.commcrete.stardust.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import com.commcrete.bittell.util.text_utils.createDataByteArray
import com.commcrete.bittell.util.text_utils.getAsciiValue
import com.commcrete.bittell.util.text_utils.getIsAck
import com.commcrete.bittell.util.text_utils.getIsPartType
import com.commcrete.bittell.util.text_utils.splitMessage
import com.commcrete.stardust.StardustAPI
import com.commcrete.stardust.StardustAPICallbacks
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ble.BleScanner
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.location.PollingUtils
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.StardustPackageHandler
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date
import kotlin.random.Random

object DataManager : StardustAPI, PttInterface{

    private var clientConnection : ClientConnection?  = null
    private var bittelusbManager : BittelUsbManager2?  = null
    private var bittelPackageHandler : StardustPackageHandler? = null
    private var pollingUtils : PollingUtils? = null
    lateinit var context : Context
    lateinit var fileLocation : String
    private var bleScanner : BleScanner? = null
    private var source : String? = null
    private var destination : String? = null
    private var stardustAPICallbacks : StardustAPICallbacks? = null

    private var hasTimber = false
    var isPlayPttFromSdk = true

    fun requireContext (context: Context){
        this.context = context
        if(!hasTimber) {
            Timber.plant(Timber.DebugTree())
        }
    }

    internal fun getClientConnection (context: Context) : ClientConnection {
        requireContext(context)
        if(clientConnection == null) {
            clientConnection = ClientConnection(context = context)
        }

        getStardustPackageHandler(context)

        return clientConnection!!
    }

    internal fun getUsbManager (context: Context) : BittelUsbManager2 {
        requireContext(context)
        if(bittelusbManager == null) {
            bittelusbManager =
                BittelUsbManager2
            bittelusbManager?.init(context)
        }


        return bittelusbManager!!
    }

    fun getLocationUtils (context: Context) : LocationUtils {
        LocationUtils.init(context)
        return LocationUtils
    }

    internal fun getStardustPackageHandler(context: Context): StardustPackageHandler {
        requireContext(context)
        if(bittelPackageHandler == null){
            bittelPackageHandler = StardustPackageHandler(context, clientConnection)
        }
        return bittelPackageHandler!!
    }

    internal fun getPollingUtils () : PollingUtils{
        if(pollingUtils == null){
            pollingUtils = PollingUtils(context)
        }
        return pollingUtils!!
    }

    override fun sendMessage(stardustAPIPackage: StardustAPIPackage, text: String) {
        val data = StardustPackageUtils.byteArrayToIntArray(createDataByteArray(
            getAsciiValue(text) ))
        val splitData = splitMessage(data)
        val id = Random.nextLong(Long.MAX_VALUE)

        val messageNum = 1
        // TODO: check
        Scopes.getDefaultCoroutine().launch {
            for (split in splitData) {
                val mPackage = StardustPackageUtils.getStardustPackage(
                    source = stardustAPIPackage.source , destenation = stardustAPIPackage.destination, stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_MESSAGE, data =  split)
                mPackage.stardustControlByte.stardustAcknowledgeType = getIsAck(messageNum, splitData.size, isAck = stardustAPIPackage.requireAck)
                mPackage.stardustControlByte.stardustPartType = getIsPartType(messageNum, splitData.size)
                mPackage.isDemandAck = if(messageNum == splitData.size) stardustAPIPackage.requireAck else false
                mPackage.messageNumber = splitData.size
                mPackage.idNumber = id
                mPackage.stardustControlByte.stardustDeliveryType = if (stardustAPIPackage.isLR == true) StardustControlByte.StardustDeliveryType.LR else StardustControlByte.StardustDeliveryType.HR
                sendDataToBle(mPackage)
                delay(if(stardustAPIPackage.isLR == true)4000 else 800)
            }
        }
        saveSentMessage(text, userId = stardustAPIPackage.destination, sender = stardustAPIPackage.source)
    }

    private fun saveSentMessage (text :String, userId : String, sender: String){
        val messageItem = MessageItem(chatId = userId, text = text, epochTimeMs = Date().time, senderID = sender)
        Scopes.getDefaultCoroutine().launch {
            val chatsRepo = getChatsRepo()
            val messagesRepository = getMessagesRepo()
            Scopes.getDefaultCoroutine().launch{
//                chatsRepo.addChat(chatItem)
                messagesRepository.addContact(messageItem)
            }
        }
    }
    @SuppressLint("MissingPermission")
    override fun startPTT(stardustAPIPackage: StardustAPIPackage) {
        this.source = stardustAPIPackage.source
        this.destination = stardustAPIPackage.destination
        RecorderUtils.init(this)
        RecorderUtils.onRecord(true, stardustAPIPackage.destination)
    }
    @SuppressLint("MissingPermission")
    override fun stopPTT(stardustAPIPackage: StardustAPIPackage) {
        RecorderUtils.onRecord(false, stardustAPIPackage.destination)
    }

    override fun sendLocation(stardustAPIPackage: StardustAPIPackage, location: Location) {
        val stardustPackage = StardustPackageUtils.getStardustPackage(source = stardustAPIPackage.destination, destenation = stardustAPIPackage.source , stardustOpCode = StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION)
        LocationUtils.sendLocation(stardustPackage, location, getClientConnection(context))
    }

    override fun requestLocation(stardustAPIPackage: StardustAPIPackage) {
        val stardustPackage = StardustPackageUtils.getStardustPackage(source = stardustAPIPackage.destination, destenation = stardustAPIPackage.source , stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_LOCATION)
        Scopes.getDefaultCoroutine().launch {
            sendDataToBle(stardustPackage)
        }
    }

    override fun sendSOS(stardustAPIPackage: StardustAPIPackage, location: Location, type: Int) {
        Scopes.getDefaultCoroutine().launch {
            SOSUtils.sendSos(type, context = context, location = location)
        }
    }


    override fun init(context: Context, fileLocation : String) {
        requireContext(context)
        requireFileLocation(fileLocation)
    }

    override fun scanForDevice() : MutableLiveData<List<ScanResult>> {
        val bleScanner = getBleScanner(this.context)
        bleScanner.startScan()
        return bleScanner.getScanResultsLiveData()
    }

    override fun connectToDevice(device: BluetoothDevice) {
        getClientConnection(context).connectDevice(device)
        val bleScanner = getBleScanner(this.context)
        bleScanner.stopScan()
        this.bleScanner = null
    }

    override fun disconnectFromDevice() {
        getClientConnection(context).disconnectFromDevice()
    }

    override fun readChats(): LiveData<List<ChatItem>> = liveData(Dispatchers.IO) {
        val chats = getChatsRepo().getAllChats ()
        emit(chats)
    }

    override fun logout() {
        Scopes.getDefaultCoroutine().launch {
            UsersUtils.logout()
        }
    }

    override fun setCallback(stardustAPICallbacks: StardustAPICallbacks) {
        this.stardustAPICallbacks = stardustAPICallbacks
    }

    override fun getSource(): String {
        return this.source ?: ""
    }

    override fun getDestenation(): String? {
        return this.destination ?: ""
    }

    override fun sendDataToBle(bittelPackage: StardustPackage) {
        getClientConnection(context).addMessageToQueue(bittelPackage)
    }

    fun requireFileLocation (location : String) {
        this.fileLocation = location
    }

    fun getBleScanner (context: Context): BleScanner {
        requireContext(context)
        if(this.bleScanner == null) {
            bleScanner = BleScanner(context)
        }
        return bleScanner!!
    }

    fun getChatsRepo () : ChatsRepository {
        return ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
    }

    fun getMessagesRepo () : MessagesRepository {
        return MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao())
    }

    fun getCallbacks () : StardustAPICallbacks? {
        return stardustAPICallbacks
    }

    fun initRemoteConfig(context: Context) {
        RemoteConfigUtils.initLocalDefaults(context)
    }
}