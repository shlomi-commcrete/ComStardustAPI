package com.commcrete.stardust.stardust

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
import com.commcrete.bittell.util.bittel_package.model.StardustFileParser
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.stardust.model.StardustAddressesPackage
import com.commcrete.stardust.stardust.model.StardustAddressesParser
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustLocationParser
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.LogUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.update.StardustUpdateProcess
import com.commcrete.stardust.stardust.model.StardustLogParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.AdminUtils
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileSendUtils
import com.commcrete.stardust.util.GroupsUtils
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

    fun handleStardustPackage(bittelPackage: StardustPackage?) {
        if(bittelPackage != null){
            val mPackage = bittelPackage
            val tempSavedPackage = savedPackage
            if(tempSavedPackage == null ||!mPackage.isEqual(tempSavedPackage)){
                if(mPackage.stardustOpCode != StardustPackageUtils.StardustOpCode.PING_RESPONSE){
                    savedPackage = mPackage
                }
                Timber.tag(ClientConnection.LOG_TAG).d("handlePackageReceivedbyteArray : ${mPackage}")
//                SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let {
//                    if(mPackage.getDestAsString() != it && mPackage.getSourceAsString() != it
//                        && mPackage.getDestAsString() != "00000002") {
//                        Timber.tag(LOG_TAG).d("Message not for user")
//                        return
//                    }
//                }
                val mPackageControl = mPackage.stardustControlByte
                val mPackageOpCode = mPackage.stardustOpCode
                if(mPackage.getDestAsString() == "00000002"){
                    val srcBytes = mPackage.sourceBytes
                    val dstBytes = mPackage.destinationBytes
                    mPackage.sourceBytes = dstBytes
                    mPackage.destinationBytes = srcBytes

                }
                if(mPackage.stardustControlByte.stardustMessageType == StardustControlByte.StardustMessageType.SNIFFED) {
                    // TODO: Handle Sniffed message
                    return
                }
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
                    StardustPackageUtils.StardustOpCode.REQUEST_LOCATION -> {
                        handleLocationRequested(mPackage)
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
                        handleVersion(mPackage)
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
                    else -> {}
                } }
            resetTimer()
        }
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
        if (mPackage.data != null && mPackage.data!!.startsWith(arrayOf(6,83, 84, 82))) {
            StardustFileStartParser().parseFileStart(bittelPackage = mPackage)
                ?.let { FileSendUtils.handleFileStartReceive(it, mPackage) }
            return
        }
        if (mPackage.data != null && mPackage.data!!.startsWith(arrayOf(83, 84, 82))) {
            StardustFileStartParser().parseFileStar2(bittelPackage = mPackage)
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

    private fun handleVersion(mPackage: StardustPackage) {
        Scopes.getMainCoroutine().launch {
            UsersUtils.bittelVersion.value = mPackage.getDataAsString()
        }
    }

    private fun handleConfiguration(mPackage: StardustPackage) {
        Scopes.getMainCoroutine().launch {
            val bittelConfigurationPackage = StardustConfigurationParser().parseConfiguration(mPackage)
            UsersUtils.bittelConfiguration.value = bittelConfigurationPackage
            bittelConfigurationPackage?. let {

            if(CarriersUtils.isCarriersChanged(it) == true) {
                CarriersUtils.getCarrierListAndUpdate(bittelConfigurationPackage)
                CarriersUtils.getDefaults()

            } else {
                CarriersUtils.setLocalCarrierList ()
            }
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

    private fun handleLocationRequested(mPackage: StardustPackage){
        val src = mPackage.sourceBytes
        val dst = mPackage.destinationBytes
        mPackage.sourceBytes = src
        mPackage.destinationBytes = dst
        DataManager.getClientConnection(context).let {
            LocationUtils.sendMyLocation(mPackage,
                it, isHR = mPackage.stardustControlByte.stardustDeliveryType
            )
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