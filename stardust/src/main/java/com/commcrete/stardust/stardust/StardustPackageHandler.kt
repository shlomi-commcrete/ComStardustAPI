package com.commcrete.stardust.stardust

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.bittell.util.bittel_package.model.StardustFileParser
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.bittell.util.text_utils.getCharValue
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.messages.MessageState
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageType
import com.commcrete.stardust.stardust.model.StardustAddressesPackage
import com.commcrete.stardust.stardust.model.StardustAddressesParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustLocationParser
import com.commcrete.stardust.util.LogUtils
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.update.StardustUpdateProcess
import com.commcrete.stardust.stardust.model.StardustLogParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.security.EraseUtils
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.listener
import com.commcrete.stardust.stardust.model.SOSPackage
import com.commcrete.stardust.stardust.model.StardustAppEventPackage.StardustAppEventType.*
import com.commcrete.stardust.stardust.model.StardustAppEventParser
import com.commcrete.stardust.stardust.model.StardustBatteryParser
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.stardust.model.StardustGroupStatusParser
import com.commcrete.stardust.stardust.model.asString
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.AdminUtils
import com.commcrete.stardust.util.AppEvents
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileReceiver
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

internal class StardustPackageHandler(private val context: Context ,
                             private var clientConnection: ClientConnection? = null) {

    private val fileReceivers = ConcurrentHashMap<String, FileReceiver>()
    private val activeTransferByTransport = ConcurrentHashMap<String, String>()
    private val fileTransferCounter = AtomicLong(0)
    private val packageProcessingLock = Any()
    private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runnable : Runnable = Runnable { synchronized(packageProcessingLock) { savedPackage = null } }
    private val handler : Handler = Handler(Looper.getMainLooper())
    private var savedPackage : StardustPackage? = null

    private fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 1000)
    }

    internal fun cleanupOnDisconnect() {
        handlerScope.coroutineContext.cancelChildren()
        synchronized(packageProcessingLock) {
            savedPackage = null
            // Dispose all active receivers to stop their internal Handler timers,
            // regardless of which user/transport they belong to.
            fileReceivers.values.forEach { it.dispose() }
            fileReceivers.clear()
            activeTransferByTransport.clear()
        }
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
    }

    fun handleStardustPackage(context: Context, bittelPackage: StardustPackage?, randomID: String) {
        val mPackage = bittelPackage ?: return

        synchronized(packageProcessingLock) {
            if (isDuplicate(mPackage)) {
                resetTimer()
                return
            }

            cachePackageIfNeeded(mPackage)
            logIncomingPackage(mPackage, randomID)
        }

        handlerScope.launch {
            swapAddressesIfSendInGroup(mPackage)

            if (mPackage.stardustControlByte.stardustMessageType == StardustControlByte.StardustMessageType.SNIFFED) {
                // TODO: Handle Sniffed message
                return@launch
            }

            if (StardustInitConnectionHandler.onIncoming(mPackage)) {
                synchronized(packageProcessingLock) { resetTimer() }
                return@launch
            }
            Timber.tag("InitHandler").d("not handled by init handler")

            dispatchPackage(context, mPackage, randomID)
            synchronized(packageProcessingLock) { resetTimer() }
        }
    }

    /** Returns true if [mPackage] is an exact duplicate of the last saved package. */
    private fun isDuplicate(mPackage: StardustPackage): Boolean =
        savedPackage?.let { mPackage.isEqual(it) } ?: false

    /** Caches the package unless it is a high-frequency no-op opcode (ping / port update). */
    private fun cachePackageIfNeeded(mPackage: StardustPackage) {
        if (mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.PING_RESPONSE &&
            mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.UPDATE_PORT_RESPONSE) {
            savedPackage = mPackage
        }
    }

    private fun logIncomingPackage(mPackage: StardustPackage, randomID: String) {
        Log.i("IncomingPackage", "op: ${mPackage.stardustOpCode}; src: ${mPackage.getSourceAsString()}")
        Timber.tag(ClientConnection.LOG_TAG).d("handlePackageReceivedbyteArray $randomID: ${mPackage.stardustOpCode}")
    }

    /** Swaps source/destination bytes when the destination belongs to a local group. */
    private suspend fun swapAddressesIfSendInGroup(mPackage: StardustPackage) {
        if (GroupsUtils.isLocalGroupId(mPackage.getDestAsString())) {
            val srcBytes = mPackage.sourceBytes
            mPackage.sourceBytes = mPackage.destinationBytes
            mPackage.destinationBytes = srcBytes
        }
    }

    /** Routes [mPackage] to the appropriate handler based on its opcode. */
    private fun dispatchPackage(context: Context, mPackage: StardustPackage, randomID: String) {
        val control = mPackage.stardustControlByte
        when (mPackage.stardustOpCode) {
            StardustPackageUtils.StardustOpCode.SEND_MESSAGE -> {
                if (control.stardustPackageType == StardustControlByte.StardustPackageType.DATA)
                    handleText(context, mPackage)
                else
                    handlePTT(context, mPackage)
            }
            StardustPackageUtils.StardustOpCode.SEND_PTT                         -> handlePTT(context, mPackage)
            StardustPackageUtils.StardustOpCode.SEND_PTT_AI                      -> handlePTTAI(context, mPackage)
            StardustPackageUtils.StardustOpCode.REQUEST_LOCATION                 -> handleLocationRequested(context, mPackage, randomID)
            StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION                 -> handleLocationReceived(context, mPackage)
            StardustPackageUtils.StardustOpCode.GET_ADDRESSES                    -> handleAddressesReceived(context, mPackage)
            StardustPackageUtils.StardustOpCode.READ_CONFIGURATION_RESPONSE,
            StardustPackageUtils.StardustOpCode.READ_STATUS                      -> handleConfiguration(context, mPackage)
            StardustPackageUtils.StardustOpCode.RECEIVE_VERSION                  -> ConfigurationUtils.handleVersion(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_FREQ_RESPONSE     -> handleUpdatePolygonFreqResponse(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS_RESPONSE          -> handleUpdateAddressResponse(context, mPackage)
            StardustPackageUtils.StardustOpCode.SEND_FILE                        -> handleDeviceFileResponse(context, mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_INTERRUPT         -> handleUpdatePolygonFreq(context)
            StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE               -> {
                DataManager.getClientConnection(context).handleAckReceived()
                handleLocationReceived(context, mPackage)
            }
            StardustPackageUtils.StardustOpCode.GET_POLYGON_RESPONSE             -> handleUpdatePolygonFreq(context)
            StardustPackageUtils.StardustOpCode.SAVE_CONFIG_RESPONSE             -> handleSaveConfigResponse(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_BITTEL_VERSION_RESPONSE   -> handleDeviceUpdateResponse(context, mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_BITTEL_VERSION_PACKAGE_RESPONSE -> handleUpdateDataResponse(context, mPackage)
            StardustPackageUtils.StardustOpCode.GET_BITTEL_BOOT_ADDRESS_RESPONSE -> handleGetDeviceBootAddressResponse(context, mPackage)
            StardustPackageUtils.StardustOpCode.PING_RESPONSE                    -> handlePingResponse(context, mPackage)
            StardustPackageUtils.StardustOpCode.GET_BITTEL_LOGS_RESPONSE         -> handleDeviceLog(mPackage)
            StardustPackageUtils.StardustOpCode.SET_ADMIN_MODE_RESPONSE          -> handleAdminModeResponse(context)
            StardustPackageUtils.StardustOpCode.ADD_GROUPS_RESPONSE              -> handleAddGroupsResponse(context)
            StardustPackageUtils.StardustOpCode.DELETE_GROUPS_RESPONSE           -> handleDeleteGroupsResponse(context)
            StardustPackageUtils.StardustOpCode.RECEIVE_SOS_INTERRUPT            -> handleSOS(mPackage)
            StardustPackageUtils.StardustOpCode.SOS_ACK                          -> handleSOSAck(mPackage)
            StardustPackageUtils.StardustOpCode.RECEIVE_APP_EVENT                -> handleAppEvent(context, mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_PRESET_DATA,
            StardustPackageUtils.StardustOpCode.UPDATE_SOS_DESTINATION           -> getConfiguration(context)
            else -> {}
        }
    }

    private fun handleAppEvent(context: Context, mPackage: StardustPackage) {
        val eventPackage = StardustAppEventParser().parseAppEvent(mPackage)

        eventPackage.let { sdPackage ->
            when (sdPackage.eventType) {
                RXSuccess, RXFail, TXStart, TXFinish, TXBufferFull, RxFinish -> {
                    ConfigurationUtils.setStardustCarrierFromEvent(sdPackage)
                }
                PresetChange -> {
                    sdPackage.getCurrentPreset()?.let {
                        ConfigurationUtils.setCurrentPresetLocal(it)
                        ConfigurationUtils.setDefaults(context)
                    }
                }
                ArmDelete -> {
                    if(sdPackage.armDelete == ArmDelete.type) {
                        EraseUtils.handleArm()
                    }
                }
                Delete -> {
                    if(sdPackage.armDelete == Delete.type) {
                        EraseUtils.handleDelete()
                    }
                }
                PartialEraseFinished -> {}

                null -> {}
            }
            Timber.tag("AppEvent").d("eventType: ${sdPackage.eventType}")
            AppEvents.updateAppEvents(sdPackage)
        }
    }

    private fun handleSOSAck(mPackage: StardustPackage) {
        val appId = RegisteredUserUtils.mRegisterUser?.appId ?: return
        DataManager.getCallbacks()?.handleSOSAck(
            StardustAPIPackage(
                senderId = mPackage.senderId,
                groupId = mPackage.groupId,
                receiverId = appId
            )
        )
    }

    private fun handleDeviceUpdateResponse(context: Context, mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustUpdateProcess.isProcessRunning){
            StardustUpdateProcess.startSendingUpdateData(context)
        }
    }

    private fun handleUpdateDataResponse(context: Context, mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustUpdateProcess.isProcessRunning){
            StardustUpdateProcess.startSendingUpdateData(context)
        } else {
            StardustUpdateProcess.cancelProcess()
            // TODO: get Data at position 1 and handle text errors
        }
    }

    private fun handleGetDeviceBootAddressResponse(context: Context, mPackage: StardustPackage) {
        StardustUpdateProcess.sendInitUpdateProcess(mPackage.data, context)
    }

    private fun handlePingResponse (context: Context, mPackage: StardustPackage) {
        DataManager.getPortUtils(context).onPingReceived()
        val deviceBatteryPackage = StardustBatteryParser().parseBattery(mPackage)
        val missingGroups = StardustGroupStatusParser().parseGroupStatus(mPackage)
        AppEvents.updateBattery(deviceBatteryPackage)
        if(missingGroups && GroupsUtils.hasLocalGroups(context)){
            GroupsUtils.addGroupsToLocal(context)
        }
    }

    private fun handleAdminModeResponse(context: Context) {
        if(BleManager.isUsbEnabled()){
            BittelUsbManager2.updateBlePort()
            Timber.tag("startUpdatingPort").d("updateUsbPort")
        }else if (BleManager.isBluetoothEnabled()) {
            DataManager.getClientConnection(context).updateBlePort()
            Timber.tag("startUpdatingPort").d("updateBlePort")
        }
    }

    private fun handleDeleteGroupsResponse(context: Context) {
        GroupsUtils.addGroupsToLocal(context)
    }

    private fun handleAddGroupsResponse(context: Context) {
        getConfiguration(context)
    }

    private fun getConfiguration(context: Context) {
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val configurationPackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destination = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.READ_STATUS)
                clientConnection?.addMessageToQueue(configurationPackage)
            }
        }
    }

    private fun handleSaveConfigResponse(mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustPolygonChange.isProcessRunning){
            StardustPolygonChange.updateServerOfSaveConfigSuccess()
        }
    }

    private fun handleUpdateAddressResponse (context: Context, mPackage: StardustPackage) {
        if(mPackage.isAck()) {
            GroupsUtils.deleteAllDeviceGroups(context)
            DataManager.getClientConnection(context).removeConnectionTimer()
        }
    }

    private fun handleDeviceFileResponse(context: Context, mPackage: StardustPackage) {
        val data = mPackage.data ?: return
        val sig = listOf(83, 84, 82) // "STR"

        val parser = StardustFileStartParser()
        // transportKey is unique per (source, dest, delivery) – each sender gets its own slot,
        // so concurrent transfers from different users are fully independent.
        val transportKey = FileReceiver.getUniqueKey(mPackage)

        fun registerReceiver(startData: StardustFileStartPackage) {
            val transferKey = buildTransferKey(transportKey, startData)
            val receiver = FileReceiver(context, startData, mPackage) {
                // Completion callback: only clean up if this transfer is still the active one
                // for this transport (guards against stale callbacks from replaced receivers).
                synchronized(packageProcessingLock) {
                    val activeKey = activeTransferByTransport[transportKey]
                    if (activeKey == transferKey) {
                        fileReceivers.remove(transferKey)
                        activeTransferByTransport.remove(transportKey, transferKey)
                    }
                }
            }

            // Replace any previous transfer on this transport (same user restarting).
            // Receivers from OTHER transports (other users) are unaffected.
            val previousKey = activeTransferByTransport.put(transportKey, transferKey)
            if (previousKey != null && previousKey != transferKey) {
                fileReceivers.remove(previousKey)?.dispose()
            }
            fileReceivers[transferKey] = receiver
        }

        when {
            data.matchesSignatureAt(1, sig) -> {
                parser.parseFileStart(mPackage)?.let { registerReceiver(it) }
            }
            data.matchesSignatureAt(0, sig) -> {
                parser.parseFileStart2(mPackage)?.let { registerReceiver(it) }
            }
            else -> {
                StardustFileParser().parseFile(bittelPackage = mPackage)?.let { fileData ->
                    val activeKey = activeTransferByTransport[transportKey]
                    fileReceivers[activeKey]?.addDataPackage(fileData)
                }
            }
        }
    }

    private fun buildTransferKey(transportKey: String, startData: StardustFileStartPackage): String {
        val uniquePart = fileTransferCounter.incrementAndGet()
        return "${transportKey}_${startData.total}_${startData.spare}_$uniquePart"
    }

    private fun handleUpdatePolygonFreqResponse(mPackage: StardustPackage) {
        if(mPackage.isAck()){
            StardustPolygonChange.updateServerOfFreqChange()
        }
    }

    private fun handleUpdatePolygonFreq(context: Context, ) {
        StardustPolygonChange.startProcess("1", context)
    }

    private fun handleConfiguration(context: Context, mPackage: StardustPackage) {
        val result = StardustIncomingConfigurationHandler.parseAndApplyConfiguration(context, mPackage)
        if (!result.applied) return
        if (result.hasPresetsWithoutConfig && StardustInitConnectionHandler.isConnectedSuccessfully()) {
            listener?.onInitDone(StardustInitConnectionHandler.State.PRESET_ERROR)
        }
        AdminUtils.updateBittelAdminMode(context)
    }

    private fun handleSOS(mPackage: StardustPackage) {
        val sosPackage = StardustLocationParser().parseSOS(mPackage)
        sosPackage ?: return
        saveSosMessage(mPackage, sosPackage)
    }

    private fun saveSosMessage(
        mPackage: StardustPackage,
        sosPackage: SOSPackage
    ) {
        val appId = RegisteredUserUtils.mRegisterUser?.appId ?: return
        handlerScope.launch {
            try {
                DataManager.getAppRepo(context).saveMessage(
                    message = MessageEntity(
                        senderID = mPackage.senderId,
                        receiverID = appId,
                        text = sosPackage.location.asString(),
                        state = MessageState.RECEIVED,
                        type = MessageType.SOS
                    ),
                    groupId = mPackage.groupId
                )

                PlayerUtils.playNotificationSound(context)
                DataManager.getCallbacks()?.receiveSOS(
                    StardustAPIPackage(
                        senderId = mPackage.senderId,
                        groupId = mPackage.groupId,
                        receiverId = appId,
                    ),
                    location = sosPackage.location,
                    type = sosPackage.sosType
                )
            } catch (e: Exception) {
                Timber.tag("StardustPackageHandler").e(e, "Failed to save SOS message")
            }
        }
    }

    private fun handleAddressesReceived(context: Context, mPackage: StardustPackage) {
        val addressesPackage = StardustAddressesParser().parseAddresses(mPackage)
        addressesPackage?.let {
            registerDevice(addressesPackage.stardustID)
            updateDeviceSmartphoneAddress(context, addressesPackage)
        }
    }

    private fun updateDeviceSmartphoneAddress(context: Context, addressesPackage: StardustAddressesPackage) {
        // Added fix , push the id i have in my app
        val appId = RegisteredUserUtils.mRegisterUser?.appId ?: return
        val data = arrayListOf<Int>()
        data.addAll(StardustPackageUtils.hexStringToByteArray(appId))
        data.add(0)
        data.add(0)
        data.add(0)
        data.add(0)
        data.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
        val mPackage = StardustPackageUtils.getStardustPackage(
            context = context,
            source = appId,
            destination = addressesPackage.stardustID,
            stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS,
            data = data.toIntArray().toTypedArray()
        )

        DataManager.getClientConnection(context).addMessageToQueue(mPackage)
    }

    private fun registerDevice(deviceId: String){
        val savedUser = SharedPreferencesUtil.getAppUser(context)
        val deviceName = SharedPreferencesUtil.getBittelDeviceName(context)

        //Temp
        if (BleManager.isBluetoothEnabled() || BleManager.isUsbEnabled()) {
            deviceName.let { name ->
                savedUser?.let { user ->
                    val newUser = RegisterUser(displayName = user.displayName, licenseType = "", phone = user.phone,
                        location = arrayOf(),
                        bittelId = deviceId, bittelName = name, bittelMacAddress = name,
                        appId = user.appId, token = user.token
                    )
                    if (user.appId != null) {
                        SharedPreferencesUtil.setAppUser(context, newUser)
                    }
                }
            }
        }
    }

    private fun handleLocationReceived(context: Context, mPackage: StardustPackage){
        if(mPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK){
            handleAck(
                appContext = context,
                source = mPackage.getSourceAsString(),
                destination = mPackage.getDestAsString(),
                deliveryType = mPackage.stardustControlByte.stardustDeliveryType
            )
        }
        val locationPackage = StardustLocationParser().parseLocation(mPackage)
        locationPackage?.let { LocationUtils.saveDeviceLocation(context, mPackage, it) }
    }

    private fun handleLocationRequested(context: Context, mPackage: StardustPackage, randomID: String){
        if(ConfigurationUtils.licensedFunctionalities[FunctionalityType.LOCATION] != LimitationType.ENABLED ) return
        Log.d("LocationRequest $randomID", "start ts ${System.currentTimeMillis()}")
        DataManager.getClientConnection(context).let {
            LocationUtils.sendMyLocation(
                appContext = context,
                mPackage = mPackage,
                clientConnection = it,
                isHR = mPackage.stardustControlByte.stardustDeliveryType,
                randomID = randomID)
        }
    }

    private fun handlePTTAI(appContext: Context, mPackage: StardustPackage) {
        PlayerUtils.onPTTAiReceived(appContext = appContext, dataPackage = mPackage)
    }

    private fun handlePTT(appContext: Context, mPackage: StardustPackage) {
        PlayerUtils.onPTTCodecReceived(appContext = appContext, dataPackage = mPackage)
    }

    private fun handleText(context: Context, mPackage: StardustPackage) {
        val appId = RegisteredUserUtils.mRegisterUser?.appId ?: return
        if(mPackage.data?.startsWith(arrayOf(83,79,83)) == true) {
            handleSOS(mPackage)
        } else {
            if(mPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK){
                handleAck(
                    appContext = context,
                    source = mPackage.getSourceAsString(),
                    destination = mPackage.getDestAsString(),
                    deliveryType = mPackage.stardustControlByte.stardustDeliveryType
                )
            }
            val text = getCharValue(mPackage.getDataAsString())
            handlerScope.launch {
                try {
                    DataManager.getAppRepo(context).saveMessage(
                        message = MessageEntity(
                            senderID = mPackage.senderId,
                            receiverID = appId,
                            text = text,
                            state = MessageState.RECEIVED,
                            type = MessageType.TEXT
                        ),
                        groupId = mPackage.groupId
                    )

                    PlayerUtils.playNotificationSound(context)
                    DataManager.getCallbacks()?.receiveMessage(
                        StardustAPIPackage(
                            senderId = mPackage.senderId,
                            groupId = mPackage.groupId,
                            receiverId = appId,
                        ),
                        text)
                } catch (e: Exception) {
                    Timber.tag("StardustPackageHandler").e(e, "Failed to save text message")
                }
            }
        }
    }

    private fun handleAck(
        appContext: Context,
        source: String,
        destination: String,
        deliveryType: StardustControlByte.StardustDeliveryType
    ) {
        if(ConfigurationUtils.licensedFunctionalities[FunctionalityType.ACK] != LimitationType.ENABLED ||
            ConfigurationUtils.licensedFunctionalities[FunctionalityType.LOCATION] != LimitationType.ENABLED) return

        handlerScope.launch {
            try {
                val dataPackage = StardustPackageUtils.getStardustPackage(
                    context = appContext,
                    source = source,
                    destination = destination,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE,
                    data = arrayOf(StardustPackageUtils.Ack, 0x00)
                )
                dataPackage.stardustControlByte.stardustAcknowledgeType = StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
                dataPackage.stardustControlByte.stardustDeliveryType = deliveryType

                DataManager.getClientConnection(appContext).let {
                    LocationUtils.sendMyLocation(
                        appContext = appContext,
                        mPackage = dataPackage,
                        clientConnection = it,
                        opCode = StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE,
                        isHR = deliveryType)
                }
            } catch (e: Exception) {
                Timber.tag("StardustPackageHandler").e(e, "Failed to handle ACK")
            }
        }
    }

    private fun handleDeviceLog (mPackage: StardustPackage) {
        StardustLogParser().parseLog(mPackage)?.let {
            LogUtils.appendToList(it)
        }
    }

    fun Array<Int>.startsWith(subArray: Array<Int>): Boolean {
        if (this.size < subArray.size) return false

        for (i in subArray.indices) {
            if (this[i] != subArray[i]) return false
        }

        return true
    }

    fun Array<Int>.matchesSignatureAt(pos: Int, sig: List<Int>): Boolean {
        if (this.size < pos + sig.size) return false
        return sig.indices.all { i -> this[pos + i] == sig[i] }
    }
}
