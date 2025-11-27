package com.commcrete.stardust.stardust

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Observer
import com.commcrete.bittell.util.bittel_package.model.StardustFileParser
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.stardust.model.StardustAddressesPackage
import com.commcrete.stardust.stardust.model.StardustAddressesParser
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustLocationParser
import com.commcrete.stardust.util.LogUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.update.StardustUpdateProcess
import com.commcrete.stardust.stardust.model.StardustLogParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.security.EraseUtils
import com.commcrete.stardust.stardust.model.StardustAppEventPackage.StardustAppEventType.*
import com.commcrete.stardust.stardust.model.StardustAppEventParser
import com.commcrete.stardust.stardust.model.StardustBatteryParser
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.AdminUtils
import com.commcrete.stardust.util.AppEvents
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileSendUtils
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.LicenseLimitationsUtil
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.launch
import timber.log.Timber

internal class StardustPackageHandler(private val context: Context ,
                             private var clientConnection: ClientConnection? = null) {

    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        savedPackage =null
    }
    private val handler : Handler = Handler(Looper.getMainLooper())
    private var savedPackage : StardustPackage? = null

    private fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 1000)
    }

    private val observer : Observer<StardustPackage?> = Observer {

    }

    init {
//        StardustPackageUtils.packageLiveData.observeForever(observer)
    }

    fun handleStardustPackage(bittelPackage: StardustPackage?, randomID: String) {
        if(bittelPackage != null){
            val mPackage = bittelPackage
            val tempSavedPackage = savedPackage
            if(tempSavedPackage == null ||!mPackage.isEqual(tempSavedPackage)){
                if(mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.PING_RESPONSE &&
                    mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.UPDATE_PORT_RESPONSE){
                    savedPackage = mPackage
                }
                Timber.tag(ClientConnection.LOG_TAG).d("handlePackageReceivedbyteArray $randomID: ${bittelPackage.stardustOpCode}")
//                SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let {
//                    if(mPackage.getDestAsString() != it && mPackage.getSourceAsString() != it
//                        && mPackage.getDestAsString() != "00000002") {
//                        Timber.tag(LOG_TAG).d("Message not for user")
//                        return
//                    }
//                }
                val mPackageControl = mPackage.stardustControlByte
                val mPackageOpCode = mPackage.stardustOpCode
                if(GroupsUtils.isGroup(mPackage.getDestAsString())){
                    val srcBytes = mPackage.sourceBytes
                    val dstBytes = mPackage.destinationBytes
                    mPackage.sourceBytes = dstBytes
                    mPackage.destinationBytes = srcBytes

                }
                if(mPackage.stardustControlByte.stardustMessageType == StardustControlByte.StardustMessageType.SNIFFED) {
                    // TODO: Handle Sniffed message
                    return
                }

                if (StardustInitConnectionHandler.onIncoming(mPackage)) {
                    resetTimer()
                    return
                }
                Timber.tag("InitHandler").d("not handled by init handler")
                when(mPackageOpCode){
                    StardustPackageUtils.StardustOpCode.SEND_MESSAGE -> {
                        if(mPackageControl.stardustPackageType == StardustControlByte.StardustPackageType.DATA) {
                            handleText(mPackage)
                        }else {
                            handlePTT(mPackage)
                        }
                    }
                    StardustPackageUtils.StardustOpCode.SEND_PTT -> {
                        handlePTT(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.SEND_PTT_AI -> {
                        handlePTTAI(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.REQUEST_LOCATION -> {
                        handleLocationRequested(mPackage, randomID)
                    }

                    StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION -> {
                        handleLocationReceived(mPackage)
                    }

                    StardustPackageUtils.StardustOpCode.GET_ADDRESSES -> {
                        handleAddressesReceived(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.READ_CONFIGURATION_RESPONSE -> {
                        handleConfiguration(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.RECEIVE_VERSION -> {
                        ConfigurationUtils.handleVersion(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_FREQ_RESPONSE -> {
                        handleUpdatePolygonFreqResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS_RESPONSE -> {
                        handleUpdateAddressResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.SEND_FILE -> {
                        handleBittelFileResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_INTERRUPT -> {
                        handleUpdatePolygonFreq(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE -> {
                        DataManager.getClientConnection(context).handleAckReceived()
                        handleLocationReceived(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.GET_POLYGON_RESPONSE -> {
                        handleUpdatePolygonFreq(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.SAVE_CONFIG_RESPONSE -> {
                        handleSaveConfigResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.UPDATE_BITTEL_VERSION_RESPONSE -> {
                        handleBittelUpdateResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.UPDATE_BITTEL_VERSION_PACKAGE_RESPONSE -> {
                        handleBittelUpdateDataResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.GET_BITTEL_BOOT_ADDRESS_RESPONSE -> {
                        handleBittelGetBittelBootAddressResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.PING_RESPONSE -> {
                        handlePingResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.GET_BITTEL_LOGS_RESPONSE -> {
                        handleBittelLog(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.SET_ADMIN_MODE_RESPONSE -> {
                        handleAdminModeResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.ADD_GROUPS_RESPONSE -> {
                        handleAddGroupsResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.DELETE_GROUPS_RESPONSE -> {
                        handleDeleteGroupsResponse(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.RECEIVE_SOS_INTERRUPT -> {
                        handleRealSOS(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.SOS_ACK -> {
                        handleSOSAck(mPackage)
                    }
                    StardustPackageUtils.StardustOpCode.RECEIVE_APP_EVENT -> {
                        handleAppEvent(mPackage)
                    }
                    else -> {}
                } }
            resetTimer()
        }
    }

    private fun handleAppEvent(mPackage: StardustPackage) {
        val bittelAppEventPackage = StardustAppEventParser().parseAppEvent(mPackage)
        bittelAppEventPackage.let { sdPackage ->
            when (sdPackage.eventType) {
                RXSuccess -> {ConfigurationUtils.setStardustCarrierFromEvent(sdPackage)}
                RXFail -> {ConfigurationUtils.setStardustCarrierFromEvent(sdPackage)}
                TXStart -> {ConfigurationUtils.setStardustCarrierFromEvent(sdPackage)}
                TXFinish -> {ConfigurationUtils.setStardustCarrierFromEvent(sdPackage)}
                TXBufferFull -> {ConfigurationUtils.setStardustCarrierFromEvent(sdPackage)}
                PresetChange -> {sdPackage.getCurrentPreset()?.let {
                    ConfigurationUtils.setCurrentPresetLocal(it)
                    ConfigurationUtils.setDefaults(DataManager.context)
                }}
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
                null -> {}

            }
            Timber.tag("AppEvent").d("eventType: ${sdPackage.eventType}")
            AppEvents.updateAppEvents(sdPackage)
        }
    }

    private fun handleSOSAck(mPackage: StardustPackage) {
        DataManager.getCallbacks()?.handleSOSAck(StardustAPIPackage(source = mPackage.getSourceAsString(),
            destination = mPackage.getDestAsString(), false, null))
    }

    private fun handleRealSOS(mPackage: StardustPackage) {
        val sosPackage = StardustLocationParser().parseSOSReal(mPackage)
        sosPackage?.let { UsersUtils.saveBittelUserSOSReal(mPackage, it) }
    }

    private fun handleBittelUpdateResponse(mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustUpdateProcess.isProcessRunning){
            StardustUpdateProcess.startSendingUpdateData(DataManager.context)
        }
    }

    private fun handleBittelUpdateDataResponse(mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustUpdateProcess.isProcessRunning){
            StardustUpdateProcess.startSendingUpdateData(DataManager.context)
        }else {
            StardustUpdateProcess.cancelProcess()
            // TODO: get Data at position 1 and handle text errors
        }
    }

    private fun handleBittelGetBittelBootAddressResponse(mPackage: StardustPackage) {
        StardustUpdateProcess.sendInitUpdateProcess(mPackage.data, context)
    }

    private fun handlePingResponse (mPackage: StardustPackage) {
        DataManager.getPortUtils(DataManager.context).onPingReceived()
        val bittelBatteryPackage = StardustBatteryParser().parseBattery(mPackage)
        bittelBatteryPackage.let {
            AppEvents.updateBattery(it)
        }
    }

    private fun handleAdminModeResponse(mPackage: StardustPackage) {
        if(BleManager.isUsbEnabled()){
            BittelUsbManager2.updateBlePort()
            Timber.tag("startUpdatingPort").d("updateUsbPort")
        }else if (BleManager.isBluetoothEnabled()) {
            DataManager.getClientConnection(context).updateBlePort()
            Timber.tag("startUpdatingPort").d("updateBlePort")
        }
    }

    private fun handleDeleteGroupsResponse(mPackage: StardustPackage) {
        GroupsUtils.addAllGroups(DataManager.context)
    }

    private fun handleAddGroupsResponse(mPackage: StardustPackage) {
        getConfiguration ()
    }

    private fun getConfiguration () {
        SharedPreferencesUtil.getAppUser(DataManager.context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val configurationPackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode =StardustPackageUtils.StardustOpCode.READ_STATUS)
                clientConnection?.addMessageToQueue(configurationPackage)
            }
        }
    }

    private fun handleSaveConfigResponse(mPackage: StardustPackage) {
        if(mPackage.isAck() && StardustPolygonChange.isProcessRunning){
            StardustPolygonChange.updateServerOfSaveConfigSuccess()
        }
    }

    private fun handleUpdateAddressResponse (mPackage: StardustPackage) {
        if(mPackage.isAck()) {
            GroupsUtils.deleteAllGroups(context)
            DataManager.getClientConnection(context).removeConnectionTimer()
        }
    }

    private fun handleBittelFileResponse(mPackage: StardustPackage) {
        val data = mPackage.data ?: return
        val sig = listOf(83, 84, 82) // "STR"

        val matchAt0 = data.matchesSignatureAt(0, sig)
        val matchAt1 = data.matchesSignatureAt(1, sig)

        if (matchAt1) {
            StardustFileStartParser().parseFileStar2(mPackage)
                ?.let { FileSendUtils.handleFileStartReceive(it, mPackage) }
            return
        }
        if (matchAt0) {
            StardustFileStartParser().parseFileStart(mPackage)
                ?.let { FileSendUtils.handleFileStartReceive(it, mPackage) }
            return
        }


        StardustFileParser().parseFile(bittelPackage = mPackage)
            ?.let { FileSendUtils.handleFileReceive(it, mPackage) }
    }

    private fun handleUpdatePolygonFreqResponse(mPackage: StardustPackage) {
        if(mPackage.isAck()){
            StardustPolygonChange.updateServerOfFreqChange()
        }
    }

    private fun handleUpdatePolygonFreq(mPackage: StardustPackage) {
        StardustPolygonChange.startProcess("1", context)
    }



    private fun setOldLocals(stardustConfigurationPackage: StardustConfigurationPackage) {
//        if(CarriersUtils.isCarriersChanged(stardustConfigurationPackage) == true) {
//            CarriersUtils.getCarrierListAndUpdate(stardustConfigurationPackage)
//            CarriersUtils.getDefaults()
//
//        } else {
//            CarriersUtils.setLocalCarrierList ()
//        }
    }

    private fun setNewLocals(stardustConfigurationPackage: StardustConfigurationPackage) {
        ConfigurationUtils.setDefaults(DataManager.context)
//        CarriersUtils.getCarrierListAndUpdate(stardustConfigurationPackage)

//
//        //todo change
//        if(CarriersUtils.isCarriersChanged(stardustConfigurationPackage) == true) {
//            CarriersUtils.getCarrierListAndUpdate(stardustConfigurationPackage)
//            CarriersUtils.getDefaults()
//
//        } else {
//            CarriersUtils.setLocalCarrierList ()
//        }
    }

    private fun handleConfiguration(mPackage: StardustPackage) {
        Scopes.getMainCoroutine().launch {
            val bittelConfigurationPackage = StardustConfigurationParser().parseConfiguration(mPackage)

            bittelConfigurationPackage?. let {
                ConfigurationUtils.bittelConfiguration.value = bittelConfigurationPackage
                ConfigurationUtils.licensedFunctionalities = LicenseLimitationsUtil().createSupportedFunctionalitiesByLicenseType(bittelConfigurationPackage.licenseType)
                ConfigurationUtils.setConfigFile(it)
                setNewLocals(it)
                AdminUtils.updateBittelAdminMode()
            }
    }
    }

    private fun handleSOS (mPackage: StardustPackage) {
        val sosPackage = StardustLocationParser().parseSOS(mPackage)
        sosPackage?.let { UsersUtils.saveBittelUserSOS(mPackage, it) }
    }

    private fun handleAddressesReceived(mPackage: StardustPackage) {
        val addressesPackage = StardustAddressesParser().parseAddresses(mPackage)
        addressesPackage?.let {
            registerBittel(addressesPackage.stardustID)
            updateBittelSmartphoneAddress(addressesPackage)
//            updateLocalBittelID ()
        }
    }

    private fun updateBittelSmartphoneAddress(addressesPackage: StardustAddressesPackage) {
        // Added fix , push the id i have in my app
        val user = SharedPreferencesUtil.getAppUser(context)
        user?.appId?.let {
            val data = arrayListOf<Int>()
            data.addAll(StardustPackageUtils.hexStringToByteArray(it))
            data.add(0)
            data.add(0)
            data.add(0)
            data.add(0)
            data.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
            val mPackage = StardustPackageUtils.getStardustPackage(
                source = it , destenation = addressesPackage.stardustID,
                stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_ADDRESS,
                data = data.toIntArray().toTypedArray()
            )

            DataManager.getClientConnection(context).addMessageToQueue(mPackage)
        }
    }

    private fun registerBittel(bittelId: String){
        val savedUser = SharedPreferencesUtil.getAppUser(context)
        val deviceName = SharedPreferencesUtil.getBittelDeviceName(context)

        //Temp
        if (BleManager.isBluetoothEnabled() || BleManager.isUsbEnabled()) {
            deviceName?.let { name ->
                savedUser?.let { user ->
                    val newUser = RegisterUser(displayName = user.displayName, licenseType = "", phone = user.phone,
                        location = arrayOf(),
                        bittelId = bittelId, bittelName = name, bittelMacAddress = name,
                        appId = user.appId, token = user.token
                    )
                    user.appId?.let {appId ->
                        SharedPreferencesUtil.setAppUser(context, newUser)
                    }
                }
            }
        }
    }

    private fun updateLocalBittelID () {
        val savedUser = SharedPreferencesUtil.getAppUser(context)
        savedUser?.let {
            UsersUtils.updateRegisteredUser(it)
        }
    }


    private fun handleLocationReceived(mPackage: StardustPackage){
        if(mPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK){
            mPackage.stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE
            handleAck(mPackage)
        }
        val locationPackage = StardustLocationParser().parseLocation(mPackage)
        locationPackage?.let { LocationUtils.saveBittelUserLocation(mPackage, it) }
    }

    private fun handleLocationRequested(mPackage: StardustPackage, randomID: String){
        if(ConfigurationUtils.licensedFunctionalities[FunctionalityType.LOCATION] != LimitationType.ENABLED ) return
        Log.d("LocationRequest $randomID", "start ts ${System.currentTimeMillis()}")
        val src = mPackage.sourceBytes
        val dst = mPackage.destinationBytes
        mPackage.sourceBytes = src
        mPackage.destinationBytes = dst
        DataManager.getClientConnection(context).let {
            LocationUtils.sendMyLocation(mPackage,
                it, isHR = mPackage.stardustControlByte.stardustDeliveryType
            , randomID = randomID)
        }
    }

    private fun handlePTTAI(mPackage: StardustPackage) {
        PlayerUtils.saveBittelPTTAiToDatabase(bittelPackage = mPackage)
        Scopes.getDefaultCoroutine().launch {
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
            var chatItem = chatsRepo.getChatByBittelID(mPackage.getSourceAsString())
            if(chatItem == null) {
                UsersUtils.createNewBittelUserPTTSender(chatsRepo, mPackage)
            }
        }
    }

    private fun handlePTT(mPackage: StardustPackage) {
        PlayerUtils.saveBittelMessageToDatabase(bittelPackage = mPackage)
        Scopes.getDefaultCoroutine().launch {
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
            var chatItem = chatsRepo.getChatByBittelID(mPackage.getSourceAsString())
            if(chatItem == null) {
                UsersUtils.createNewBittelUserPTTSender(chatsRepo, mPackage)
            }
        }
    }

    private fun handleText(mPackage: StardustPackage) {
        if(mPackage.data !=null && mPackage.data!!.startsWith(arrayOf(83,79,83))) {
            handleSOS(mPackage)
        }else {
            if(mPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK){
                mPackage.stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE
                handleAck(mPackage)
            }
            UsersUtils.saveBittelMessageToDatabase(mPackage)
        }
    }

    private fun handleAck(mPackage: StardustPackage) {
        if(ConfigurationUtils.licensedFunctionalities[FunctionalityType.ACK] != LimitationType.ENABLED ||
            ConfigurationUtils.licensedFunctionalities[FunctionalityType.LOCATION] != LimitationType.ENABLED ) return

        Scopes.getDefaultCoroutine().launch {

            val bittelPackageToReturn = StardustPackageUtils.getStardustPackage(
                source = mPackage.getSourceAsString() , destenation = mPackage.getDestAsString(), stardustOpCode = mPackage.stardustOpCode, data =  arrayOf(StardustPackageUtils.Ack, 0x00)
            )
            bittelPackageToReturn.stardustControlByte.stardustAcknowledgeType = StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            bittelPackageToReturn.stardustControlByte.stardustDeliveryType = mPackage.stardustControlByte.stardustDeliveryType
            DataManager.getClientConnection(context).let {
                LocationUtils.sendMyLocation(bittelPackageToReturn, it, opCode = StardustPackageUtils.StardustOpCode.SEND_DATA_RESPONSE, isHR = mPackage.stardustControlByte.stardustDeliveryType)
            }

//            sendDataToBle(bittelPackageToReturn)
        }
    }

    private fun handleBittelLog (mPackage: StardustPackage) {
        val logPackage = StardustLogParser().parseLog(mPackage)
        logPackage?.let {
            LogUtils.appendToList(it)
        }
    }

    private fun sendDataToBle(bittelPackage: StardustPackage) {
        Handler(Looper.getMainLooper()).postDelayed({
            DataManager.getClientConnection(context).sendMessage(bittelPackage)
        }, 50)
    }

    fun killObserver () {
        StardustPackageUtils.packageLiveData.removeObserver(observer)
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