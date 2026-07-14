package com.commcrete.stardust.util

import android.content.Context
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.location.Location
import androidx.lifecycle.MutableLiveData
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
import com.commcrete.stardust.ble.CompanionDeviceHelper
import com.commcrete.stardust.ble.PairingRepository
import androidx.lifecycle.asFlow
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("StaticFieldLeak")
object DataManager : StardustAPI, PttInterface {

    private var clientConnection : ClientConnection?  = null
    private var bittelusbManager : BittelUsbManager2?  = null
    private var bittelPackageHandler : StardustPackageHandler? = null
    private var pollingUtils : PollingUtils? = null

    lateinit var appContext : Context
    lateinit var pluginContext: Context
    lateinit var fileLocation : String

    private var bleScanner : BleScanner? = null
    private var source : String? = null
    private var chatId : String? = null
    private var destination : String? = null
    private var stardustAPICallbacks : StardustAPICallbacks? = null

    private var savePTTFiles: Boolean? = null

    var isPlayPttFromSdk = true

    private val fileSenders = ConcurrentHashMap<String, FileSender>() // <uuid, FileSender>

    /** Must be called once via [init] before any other DataManager function is used. */
    private fun initAppContext(appContext: Context) {
        val normalized = appContext.applicationContext
        if (!this::appContext.isInitialized) {
            this.appContext = normalized
        } else if (this.appContext !== normalized) {
            Timber.w("DataManager already has a context. Keeping the original app context.")
        }
    }

    private fun initPluginContext(pluginContext: Context) {
        this.pluginContext = pluginContext
    }

    /** Call at the start of every public function to ensure [init] was called first. */
    private fun checkInitialized() {
        check(this::appContext.isInitialized) {
            "DataManager is not initialized. Call DataManager.init(appContext, pluginContext) first."
        }
    }

    override fun init(appContext: Context, pluginContext: Context, fileLocation: String) {
        initAppContext(appContext)
        initPluginContext(pluginContext)
        RegisteredUserUtils.updateRegisteredUser(SharedPreferencesUtil.getAppUser())

        SharedPreferencesUtil.getIsErased().let {
            if (it) throw IllegalStateException("Device is erased, please reset the device")
        }


        requireFileLocation(fileLocation)
        AIModuleInitializer.initModules()

        runCatching {
            val debugOutFile = File(DataManager.appContext.cacheDir, "decoded_data.txt")
            if (debugOutFile.exists()) debugOutFile.delete()
        }
    }


    internal fun getClientConnection() : ClientConnection {
        checkInitialized()
        BleManager.initBleConnectState()
        if(clientConnection == null) {
            clientConnection = ClientConnection()
        }
        getStardustPackageHandler()
        return clientConnection!!
    }

    internal fun getUsbManager() : BittelUsbManager2 {
        checkInitialized()
        if(bittelusbManager == null) {
            bittelusbManager = BittelUsbManager2
            bittelusbManager?.init()
        }
        return bittelusbManager!!
    }

    internal fun getStardustPackageHandler(): StardustPackageHandler {
        checkInitialized()
        if(bittelPackageHandler == null) {
            bittelPackageHandler = StardustPackageHandler(clientConnection)
        }
        return bittelPackageHandler!!
    }

    internal fun getPollingUtils(): PollingUtils{
        checkInitialized()
        if(pollingUtils == null){
            pollingUtils = PollingUtils()
        }
        return pollingUtils!!
    }

    override fun sendMessage(chatId: String, stardustAPIPackage: StardustAPIPackage, text: String) {
        checkInitialized()
        CoroutineScope(Dispatchers.IO).launch {
            val data = StardustPackageUtils.byteArrayToIntArray(createDataByteArray(
                getAsciiValue(text) ))
            val splitData = splitMessage(data)

            val messageNum = 1
            val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, FunctionalityType.TEXT) ?: return@launch

            val id = getAppRepo().saveMessage(
                message = MessageEntity(
                    chatId = chatId,
                    senderID = stardustAPIPackage.senderId,
                    receiverID = stardustAPIPackage.receiverId,
                    state = MessageState.SENT,
                    extraData = MessageExtraData.Text(text = text)
                ),
                groupId = stardustAPIPackage.groupId
            )
            for (split in splitData) {
                val mPackage = StardustPackageUtils.getStardustPackage(
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


    @SuppressLint("MissingPermission")
    override fun startPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE): File? {
        checkInitialized()
        this.source = stardustAPIPackage.senderId
        this.destination = stardustAPIPackage.receiverId
        this.chatId = chatId
        RecorderUtils.init(this)
        return RecorderUtils.startRecording(chatId, stardustAPIPackage.receiverId, stardustAPIPackage.carrier, codeType)
    }

    @SuppressLint("MissingPermission")
    override fun stopPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE, file: File) {
        checkInitialized()
        RecorderUtils.stopRecording(chatId = chatId, receiverId = stardustAPIPackage.receiverId, carrier = stardustAPIPackage.carrier, codeType = codeType, file = file)
    }

    override fun sendLocation(chatId: String, stardustAPIPackage: StardustAPIPackage, location: Location) {
        checkInitialized()
        val stardustPackage = StardustPackageUtils.getStardustPackage(
            source = stardustAPIPackage.senderId,
            destination = stardustAPIPackage.receiverId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION)

        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, functionalityType = FunctionalityType.LOCATION) ?: return
        LocationUtils.sendLocation(chatId,stardustPackage, location, getClientConnection(), isHR = radio.second)
    }

    override fun sendImage(data: FileUtils.FileTransferData.Send, onFileStatusChange: OnFileStatusChange): Deferred<Boolean> {
        return onSendFile(data, onFileStatusChange)
    }

    override fun sendFile(data: FileUtils.FileTransferData.Send, onFileStatusChange: OnFileStatusChange): Deferred<Boolean> {
        return onSendFile(data, onFileStatusChange)
    }

    private fun onSendFile(data: FileUtils.FileTransferData.Send, onFileStatusChange: OnFileStatusChange): Deferred<Boolean> {
        checkInitialized()
        val sender = FileSender(data)
        fileSenders.put(data.id, sender)
        return sender.sendFile(object : OnFileStatusChange {
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

    override fun stopSendFile(data: FileUtils.FileTransferData.Send) {
        checkInitialized()
        fileSenders[data.id]?.stopSendingPackages()
    }

    override fun requestLocation(stardustAPIPackage: StardustAPIPackage) {
        checkInitialized()
        val radio = CarriersUtils.getRadioToSend(stardustAPIPackage.carrier, functionalityType = FunctionalityType.LOCATION) ?: return
        val stardustPackage = StardustPackageUtils.getStardustPackage(
            source = stardustAPIPackage.senderId,
            destination = stardustAPIPackage.receiverId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_LOCATION)
        stardustPackage.stardustControlByte.stardustDeliveryType = radio.second
        Scopes.getDefaultCoroutine().launch {
            sendDataToBle(stardustPackage)
        }
    }

    override fun sendSOS(stardustAPIPackage: StardustAPIPackage, location: Location, type: SOSUtils.SOS_REPORT_TYPES?) {
        checkInitialized()
        CoroutineScope(Dispatchers.IO).launch {
            SOSUtils.sendAlert(location = location, sosType = type, stardustAPIPackage = stardustAPIPackage)
        }
    }

    override fun sendRealSOS(location: Location) {
        checkInitialized()
        CoroutineScope(Dispatchers.IO).launch {
            SOSUtils.sendSos(location = location)
        }
    }

    override fun AckSOS(stardustAPIPackage: StardustAPIPackage) {
        checkInitialized()
        Scopes.getDefaultCoroutine().launch {
            SOSUtils.ackSOS(stardustAPIPackage = stardustAPIPackage)
        }
    }

    override fun setSecurityKey(key: String, name: String) {
        checkInitialized()
        SecureKeyUtils.setSecuredKey(key, name)
    }

    override fun setSecurityKeyDefault() {
        checkInitialized()
        SecureKeyUtils.setSecuredKeyDefault()
    }

    override fun getSecurityKey(): ByteArray {
        checkInitialized()
        return SecureKeyUtils.getSecuredKey()
    }

    override fun reconnectToCurrentDevice() {
        checkInitialized()
        getClientConnection().reconnectToDeviceFast()
    }

    override fun canRecord(): MutableLiveData<Boolean> {
        return RecorderUtils.canRecord
    }

    override fun scanForDevice() : MutableLiveData<List<ScanResult>> {
        checkInitialized()
        val bleScanner = getBleScanner()
        bleScanner.startScan()
        return bleScanner.getScanResultsLiveData()
    }

    fun getStartupBleData() : BluetoothDevice? {
        checkInitialized()
        val savedAddress = SharedPreferencesUtil.getBittelDevice()
        if(savedAddress.isNullOrEmpty()) {
            val device =  getPairedDevices()
            device?.let {
                return it
            }
        } else {
            val device = getClientConnection().getBleConnectedStardustDeviceBySavedAddress(savedAddress)
            device?.let {
                return it
            }
        }
        return null
    }

     fun getPairedDevices() : BluetoothDevice?{
        checkInitialized()
        return getClientConnection().getBleConnectedStardustDevice()
    }

    fun getConnectedDevices() : BluetoothDevice? {
        checkInitialized()
        val connection = getClientConnection()
        return connection.mDevice ?: connection.getBlePairedStardustDevice()
    }

    fun bondOnStartup() {
        checkInitialized()
        getClientConnection().initBleStatus()
        // Check if Bluetooth is enabled; if not, user will see a dialog
        if (!getClientConnection().isBluetoothEnabled()) {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.BLUETOOTH_OFF)
            return
        }

        // Reconcile app pairing with the OS bond registry first, so a stale saved address
        // (OS bond removed externally) is cleared and a still-bonded device is honored.
        PairingRepository.reconcile()

        val pairedAddress = PairingRepository.currentPairedAddress()
        if (pairedAddress != null) {
            val device = getClientConnection().getBleConnectedStardustDeviceBySavedAddress(pairedAddress)
            if (device != null) {
                StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)
                getClientConnection().bondToBleDeviceStartup(device)
                return
            }
        }

        // Not paired to a saved device. If Stardust devices are already bonded to the phone
        // (paired from Settings or another app), surface them so the user can choose to adopt
        // one instead of silently grabbing the first name match.
        val adoptable = PairingRepository.getAdoptableDevices()
        if (adoptable.isNotEmpty()) {
            // Hand off to the host. State is left for adoptDevice() to advance (to SEARCHING);
            // if the host ignores the callback the state simply stays DISCONNECTED (the default).
            getCallbacks()?.onAdoptableDevicesFound(adoptable)
        } else {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
        }
    }

    override fun getAdoptableDevices(): List<com.commcrete.stardust.AdoptableDevice> {
        checkInitialized()
        return PairingRepository.getAdoptableDevices()
    }

    override fun adoptDevice(address: String): Boolean {
        checkInitialized()
        return PairingRepository.adopt(address)
    }

    /**
     * The unified connection state (single source of truth): link + init-handshake status combined.
     * Prefer collecting this over juggling [StardustAPICallbacks.connectionStatusChanged] and
     * [StardustAPICallbacks.onDeviceInitialized] separately.
     */
    fun getConnectionState(): kotlinx.coroutines.flow.StateFlow<com.commcrete.stardust.transport.ConnectionState> =
        com.commcrete.stardust.transport.ConnectionManager.connectionState

    // ── Flow facade (Stage 5) ────────────────────────────────────────────────
    // Flow equivalents of the connection callbacks, backed by the existing LiveData. Additive:
    // the StardustAPICallbacks surface is unchanged. Messaging/PTT callbacks are intentionally
    // left as-is — this refactor is scoped to the connection layer.

    /** Emits true while a device is paired. Flow equivalent of the paired signal. */
    fun pairedFlow(): kotlinx.coroutines.flow.Flow<Boolean> = BleManager.isPaired.asFlow()

    /** Emits RSSI updates. Flow equivalent of [StardustAPICallbacks.onDeviceConnectionRSSIChanged]. */
    fun rssiFlow(): kotlinx.coroutines.flow.Flow<Int> = BleManager.rssi.asFlow()

    // ── CompanionDeviceManager (Stage 5) ─────────────────────────────────────
    // Thin pass-throughs to CompanionDeviceHelper for host discoverability. Association requires a
    // host Activity to launch the returned IntentSender and forward its result back.

    fun isCompanionDeviceSupported(): Boolean = CompanionDeviceHelper.isSupported()

    fun associateCompanionDevice(
        activity: android.app.Activity,
        onLaunch: (android.content.IntentSender) -> Unit,
        onError: (CharSequence) -> Unit,
    ) = CompanionDeviceHelper.associate(activity, onLaunch, onError)

    fun handleCompanionAssociationResult(data: android.content.Intent?): Boolean =
        CompanionDeviceHelper.handleAssociationResult(data)

    fun getCompanionAssociations(): List<String> = CompanionDeviceHelper.getAssociatedAddresses()

    override fun connectToDevice(device: ScanResult) {
        checkInitialized()
        getClientConnection().bondToBleDevice(device.device,device.scanRecord?.deviceName )
        val bleScanner = getBleScanner()
        bleScanner.stopScan()
        this.bleScanner = null
    }

    override fun disconnectFromDevice() {
        checkInitialized()
        // Intentional disconnect: stop the auto-reconnect watchdog so we don't fight the tear-down.
        com.commcrete.stardust.transport.ConnectionManager.disableAutoReconnect()
        cleanupPackageHandlerOnDisconnect()
        getClientConnection().disconnectFromBLEDevice()
    }

    override fun logout() {
        checkInitialized()
        Scopes.getDefaultCoroutine().launch {
            RegisteredUserUtils.logout()
        }
    }

    override fun setCallback(stardustAPICallbacks: StardustAPICallbacks) {
        this.stardustAPICallbacks = stardustAPICallbacks
    }

    override fun getCarriers(): List<Carrier>? {
        checkInitialized()
        return CarriersUtils.setLocalCarrierList ()
    }

    override fun getChatId(): String {
        return this.chatId ?: ""
    }

    override fun getSource(): String {
        return this.source ?: ""
    }

    override fun getDestination(): String {
        return this.destination ?: ""
    }

    fun getUserUtils() : RegisteredUserUtils {
        checkInitialized()
        return RegisteredUserUtils
    }

    override fun sendDataToBle(pkg: StardustPackage) {
        getClientConnection().addMessageToQueue(pkg)
    }

    fun requireFileLocation (location : String) {
        this.fileLocation = location
    }

    fun getBleScanner(): BleScanner {
        checkInitialized()
        if(this.bleScanner == null) {
            bleScanner = BleScanner()
        }
        return bleScanner!!
    }

    fun getBleManager(): BleManager {
        checkInitialized()
        return BleManager
    }

    fun unpairDeviceBLE() {
        checkInitialized()
        com.commcrete.stardust.transport.ConnectionManager.disableAutoReconnect()
        cleanupPackageHandlerOnDisconnect()
        val clientConnection = getClientConnection()
        clientConnection.removeBittelBond()
    }

    private fun cleanupPackageHandlerOnDisconnect() {
        bittelPackageHandler?.cleanupOnDisconnect()
    }

    fun getPortUtils(): PortUtils {
        checkInitialized()
        return PortUtils
    }

    fun getSharedPreferences(): SharedPreferencesUtil {
        checkInitialized()
        return SharedPreferencesUtil
    }

    fun getFolderReader(): FolderReader {
        checkInitialized()
        return FolderReader
    }

    fun getDataUtil(): ContactsFileParserUtil {
        checkInitialized()
        return ContactsFileParserUtil
    }

    fun getPlayerUtils(): PlayerUtils {
        checkInitialized()
        return PlayerUtils
    }

    fun getAppRepo(): AppRepository {
        return RepositoryProvider.appRepository()
    }


    fun getCallbacks() : StardustAPICallbacks? {
        return stardustAPICallbacks
    }

    fun getSavePTTFilesRequired(): Boolean {
        checkInitialized()
        if (savePTTFiles == null) {
            savePTTFiles = SharedPreferencesUtil.getSavePTTFiles()
        }
        return savePTTFiles != false
    }

    fun updateSavePTTFilesRequired(isRequired: Boolean) {
        checkInitialized()
        savePTTFiles = isRequired
        SharedPreferencesUtil.setSavePTTFiles(isRequired)
    }

    fun initRemoteConfig() {
        checkInitialized()
        RemoteConfigUtils.initLocalDefaults()
    }

    suspend fun cleanAllDatabases(): Boolean {
        checkInitialized()
        return getAppRepo().clearData()
    }

    suspend fun deleteChatFiles(): CleanResult = withContext(Dispatchers.IO) {

        withProcessFileLock("clean_user_files") {

            val dirs = FileUtils.getAllChatFilesDirs()
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
        lockName: String,
        block: () -> T
    ): T {
        val lockFile = File(appContext.filesDir, "$lockName.lock")
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