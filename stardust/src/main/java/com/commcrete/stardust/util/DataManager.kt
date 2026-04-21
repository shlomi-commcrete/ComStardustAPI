package com.commcrete.stardust.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.util.ContactsFileParserUtil
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
import com.commcrete.stardust.room.RepositoryProvider
import com.commcrete.stardust.room.new_db.AppRepository
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustPackageHandler
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.FileSender.OnFileStatusChange
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.connectivity.PortUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
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

    var isPlayPttFromSdk = true

    private val fileSenders = ConcurrentHashMap<String, FileSender>() // <uuid, FileSender>

    fun requireContext (context: Context) {
        this.context = context
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

    internal fun getStardustPackageHandler(context: Context): StardustPackageHandler {
        requireContext(context)
        if(bittelPackageHandler == null) {
            bittelPackageHandler = StardustPackageHandler(context, clientConnection)
        }
        return bittelPackageHandler!!
    }

    internal fun getPollingUtils(context: Context) : PollingUtils{
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

        val messageNum = 1
        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, FunctionalityType.TEXT)  ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val id = saveSentMessage(context, text, receiver = stardustAPIPackage.receiverId, sender = stardustAPIPackage.senderId, groupId = stardustAPIPackage.groupId)

            for (split in splitData) {
                val mPackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = stardustAPIPackage.senderId,
                    destination = stardustAPIPackage.receiverId,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_MESSAGE,
                    data = split)
                mPackage.stardustControlByte.stardustAcknowledgeType = getIsAck(messageNum, splitData.size, isAck = stardustAPIPackage.requireAck)
                mPackage.stardustControlByte.stardustPartType = getIsPartType(messageNum, splitData.size)
                mPackage.isDemandAck = if(messageNum == splitData.size) stardustAPIPackage.requireAck else false
                mPackage.messageNumber = splitData.size
                mPackage.idNumber = id
                mPackage.stardustControlByte.stardustDeliveryType = radio.second
                sendDataToBle(mPackage)
                delay(if(radio.first.type == StardustConfigurationParser.StardustTypeFunctionality.HR) 800 else 4000)
            }
        }
    }

    private suspend fun saveSentMessage(context: Context, text: String, receiver: String, sender: String, groupId: String? = null): Long? {
        return getAppRepo(context).saveMessage(
            message = MessageEntity(
                senderID = sender,
                receiverID = receiver,
                state = MessageState.SENT,
                extraData = MessageExtraData.Text(text = text)
            ),
            groupId = groupId
        )
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
    override fun startPTT(context: Context, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.AudioEncoderType): File? {
        requireContext(context)
        this.source = stardustAPIPackage.senderId
        this.destination = stardustAPIPackage.receiverId
        RecorderUtils.init(this)
        return RecorderUtils.startRecording(stardustAPIPackage.receiverId, stardustAPIPackage.carrier, codeType)
    }
    @SuppressLint("MissingPermission")
    override fun stopPTT(context: Context, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.AudioEncoderType, file: File) {
        requireContext(context)
        RecorderUtils.stopRecording(receiverId = stardustAPIPackage.receiverId, carrier = stardustAPIPackage.carrier, codeType = codeType, file = file)
    }

    override fun sendLocation(context: Context, stardustAPIPackage: StardustAPIPackage, location: Location) {
        requireContext(context)
        val stardustPackage = StardustPackageUtils.getStardustPackage(
            context = context,
            source = stardustAPIPackage.senderId,
            destination = stardustAPIPackage.receiverId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION)
        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, functionalityType =  FunctionalityType.LOCATION) ?: return
        LocationUtils.sendLocation(context, stardustPackage, location, getClientConnection(context), isHR = radio.second)
    }

    override fun sendImage(
        context: Context,
        data: FileUtils.FileTransferData.Send,
        onFileStatusChange: OnFileStatusChange
    ) {
        onSendFile(context, data, onFileStatusChange)
    }

    override fun sendFile(
        context: Context,
        data: FileUtils.FileTransferData.Send,
        onFileStatusChange: OnFileStatusChange
    ) {
        onSendFile(context, data, onFileStatusChange)
    }

    private fun onSendFile(
        context: Context,
        data: FileUtils.FileTransferData.Send,
        onFileStatusChange: OnFileStatusChange
    ) {
        requireContext(context)
        val sender = FileSender(context, data)
        fileSenders.put(data.id, sender)
        sender.sendFile(object : OnFileStatusChange {
            override fun finishSending(data: FileUtils.FileTransferData.Send) {
                super.finishSending(data)
                onFileStatusChange.finishSending(data)
                fileSenders.remove(data.id)
            }

            override fun startSending(data: FileUtils.FileTransferData.Send) {
                super.startSending(data)
                onFileStatusChange.startSending(data)
            }

            override fun stopSending(data: FileUtils.FileTransferData.Send) {
                super.stopSending(data)
                onFileStatusChange.stopSending(data)
                fileSenders.remove(data.id)
            }

            override fun updateStep(data: FileUtils.FileTransferData.Send, percentage: Int) {
                super.updateStep(data, percentage)
                onFileStatusChange.updateStep(data, percentage)
            }
        })
    }

    override fun stopSendFile(context: Context, data: FileUtils.FileTransferData.Send) {
        requireContext(context)
        fileSenders[data.id]?.stopSendingPackages()
    }

    override fun requestLocation(context: Context, stardustAPIPackage: StardustAPIPackage) {
        requireContext(context)
        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, functionalityType =  FunctionalityType.LOCATION) ?: return
        val stardustPackage = StardustPackageUtils.getStardustPackage(
            context = context,
            source = stardustAPIPackage.senderId,
            destination = stardustAPIPackage.receiverId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_LOCATION)
        stardustPackage.stardustControlByte.stardustDeliveryType = radio.second
        Scopes.getDefaultCoroutine().launch {
            sendDataToBle(stardustPackage)
        }
    }

    override fun sendSOS(context: Context, stardustAPIPackage: StardustAPIPackage, location: Location, type: SOSUtils.SOS_REPORT_TYPES?) {
        requireContext(context)
        CoroutineScope(Dispatchers.IO).launch {
            SOSUtils.sendAlert(context = context, location = location, sosType = type, stardustAPIPackage = stardustAPIPackage)
        }
    }

    override fun sendRealSOS(context: Context, location: Location) {
        requireContext(context)
        CoroutineScope(Dispatchers.IO).launch {
            SOSUtils.sendSos(context = context, location = location)
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
        RegisteredUserUtils.mRegisterUser = SharedPreferencesUtil.getAppUser(context)
        GroupsUtils.resetLocalGroupIds(context)
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

    fun getConnectedDevices (context: Context) : BluetoothDevice? {
        requireContext(context)
        val connection = getClientConnection(context)
        return connection.mDevice ?: connection.getBlePairedStardustDevice()
    }

    fun bondOnStartup (context: Context) {
        requireContext(context)

        val bondedDevice = getPairedDevices(context)
        if(bondedDevice != null) {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)
            getClientConnection(context).bondToBleDeviceStartup(bondedDevice)
        } else {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.IDLE)
        }
    }

    override fun connectToDevice(context: Context, device: ScanResult) {
        requireContext(context)
        getClientConnection(context).bondToBleDevice(device.device,device.scanRecord?.deviceName )
        val bleScanner = getBleScanner(this.context)
        bleScanner.stopScan()
        this.bleScanner = null
    }

    override fun disconnectFromDevice(context: Context) {
        requireContext(context)
        cleanupPackageHandlerOnDisconnect()
        getClientConnection(context).disconnectFromBLEDevice()
    }

    override fun logout(context: Context) {
        requireContext(context)
        Scopes.getDefaultCoroutine().launch {
            RegisteredUserUtils.logout()
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

    override fun getDestination(): String {
        return this.destination ?: ""
    }

    fun getUserUtils (context: Context) : RegisteredUserUtils {
        requireContext(context)
        return RegisteredUserUtils
    }

    override fun sendDataToBle(pkg: StardustPackage) {
        getClientConnection(context).addMessageToQueue(pkg)
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
        cleanupPackageHandlerOnDisconnect()
        val clientConnection = getClientConnection(context)
        clientConnection.removeBittelBond()
    }

    private fun cleanupPackageHandlerOnDisconnect() {
        bittelPackageHandler?.cleanupOnDisconnect()
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

    fun getDataUtil (context: Context): ContactsFileParserUtil {
        requireContext(context)
        return ContactsFileParserUtil
    }

    fun getPlayerUtils (context: Context): PlayerUtils {
        requireContext(context)
        return PlayerUtils
    }

    fun getAppRepo(context: Context): AppRepository {
        requireContext(context)
        return RepositoryProvider.appRepository(context)
    }


    fun getCallbacks() : StardustAPICallbacks? {
        return stardustAPICallbacks
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

    suspend fun cleanAllDatabases(context: Context): Boolean = getAppRepo(context).clearData()

    suspend fun deleteChatFiles(
        context: Context
    ): CleanResult = withContext(Dispatchers.IO) {

        withProcessFileLock(context, "clean_user_files") {

            val dirs = FileUtils.getAllChatFilesDirs(context)
            val failed = mutableListOf<File>()

            dirs.forEachIndexed { index, dir ->
                try {
                    if (dir.exists() && !dir.deleteRecursively()) {
                        failed += dir
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed deleting ${dir.absolutePath}")
                    failed += dir
                }
            }


            CleanResult(
                success = failed.isEmpty(),
                failedDirs = failed
            )
        }
    }

    inline fun <T> withProcessFileLock(
        context: Context,
        lockName: String,
        block: () -> T
    ): T {
        val lockFile = File(context.filesDir, "$lockName.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                return block()
            }
        }
    }

    data class CleanResult(
        val success: Boolean,
        val failedDirs: List<File>
    )
}