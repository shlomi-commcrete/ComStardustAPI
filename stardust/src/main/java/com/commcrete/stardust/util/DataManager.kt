package com.commcrete.stardust.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.bittell.util.demo.DemoDataUtil
import com.commcrete.bittell.util.text_utils.createDataByteArray
import com.commcrete.bittell.util.text_utils.getAsciiValue
import com.commcrete.bittell.util.text_utils.getIsAck
import com.commcrete.bittell.util.text_utils.getIsPartType
import com.commcrete.bittell.util.text_utils.splitMessage
import com.commcrete.stardust.StardustAPI
import com.commcrete.stardust.StardustAPICallbacks
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ai.codec.AIModuleInitializer
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.BleScanner
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.crypto.SecureKeyUtils
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.location.PollingUtils
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.beetle_users.BittelUserDatabase
import com.commcrete.stardust.room.beetle_users.BittelUserRepository
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.contacts.ContactsRepository
import com.commcrete.stardust.room.friends.FriendsDatabase
import com.commcrete.stardust.room.friends.FriendsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustPackageHandler
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.connectivity.PortUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.random.Random

@SuppressLint("StaticFieldLeak")
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
    var pluginContext: Context? = null

    private var savePTTFiles: Boolean? = null
    private var exportDataOnLogout: Boolean? = null

    private var hasTimber = false
    var isPlayPttFromSdk = true

    fun requireContext (context: Context) {
        this.context = context
//       if(!hasTimber) { // TODO: for debug only
//            Timber.plant(Timber.DebugTree())
//            hasTimber = true
//        }
        SharedPreferencesUtil.getIsErased(context).let {
            if(it) {
                throw IllegalStateException("Device is erased, please reset the device")
            }
        }
    }

    internal fun getClientConnection (context: Context) : ClientConnection {
        requireContext(context)
        BleManager.initBleConnectState(context)
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
        requireContext(context)
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

    internal fun getPollingUtils (context: Context) : PollingUtils{
        requireContext(context)
        if(pollingUtils == null){
            pollingUtils = PollingUtils(context)
        }
        return pollingUtils!!
    }

    override fun sendMessage(context: Context, stardustAPIPackage: StardustAPIPackage, text: String) {
        requireContext(context)
        val data = StardustPackageUtils.byteArrayToIntArray(createDataByteArray(
            getAsciiValue(text) ))
        val splitData = splitMessage(data)
        val id = Random.nextLong(Long.MAX_VALUE)

        val messageNum = 1
        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, FunctionalityType.TEXT)  ?: return
        Scopes.getDefaultCoroutine().launch {
            for (split in splitData) {
                val mPackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = stardustAPIPackage.source,
                    destenation = stardustAPIPackage.destination,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_MESSAGE,
                    data =  split)
                mPackage.stardustControlByte.stardustAcknowledgeType = getIsAck(messageNum, splitData.size, isAck = stardustAPIPackage.requireAck)
                mPackage.stardustControlByte.stardustPartType = getIsPartType(messageNum, splitData.size)
                mPackage.isDemandAck = if(messageNum == splitData.size) stardustAPIPackage.requireAck else false
                mPackage.messageNumber = splitData.size
                mPackage.idNumber = id
                mPackage.stardustControlByte.stardustDeliveryType = radio.second
                sendDataToBle(mPackage)
                delay(if(radio.first == null || radio.first!!.type == StardustConfigurationParser.StardustTypeFunctionality.HR)800 else 4000)
            }
        }
        saveSentMessage(context, text, userId = stardustAPIPackage.destination, sender = stardustAPIPackage.source)
    }

    private fun saveSentMessage (context: Context, text :String, userId : String, sender: String){
        requireContext(context)
        val messageItem = MessageItem(chatId = userId, text = text, epochTimeMs = Date().time, senderID = sender)
        Scopes.getDefaultCoroutine().launch {
            val chatsRepo = getChatsRepo(context)
            val messagesRepository = getMessagesRepo(context)
            val chatItem = chatsRepo.getChatByBittelID(userId)
            chatItem?.message = Message(
                senderID = userId,
                text = text,
                seen = false
            )
            chatItem?.let { chatsRepo.addChat(it) }
            messagesRepository.addContact(messageItem)
        }
    }

    fun setMPluginContext (context: Context) {
        this.pluginContext = context
//        PttSendManager.init(DataManager.context, DataManager.pluginContext ?: DataManager.context)

    }

    fun initModules (context: Context, pluginContext: Context) {
        requireContext(context)
        AIModuleInitializer.initModules(context, pluginContext)
    }
    @SuppressLint("MissingPermission")
    override fun startPTT(context: Context, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE): File? {
        requireContext(context)
        this.source = stardustAPIPackage.source
        this.destination = stardustAPIPackage.destination
        RecorderUtils.init(this)
        return RecorderUtils.startRecording(stardustAPIPackage.destination, stardustAPIPackage.carrier, codeType)
    }
    @SuppressLint("MissingPermission")
    override fun stopPTT(context: Context, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE, file: File?) {
        requireContext(context)
        RecorderUtils.stopRecording(stardustAPIPackage.destination, stardustAPIPackage.carrier, codeType, file)
    }

    override fun sendLocation(context: Context, stardustAPIPackage: StardustAPIPackage, location: Location) {
        requireContext(context)
        val stardustPackage = StardustPackageUtils.getStardustPackage(
            context = context,
            source = stardustAPIPackage.destination,
            destenation = stardustAPIPackage.source ,
            stardustOpCode = StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION)
        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, functionalityType =  FunctionalityType.LOCATION) ?: return
        LocationUtils.sendLocation(stardustPackage, location, getClientConnection(context), isHR = radio.second)
    }

    override fun sendImage(context: Context, stardustAPIPackage: StardustAPIPackage, file: File, onFileStatusChange: FileSendUtils.OnFileStatusChange
                           , fileName : String, fileExt : String) {
        requireContext(context)
        FileSendUtils.sendFile(context, stardustAPIPackage, file, StardustFileStartParser.FileTypeEnum.JPG,onFileStatusChange, fileName, fileExt)
    }

    override fun sendFile(context: Context, stardustAPIPackage: StardustAPIPackage, file: File, onFileStatusChange: FileSendUtils.OnFileStatusChange
                          , fileName : String, fileExt : String) {
        requireContext(context)
        FileSendUtils.sendFile(context, stardustAPIPackage, file, StardustFileStartParser.FileTypeEnum.TXT, onFileStatusChange, fileName, fileExt)
    }

    override fun stopSendFile(context: Context) {
        requireContext(context)
        FileSendUtils.stopSendingPackages()
    }

    override fun requestLocation(context: Context, stardustAPIPackage: StardustAPIPackage) {
        requireContext(context)
        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, functionalityType =  FunctionalityType.LOCATION) ?: return
        val stardustPackage = StardustPackageUtils.getStardustPackage(
            context = context,
            source = stardustAPIPackage.destination,
            destenation = stardustAPIPackage.source,
            stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_LOCATION)
        stardustPackage.stardustControlByte.stardustDeliveryType = radio.second
        Scopes.getDefaultCoroutine().launch {
            sendDataToBle(stardustPackage)
        }
    }

    override fun sendSOS(context: Context, stardustAPIPackage: StardustAPIPackage, location: Location, type: Int) {
        requireContext(context)
        Scopes.getDefaultCoroutine().launch {
            SOSUtils.sendAlert(type, context = context, location = location, stardustAPIPackage = stardustAPIPackage)
        }
    }

    override fun sendRealSOS(context: Context, stardustAPIPackage: StardustAPIPackage, location: Location) {
        requireContext(context)
        Scopes.getDefaultCoroutine().launch {
            SOSUtils.sendSos(context = context, location = location, stardustAPIPackage = stardustAPIPackage)
        }
    }

    override fun AckSOS(context: Context, stardustAPIPackage: StardustAPIPackage) {
        requireContext(context)
        Scopes.getDefaultCoroutine().launch {
            SOSUtils.ackSOS(context = context, stardustAPIPackage = stardustAPIPackage)
        }
    }

    override fun setSecurityKey(context: Context, key: String, name : String) {
        requireContext(context)
        SecureKeyUtils.setSecuredKey(context, key, name)
    }

    override fun setSecurityKeyDefault(context: Context) {
        requireContext(context)
        SecureKeyUtils.setSecuredKeyDefault(context)
    }

    override fun getSecurityKey(context: Context): ByteArray {
        requireContext(context)
        return SecureKeyUtils.getSecuredKey(context)
    }

    override fun reconnectToCurrentDevice(context: Context) {
        requireContext(context)
        getClientConnection(context).reconnectToDeviceFast()
    }

    override fun canRecord(context: Context): MutableLiveData<Boolean> {
        return RecorderUtils.canRecord
    }


    override fun init(context: Context, fileLocation : String) {
        requireContext(context)
        requireFileLocation(fileLocation)
    }

    override fun scanForDevice(context: Context) : MutableLiveData<List<ScanResult>> {
        requireContext(context)
        val bleScanner = getBleScanner(this.context)
        bleScanner.startScan()
        return bleScanner.getScanResultsLiveData()
    }

    fun getStartupBleData (context: Context) : BluetoothDevice? {
        requireContext(context)
        val savedAddress = SharedPreferencesUtil.getBittelDevice(context)
        if(savedAddress.isNullOrEmpty()) {
            val device =  getPairedDevices(context)
            device?.let {
                return it
            }
        } else {
            val device = getClientConnection(context).getBleConnectedStardustDeviceBySavedAddress(savedAddress)
            device?.let {
                return it
            }
        }
        return null
    }

     fun getPairedDevices (context: Context) : BluetoothDevice?{
        requireContext(context)
        return getClientConnection(context).getBleConnectedStardustDevice()
    }

    fun getConnectedDevices (context: Context) : BluetoothDevice?{
        requireContext(context)
        if(getClientConnection(context).mDevice != null) {
            return getClientConnection(context).mDevice
        } else {
            return getClientConnection(context).getBlePairedStardustDevice()
        }
    }

    fun bondOnStartup (context: Context) {
        requireContext(context)

        val bondedDevice = getPairedDevices(context)
        if(bondedDevice != null) {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)
            getClientConnection(context).bondToBleDeviceStartup(bondedDevice)
        } else {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SUCCESS)
        }
    }

    override fun connectToDevice(context: Context, device: ScanResult) {
        requireContext(context)
        StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.IDLE)
        getClientConnection(context).bondToBleDevice(device.device,device.scanRecord?.deviceName )
        val bleScanner = getBleScanner(this.context)
        bleScanner.stopScan()
        this.bleScanner = null
    }

    override fun disconnectFromDevice(context: Context) {
        requireContext(context)
        getClientConnection(context).disconnectFromBLEDevice()
    }

    override fun readChats(context: Context): LiveData<List<ChatItem>> = liveData(Dispatchers.IO) {
        val chats = getChatsRepo(context).getAllChats ()
        emit(chats)
    }

    override fun logout(context: Context, withDelete: Boolean) {
        requireContext(context)
        Scopes.getDefaultCoroutine().launch {
            UsersUtils.logout(withDelete)
        }
    }

    override fun setCallback(stardustAPICallbacks: StardustAPICallbacks) {
        this.stardustAPICallbacks = stardustAPICallbacks
    }

    override fun getCarriers(context: Context): List<Carrier>? {
        requireContext(context)
        return CarriersUtils.setLocalCarrierList ()
    }

    override fun getSource(): String {
        return this.source ?: ""
    }

    override fun getDestenation(): String? {
        return this.destination ?: ""
    }

    fun getUserUtils (context: Context) : UsersUtils {
        requireContext(context)
        return UsersUtils
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

    fun getBleManager (context: Context): BleManager {
        requireContext(context)
        return BleManager
    }

    fun unpairDeviceBLE (context: Context) {
        requireContext(context)
        val clientConnection = getClientConnection(context)
        clientConnection.removeBittelBond()
    }

    fun getPortUtils (context: Context): PortUtils {
        requireContext(context)
        return PortUtils
    }

    fun getSharedPreferences (context: Context): SharedPreferencesUtil {
        requireContext(context)
        return SharedPreferencesUtil
    }

    fun getFolderReader (context: Context): FolderReader {
        requireContext(context)
        return FolderReader
    }

    fun getDataUtil (context: Context): DemoDataUtil {
        requireContext(context)
        return DemoDataUtil
    }

    fun getPlayerUtils (context: Context): PlayerUtils {
        requireContext(context)
        return PlayerUtils
    }

    fun getChatsRepo (context: Context) : ChatsRepository {
        requireContext(context)
        return ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
    }

    fun getMessagesRepo (context: Context) : MessagesRepository {
        requireContext(context)
        return MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao())
    }

    fun getCallbacks() : StardustAPICallbacks? {
        return stardustAPICallbacks
    }

    fun getExportFilesOnLogoutRequired(context: Context): Boolean {
        if(exportDataOnLogout == null) {
            exportDataOnLogout = SharedPreferencesUtil.getExportDataOnLogout(context)
        }
        return exportDataOnLogout != false
    }

    fun updateExportFilesOnLogoutRequired(context: Context, isRequired: Boolean) {
        exportDataOnLogout = isRequired
        SharedPreferencesUtil.setExportDataOnLogout(context, isRequired)
    }

    fun getSavePTTFilesRequired(context: Context): Boolean {
        if(savePTTFiles == null) {
            savePTTFiles = SharedPreferencesUtil.getSavePTTFiles(context)
        }
        return savePTTFiles != false
    }

    fun updateSavePTTFilesRequired(context: Context, isRequired: Boolean) {
        savePTTFiles = isRequired
        SharedPreferencesUtil.setSavePTTFiles(context, isRequired)
    }

    fun initRemoteConfig(context: Context) {
        requireContext(context)
        RemoteConfigUtils.initLocalDefaults(context)
    }

    suspend fun cleanAllDatabases(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val user = async {
                    BittelUserRepository(
                        BittelUserDatabase.getDatabase(context).bittelUserDao()
                    ).clearData()
                }

                val chats = async {
                    ChatsRepository(
                        ChatsDatabase.getDatabase(context).chatsDao()
                    ).clearData()
                }

                val contacts = async {
                    ContactsRepository(
                        ContactsDatabase.getDatabase(context).contactsDao()
                    ).clearData()
                }

                val friends = async {
                    FriendsRepository(
                        FriendsDatabase.getDatabase(context).friendsDao()
                    ).clearData()
                }

                val messages = async {
                    MessagesRepository(
                        MessagesDatabase.getDatabase(context).messagesDao()
                    ).clearData()
                }

                user.await() && chats.await() && contacts.await() && friends.await() && messages.await()
            }
        }

    suspend fun archiveAllDatabases(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            coroutineScope {

//                val chats = async {
//                    ChatsRepository(
//                        ChatsDatabase.getDatabase(context).chatsDao()
//                    ).archiveData()
//                }
//
//                val contacts = async {
//                    ContactsRepository(
//                        ContactsDatabase.getDatabase(context).contactsDao()
//                    ).archiveData()
//                }
//
//                val messages = async {
//                    MessagesRepository(
//                        MessagesDatabase.getDatabase(context).messagesDao()
//                    ).archiveData()
//                }
//
//                chats.await() && contacts.await() && messages.await()
                true
            }
        }

}