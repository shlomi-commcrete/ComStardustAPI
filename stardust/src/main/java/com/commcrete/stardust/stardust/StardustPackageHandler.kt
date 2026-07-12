package com.commcrete.stardust.stardust

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.bittell.util.bittel_package.model.StardustFileParser
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.bittell.util.text_utils.getCharValue
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
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
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.mapper.StardustPackageApiMapper
import com.commcrete.stardust.stardust.model.StardustAppEventPackage.StardustAppEventType.*
import com.commcrete.stardust.stardust.model.StardustAppEventParser
import com.commcrete.stardust.stardust.model.StardustBatteryParser
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.stardust.model.StardustGroupStatusParser
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.AdminUtils
import com.commcrete.stardust.util.AppEvents
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileReceiver
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.SOSUtils
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

internal class StardustPackageHandler(private var clientConnection: ClientConnection? = null) {

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

    fun handleStardustPackage(bittelPackage: StardustPackage?, randomID: String) {
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

            dispatchPackage(mPackage, randomID)
            synchronized(packageProcessingLock) { resetTimer() }
        }
    }

    /** Returns true if [mPackage] is an exact duplicate of the last saved package. */
    private fun isDuplicate(mPackage: StardustPackage): Boolean =
        savedPackage?.let { mPackage.isEqual(it) } ?: false

    /** Caches the package unless it is a high-frequency no-op opcode (ping / port update). */
    private fun cachePackageIfNeeded(mPackage: StardustPackage) {
        if (mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.PING_RESPONSE &&
            mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.UPDATE_PORT_RESPONSE &&
            mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.RECEIVE_APP_EVENT) {
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
    private fun dispatchPackage(mPackage: StardustPackage, randomID: String) {
        val control = mPackage.stardustControlByte
        when (mPackage.stardustOpCode) {
            StardustPackageUtils.StardustOpCode.SEND_MESSAGE -> {
                if (control.stardustPackageType == StardustControlByte.StardustPackageType.DATA)
                    handleText(mPackage)
                else
                    handlePTT(mPackage)
            }
            StardustPackageUtils.StardustOpCode.SEND_PTT                         -> handlePTT(mPackage)
            StardustPackageUtils.StardustOpCode.SEND_PTT_AI                      -> handlePTTAI(mPackage)
            StardustPackageUtils.StardustOpCode.REQUEST_LOCATION                 -> handleLocationRequested(mPackage, randomID)
            StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION                 -> handleLocationReceived(mPackage)
            StardustPackageUtils.StardustOpCode.GET_ADDRESSES                    -> handleAddressesReceived(mPackage)
            StardustPackageUtils.StardustOpCode.READ_CONFIGURATION_RESPONSE,
            StardustPackageUtils.StardustOpCode.READ_STATUS                      -> handleConfiguration(mPackage)
            StardustPackageUtils.StardustOpCode.RECEIVE_VERSION                  -> ConfigurationUtils.handleVersion(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_FREQ_RESPONSE     -> handleUpdatePolygonFreqResponse(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS_RESPONSE          -> handleUpdateAddressResponse(mPackage)
            StardustPackageUtils.StardustOpCode.SEND_FILE                        -> handleDeviceFileResponse(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_INTERRUPT         -> handleUpdatePolygonFreq()
            StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE               -> {
                DataManager.getClientConnection().handleAckReceived()
                handleLocationReceived(mPackage)
            }
            StardustPackageUtils.StardustOpCode.GET_POLYGON_RESPONSE             -> handleUpdatePolygonFreq()
            StardustPackageUtils.StardustOpCode.SAVE_CONFIG_RESPONSE             -> handleSaveConfigResponse(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_BITTEL_VERSION_RESPONSE   -> handleDeviceUpdateResponse(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_BITTEL_VERSION_PACKAGE_RESPONSE -> handleUpdateDataResponse(mPackage)
            StardustPackageUtils.StardustOpCode.GET_BITTEL_BOOT_ADDRESS_RESPONSE -> handleGetDeviceBootAddressResponse(mPackage)
            StardustPackageUtils.StardustOpCode.PING_RESPONSE                    -> handlePingResponse(mPackage)
            StardustPackageUtils.StardustOpCode.GET_BITTEL_LOGS_RESPONSE         -> handleDeviceLog(mPackage)
            StardustPackageUtils.StardustOpCode.SET_ADMIN_MODE_RESPONSE          -> handleAdminModeResponse()
            StardustPackageUtils.StardustOpCode.ADD_GROUPS_RESPONSE              -> handleAddGroupsResponse()
            StardustPackageUtils.StardustOpCode.DELETE_GROUPS_RESPONSE           -> handleDeleteGroupsResponse()
            StardustPackageUtils.StardustOpCode.RECEIVE_SOS_INTERRUPT            -> handleSOS(mPackage)
            StardustPackageUtils.StardustOpCode.SOS_ACK                          -> handleSOSAck(mPackage)
            StardustPackageUtils.StardustOpCode.RECEIVE_APP_EVENT                -> handleAppEvent(mPackage)
            StardustPackageUtils.StardustOpCode.UPDATE_PRESET_DATA,
            StardustPackageUtils.StardustOpCode.SOS_DESTINATION_UPDATED           -> getConfiguration()
            else -> {}
        }
    }

    private fun handleAppEvent(mPackage: StardustPackage) {
        val eventPackage = StardustAppEventParser().parseAppEvent(mPackage)

        eventPackage.let { sdPackage ->
            when (sdPackage.eventType) {
                RXSuccess, RXFail, TXStart, TXFinish, TXBufferFull, RxFinish -> {
                    ConfigurationUtils.setStardustCarrierFromEvent(sdPackage)
                }
                PresetChange -> {
                    sdPackage.getCurrentPreset()?.let {
                        ConfigurationUtils.setCurrentPresetLocal(it)
                        ConfigurationUtils.setDefaults()
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
            val rssiReportSource = SharedPreferencesUtil.getRSSIReportSource(DataManager.context)
            if(sdPackage.senderID.equals(rssiReportSource, true)) {
                AppEvents.updateRssiSignalChanged(
                    StardustAppEventPackage.RSSIPackage(
                        rssi = sdPackage.deviceConnectionRssi,
                        signalRssi = sdPackage.signalRssi,
                        snr = sdPackage.snr,
                        carrier = sdPackage.carrier?.let { getCarrierByStardustCarrier(it) }
                ))
            }
        }
    }

    private fun handleSOSAck(mPackage: StardustPackage) {
        val pkg = StardustPackageApiMapper.toStardustAPIPackage(mPackage) ?: return
        DataManager.getCallbacks()?.handleSOSAck(pkg)
    }

    private fun handleDeviceUpdateResponse(mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustUpdateProcess.isProcessRunning){
            StardustUpdateProcess.startSendingUpdateData()
        }
    }

    private fun handleUpdateDataResponse(mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustUpdateProcess.isProcessRunning) {
            StardustUpdateProcess.startSendingUpdateData()
        } else {
            StardustUpdateProcess.cancelProcess()
            // TODO: get Data at position 1 and handle text errors
        }
    }

    private fun handleGetDeviceBootAddressResponse(mPackage: StardustPackage) {
        StardustUpdateProcess.sendInitUpdateProcess(mPackage.data)
    }

    private fun handlePingResponse(mPackage: StardustPackage) {
        DataManager.getPortUtils().onPingReceived()
        val deviceBatteryPackage = StardustBatteryParser().parseBattery(mPackage)
        val missingGroups = StardustGroupStatusParser().parseGroupStatus(mPackage)
        AppEvents.updateBattery(deviceBatteryPackage)
        if(missingGroups) {
            GroupsUtils.sendAddAllGroups()
        }
    }

    private fun handleAdminModeResponse() {
        if(BleManager.isUsbEnabled()){
            BittelUsbManager2.updateBlePort()
            Timber.tag("startUpdatingPort").d("updateUsbPort")
        }else if (BleManager.isBluetoothConnected()) {
            DataManager.getClientConnection().updateBlePort()
            Timber.tag("startUpdatingPort").d("updateBlePort")
        }
    }

    private fun handleDeleteGroupsResponse() {
        GroupsUtils.sendAddAllGroups()
    }

    private fun handleAddGroupsResponse() {
        getConfiguration()
    }

    private fun getConfiguration() {
        val (src, dst) = requireLocalSrcDst() ?: return

        val configurationPackage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.READ_STATUS)

        clientConnection?.addMessageToQueue(configurationPackage)
    }

    private fun handleSaveConfigResponse(mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustPolygonChange.isProcessRunning){
            StardustPolygonChange.updateServerOfSaveConfigSuccess()
        }
    }

    private fun handleUpdateAddressResponse (mPackage: StardustPackage) {
        if(mPackage.isAck()) {
            GroupsUtils.sendDeleteAllGroups()
            DataManager.getClientConnection().removeConnectionTimer()
        }
    }

    private fun handleDeviceFileResponse(mPackage: StardustPackage) {
        val data = mPackage.data ?: return
        val sig = listOf(83, 84, 82) // "STR"

        val parser = StardustFileStartParser()
        // transportKey is unique per (source, dest, delivery) – each sender gets its own slot,
        // so concurrent transfers from different users are fully independent.
        val transportKey = FileReceiver.getUniqueKey(mPackage)

        fun registerReceiver(startData: StardustFileStartPackage) {
            val transferKey = buildTransferKey(transportKey, startData)
            val receiver = FileReceiver(startData, mPackage) {
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
                    // ConcurrentHashMap.get() throws NPE on null key, so guard against missing
                    // active transfer (data packet arrived before/after a file-start packet).
                    val activeKey = activeTransferByTransport[transportKey] ?: return@let
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
        if(mPackage.isAck()) { StardustPolygonChange.updateServerOfFreqChange() }
    }

    private fun handleUpdatePolygonFreq() {
        StardustPolygonChange.startProcess("1")
    }

    private fun handleConfiguration(mPackage: StardustPackage) {
        val result = StardustIncomingConfigurationHandler.parseAndApplyConfiguration(mPackage)
        if (!result.applied) return
        if (result.hasPresetsWithoutConfig && StardustInitConnectionHandler.isConnectedSuccessfully()) {
            listener.onInitDone(StardustInitConnectionHandler.State.PRESET_ERROR)
        }
        AdminUtils.updateBittelAdminMode()
    }

    private fun handleSOS(mPackage: StardustPackage) {
        val pkg = StardustPackageApiMapper.toStardustAPIPackage(mPackage) ?: return

        handlerScope.launch {
            try {
                val sosPackage = StardustLocationParser().parseSOS(mPackage) ?: return@launch

                SOSUtils.saveSOSMessage(
                    type = sosPackage.sosType,
                    location = sosPackage.location,
                    stardustAPIPackage = pkg,
                    state = MessageState.RECEIVED
                )

                PlayerUtils.playNotificationSound()
                DataManager.getCallbacks()?.receiveSOS(pkg, sosPackage)
            } catch (e: Exception) {
                Timber.tag("StardustPackageHandler").e(e, "Failed to save SOS message")
            }
        }
    }

    private fun handleAddressesReceived(mPackage: StardustPackage) {
        StardustAddressesParser().parseAddresses(mPackage)?.let { addressesPackage ->
            registerDevice(addressesPackage.stardustID)
            updateDeviceSmartphoneAddress(addressesPackage)
        }
    }

    private fun registerDevice(deviceId: String) {
        val savedUser = SharedPreferencesUtil.getAppUser()
        val deviceName = SharedPreferencesUtil.getBittelDeviceName()

        //Temp
        if (BleManager.isBluetoothConnected() || BleManager.isUsbEnabled()) {
            deviceName.let { name ->
                savedUser?.let { user ->
                    val newUser = RegisterUser(
                        displayName = user.displayName,
                        _deviceId = deviceId,
                        _appId = user.appId,
                    )
                    SharedPreferencesUtil.setAppUser(newUser)
                }
            }
        }
    }

    /**
     * Sends UPDATE_ADDRESS packet to device with this app's ID and device type.
     * Packet structure: [appId_4bytes][padding_4bytes][device_type_1byte]
     */
    private fun updateDeviceSmartphoneAddress(addressesPackage: StardustAddressesPackage) {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId
        if (appId.isNullOrBlank()) {
            Timber.tag("UpdateAddress").w("Cannot send UPDATE_ADDRESS: appId not registered")
            return
        }

        val clientConnection = DataManager.getClientConnection()

        try {
            val payload = buildUpdateAddressPayload(appId)
            val mPackage = StardustPackageUtils.getStardustPackage(
                source = appId,
                destination = addressesPackage.stardustID,
                stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS,
                data = payload
            )
            clientConnection.addMessageToQueue(mPackage)
            Timber.tag("UpdateAddress").d(
                "Sent UPDATE_ADDRESS from $appId to ${addressesPackage.stardustID}"
            )
        } catch (e: Exception) {
            Timber.tag("UpdateAddress").e(e, "Failed to send UPDATE_ADDRESS packet")
        }
    }

    /**
     * Builds UPDATE_ADDRESS payload: appId (4 bytes) + padding (4 bytes) + device type (1 byte).
     */
    private fun buildUpdateAddressPayload(appId: String): Array<Int> {
        return arrayListOf<Int>().apply {
            addAll(StardustPackageUtils.hexStringToByteArray(appId))
            // 4-byte padding (reserved for future use)
            repeat(4) { add(0) }
            // Device type indicator (smartphone)
            add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
        }.toIntArray().toTypedArray()
    }

    private fun handleLocationReceived(mPackage: StardustPackage) {
        val pkg = StardustPackageApiMapper.toStardustAPIPackage(mPackage) ?: return
        handlerScope.launch {
            if(mPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK) {
                handleAck(
                    source = mPackage.getSourceAsString(),
                    destination = mPackage.getDestAsString(),
                    deliveryType = mPackage.stardustControlByte.stardustDeliveryType
                )
            }

            val locationPackage = StardustLocationParser().parseLocation(mPackage) ?: return@launch

            LocationUtils.saveLocationMessage(mPackage.chatId, pkg, locationPackage, MessageState.RECEIVED)
            val pollingUtils = DataManager.getPollingUtils()
            if(pollingUtils.isRunning) { pollingUtils.handleResponse(mPackage) }

            PlayerUtils.playNotificationSound()

            DataManager.getCallbacks()?.receiveLocation(
                stardustAPIPackage = pkg,
                location = locationPackage.location)
        }
    }

    private fun handleLocationRequested(mPackage: StardustPackage, randomID: String) {

        if(ConfigurationUtils.licensedFunctionalities[FunctionalityType.LOCATION] != LimitationType.ENABLED) return

        Log.d("LocationRequest $randomID", "start ts ${System.currentTimeMillis()}")
        DataManager.getClientConnection().let {
            LocationUtils.respondToRequestedLocation(
                mPackage = mPackage,
                clientConnection = it,
                isHR = mPackage.stardustControlByte.stardustDeliveryType,
                randomID = randomID)
        }
    }

    private fun handlePTTAI(mPackage: StardustPackage) {
        PlayerUtils.onPTTAiReceived(dataPackage = mPackage)
    }

    private fun handlePTT(mPackage: StardustPackage) {
        PlayerUtils.onPTTCodecReceived(dataPackage = mPackage)
    }

    private fun handleText(mPackage: StardustPackage) {
        val pkg = StardustPackageApiMapper.toStardustAPIPackage(mPackage) ?: return
        if(mPackage.data?.startsWith(arrayOf(83,79,83)) == true) {
            handleSOS(mPackage)
        } else {
            handlerScope.launch {
                if(mPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK) {
                    handleAck(
                        source = mPackage.getSourceAsString(),
                        destination = mPackage.getDestAsString(),
                        deliveryType = mPackage.stardustControlByte.stardustDeliveryType
                    )
                }
                val text = getCharValue(mPackage.getDataAsString())
                try {
                    DataManager.getAppRepo().saveMessage(pkg, MessageExtraData.Text(text), MessageState.RECEIVED)
                    PlayerUtils.playNotificationSound()
                    DataManager.getCallbacks()?.receiveMessage(pkg, text)
                } catch (e: Exception) {
                    Timber.tag("StardustPackageHandler").e(e, "Failed to save text message")
                }
            }
        }
    }

    private fun handleAck(
        source: String,
        destination: String,
        deliveryType: StardustControlByte.StardustDeliveryType
    ) {
        if(ConfigurationUtils.licensedFunctionalities[FunctionalityType.ACK] != LimitationType.ENABLED ||
            ConfigurationUtils.licensedFunctionalities[FunctionalityType.LOCATION] != LimitationType.ENABLED) return

        try {
            val dataPackage = StardustPackageUtils.getStardustPackage(
                source = source,
                destination = destination,
                stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE,
                data = arrayOf(StardustPackageUtils.Ack, 0x00)
            )
            dataPackage.stardustControlByte.stardustAcknowledgeType = StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            dataPackage.stardustControlByte.stardustDeliveryType = deliveryType

            DataManager.getClientConnection().let {
                LocationUtils.respondToRequestedLocation(
                    mPackage = dataPackage,
                    clientConnection = it,
                    opCode = StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE,
                    isHR = deliveryType)
            }
        } catch (e: Exception) {
            Timber.tag("StardustPackageHandler").e(e, "Failed to handle ACK")
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
