package com.commcrete.stardust.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.stardust.AckSystem
import com.commcrete.stardust.stardust.AckSystem.Companion.DELAY_TS_HR
import com.commcrete.stardust.stardust.AckSystem.Companion.DELAY_TS_LR
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import java.util.Locale
import java.util.UUID

internal class ClientConnection(
    private val context: Context
) : BleManager(context) {

    private val TAG = ClientConnection::class.java.simpleName

    private var characteristic: BluetoothGattCharacteristic? = null
    private var indicationCharacteristics: BluetoothGattCharacteristic? = null
    private var reliableCharacteristics: BluetoothGattCharacteristic? = null
    private var readCharacteristics: BluetoothGattCharacteristic? = null

    var gattConnection : BluetoothGatt? = null
    var mDevice : BluetoothDevice? = null
    var deviceLastDigit = ""
    var counter  : Int = 0

    val mutableMessageList = mutableListOf<StardustPackage>()
    val mutableAckAwaitingList = mutableListOf<AckSystem>()


    private val handler : Handler = Handler(Looper.getMainLooper())
    var bittelPackage : StardustPackage? = null
    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        if(mutableMessageList.isNotEmpty()){
            sendMessage(mutableMessageList[0])
        }
    }

    val handlerRSSI = Handler(Looper.getMainLooper())

    val readRssiRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            gattConnection?.readRemoteRssi()
            resetRSSITimer()
        }
    }



    var uuid : UUID? = null


    var hasCallback = false
    var deviceName : String?  = ""

    val mapHRLR : MutableMap<String, Boolean> = mutableMapOf()

    private fun gettCallback () : BluetoothGattCallback{
        return object : BluetoothGattCallback(){

            private val handler : Handler = Handler(Looper.getMainLooper())

            override fun onPhyUpdate(
                gatt: BluetoothGatt?,
                txPhy: Int,
                rxPhy: Int,
                status: Int
            ) {
                super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            }

            override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
                super.onPhyRead(gatt, txPhy, rxPhy, status)
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt?,
                status: Int,
                newState: Int
            ) {
                super.onConnectionStateChange(gatt, status, newState)
                val mtu = gatt?.requestMtu(200)
                if(status == 0 && newState == 2){
                    Handler(Looper.getMainLooper()).postDelayed({gatt?.discoverServices()} , 2000)
                }else {
                    Scopes.getMainCoroutine().launch {
                        com.commcrete.stardust.ble.BleManager.isBleConnected = false
                        com.commcrete.stardust.ble.BleManager.bleConnectionStatus.value = false
                    }
                }
            }

            @SuppressLint("MissingPermission")
            private fun setDevice() {
                mDevice?.name?.let {
                    deviceLastDigit = it.takeLast(2)
                    uuid = Characteristics.getWriteChar(deviceLastDigit)
                }
                deviceName?.let { it1 ->
                    if(it1.isEmpty()){
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            mDevice?.alias?.let {
                                SharedPreferencesUtil.setBittelDeviceName(context, it)
                            }
                        } else {
                            mDevice?.name?.let {
                                SharedPreferencesUtil.setBittelDeviceName(context, it)
                            }
                        }
                    }else {
                        SharedPreferencesUtil.setBittelDeviceName(context, it1)
                    }
                }
                mDevice?.address?.let { SharedPreferencesUtil.setBittelDevice(context, it) }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                setDevice()
                Scopes.getMainCoroutine().launch {
                    if(com.commcrete.stardust.ble.BleManager.isPaired.value == true){
                        gattConnection = gatt
                        com.commcrete.stardust.ble.BleManager.isBleConnected = true
                        com.commcrete.stardust.ble.BleManager.bleConnectionStatus.value = true
                        resetRSSITimer()
                    }
                }
                gatt?.requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH)
                val id = deviceLastDigit
                val readUUID = Characteristics.getReadChar(id)
                val writeUUID = Characteristics.getWriteChar(id)
                val readChar = gatt?.getService(Characteristics.getConnectChar(id))
                    ?.getCharacteristic(readUUID)
                if(readChar!=null){
                    gatt.setCharacteristicNotification(readChar, true)
                    val desc = readChar.descriptors?.get(0)
                    desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(desc)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    SharedPreferencesUtil.getAppUser(context)?.let {
                        val mPackage = StardustPackageUtils.getStardustPackage(
                            source = "0" , destenation = "1", stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADDRESS)
                        addMessageToQueue(mPackage)
                    }
                }, 4000)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                clearTimer()
                characteristic.value?.let {
                    clearTimer()
                    StardustPackageUtils.handlePackageReceived(it)
                }

            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
            }

            override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
                super.onReliableWriteCompleted(gatt, status)
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Handle the RSSI value
                    Scopes.getMainCoroutine().launch {
                        com.commcrete.stardust.ble.BleManager.rssi.value = rssi
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)

            }


        }
    }


    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun getMinLogPriority(): Int {
        return Log.VERBOSE
    }

    // Return false if a required service has not been discovered.
    @SuppressLint("MissingPermission")
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        gatt.getService(Characteristics.UUID_SERVICE_DEVICE)?.let { service ->
            characteristic = service.getCharacteristic(Characteristics.WRITE_CHARACTERISTIC)
            indicationCharacteristics = service.getCharacteristic(Characteristics.IND_CHARACTERISTIC)
            reliableCharacteristics = service.getCharacteristic(Characteristics.REL_WRITE_CHARACTERISTIC)
            readCharacteristics = service.getCharacteristic(Characteristics.READ_CHARACTERISTIC)
        }
        return characteristic != null &&
                indicationCharacteristics != null &&
                reliableCharacteristics != null &&
                readCharacteristics != null
    }

    override fun initialize() {
        // TODO: return ?
//        requestMtu(512).enqueue()
        requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH).enqueue()
    }

    override fun onServicesInvalidated() {
        characteristic = null
        indicationCharacteristics = null
        reliableCharacteristics = null
        readCharacteristics = null
    }


    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        if(!hasCallback) {
            device.connectGatt(context, true, getBleGattCallback(device))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectFromDevice () {
        gattConnection?.disconnect()
        gattConnection?.close()
        gattConnection = null
        hasCallback = false
        Scopes.getMainCoroutine().launch {
            com.commcrete.stardust.ble.BleManager.isBleConnected = false
            com.commcrete.stardust.ble.BleManager.bleConnectionStatus.value = false
            removeRSSITimer()
        }
    }


    private fun getBleGattCallback(device: BluetoothDevice): BluetoothGattCallback {
        mDevice = device
        hasCallback = true
        return gettCallback()
    }

    fun release() {
        cancelQueue()
        disconnect().enqueue()
    }

    @SuppressLint("MissingPermission")
    fun bondToBleDevice(device: BluetoothDevice, deviceName : String?) {
        this.deviceName = deviceName
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(
                    broadcastReceiver,
                    IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    ,Context.RECEIVER_EXPORTED
                )
            }else {
                context.applicationContext.registerReceiver(
                    broadcastReceiver,
                    IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                ) }
//            device.createBond()
            device.connectGatt(context, false, object  : BluetoothGattCallback() {})
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            with(intent) {
                if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED ) {
                    val device = getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val previousBondState = getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    val bondState = getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val bondTransition = "${previousBondState.toBondStateDescription()} to " +
                            bondState.toBondStateDescription()
                    Log.w("Bond state change", "${device?.address} bond state changed | $bondTransition")
                    if(bondState == BluetoothDevice.BOND_BONDED && previousBondState == BluetoothDevice.BOND_BONDING) {
                        device?.address?.let { SharedPreferencesUtil.setBittelDevice(context, it) }
                        device?.name?.let { SharedPreferencesUtil.setBittelDeviceName(context, it) }
                        device?.let {
                            CoroutineScope(Dispatchers.IO).launch {
                                Scopes.getMainCoroutine().launch {
                                    com.commcrete.stardust.ble.BleManager.isPaired.value = true
                                    connectDevice(device)
                                }
                            }
                        }
                    } else if(bondState == BluetoothDevice.BOND_BONDING && previousBondState == BluetoothDevice.BOND_NONE) {

                        disconnectFromDevice()
                    } else{
                        device?.let {
                            try {
                                it::class.java.getMethod("removeBond").invoke(it)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        disconnectFromDevice()
                    }
                }
            }
        }

        private fun Int.toBondStateDescription() = when(this) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_NONE -> "NOT BONDED"
            else -> "ERROR: $this"
        }
    }

    @SuppressLint("MissingPermission")
    fun getBleConnectedDevices(uuid : String) : BluetoothDevice?{
        val btManager = context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if(btManager == null || btManager.adapter == null) {
            return null
        }
        val pairedDevices = btManager.adapter.bondedDevices

        if (pairedDevices.size > 0) {

            for (device in pairedDevices) {
                val deviceName = device.name
                val macAddress = device.address
                val aliasing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.alias
                } else {
                    "Empty"
                }

                Log.i(
                    " pairedDevices ",
                    "paired device: $deviceName at $macAddress + $aliasing "
                )
                if(device.address.equals(uuid)
                    || aliasing?.lowercase(Locale.getDefault())?.contains("bittle") == true
                    || aliasing?.lowercase(Locale.getDefault())?.contains("bittel") == true){
                    return device
                }
            }
        }
        SharedPreferencesUtil.setBittelDevice(context, "empty")
        SharedPreferencesUtil.setBittelDeviceName(context, "empty")
        return null
    }

    fun addMessageToQueue(bittelPackage: StardustPackage) {
        mutableMessageList.add(bittelPackage)
        sendMessage(mutableMessageList[0])
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(bittelPackage: StardustPackage){
        if(mutableAckAwaitingList.isNotEmpty()) {
            Handler(Looper.getMainLooper()).postDelayed({
                sendMessage(bittelPackage)
            }, 100)
            return
        }
        bittelPackage.stardustControlByte.stardustServer = StardustControlByte.StardustServer.NOT_SERVER
//        bittelPackage.StardustControlByte.bittelServer = if(SharedPreferencesUtil.getIsStardustServerBitEnabled(DataManager.context))
//            StardustControlByte.StardustServer.SERVER else StardustControlByte.StardustServer.NOT_SERVER

        bittelPackage.checkXor = StardustPackageUtils.getCheckXor(bittelPackage.getStardustPackageToCheckXor())
        if(bittelPackage.isAbleToSendAgain()){
            resetTimer(bittelPackage)
            SharedPreferencesUtil.getAppUser(context)?.let {
                val id = deviceLastDigit
                val uuid = Characteristics.getWriteChar(id)
                bittelPackage.updateRetryCounter()
                gattConnection?.getService(Characteristics.getConnectChar(id))?.getCharacteristic(uuid)
                    ?.let {
                        writePackage(it, bittelPackage)
                    }
                if(mutableMessageList.isNotEmpty()){
                    mutableMessageList.removeAt(0)
                }
            }
        }else {
            if(mutableMessageList.isNotEmpty()){
                mutableMessageList.removeAt(0)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writePackage(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count : Int = 0
    ) {
        if(count>3){
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val write = gattConnection?.writeCharacteristic(
                bluetoothGattCharacteristic,
                bittelPackage.getStardustPackageToSend(),
                WRITE_TYPE_DEFAULT
            )
            write?.let {
                if(write !=0){
                    writePackage(bluetoothGattCharacteristic, bittelPackage, count+1)
                    if(write == 2147483647) {
                        reconnectToDevice()
                    }
                } else {
                    checkIfPackageDemandsAck(bittelPackage)
                }
            }
        } else {
            bluetoothGattCharacteristic.value = bittelPackage.getStardustPackageToSend()
            val write = gattConnection?.writeCharacteristic(bluetoothGattCharacteristic)
            write?.let {
                if(!it){
                    writePackage(bluetoothGattCharacteristic, bittelPackage, count+1)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendDataTest(byteArray: ByteArray, i: Int){
        gattConnection?.getService(Characteristics.getConnectChar(deviceLastDigit))?.getCharacteristic(uuid)
            ?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val write = gattConnection?.writeCharacteristic(
                        it,
                        byteArray,
                        WRITE_TYPE_NO_RESPONSE
                    )
                } else {
                    val write = gattConnection?.writeCharacteristic(it)

                }
            }
    }

    private fun checkIfPackageDemandsAck (bittelPackage: StardustPackage) {
        if((bittelPackage.stardustOpCode == StardustPackageUtils.StardustOpCode.SEND_MESSAGE && bittelPackage.stardustControlByte.stardustPackageType == StardustControlByte.StardustPackageType.DATA
                    && bittelPackage.stardustControlByte.stardustMessageType != StardustControlByte.StardustMessageType.SNIFFED) || bittelPackage.stardustOpCode == StardustPackageUtils.StardustOpCode.REQUEST_LOCATION){
            if(bittelPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK){
                val ackSystem = AckSystem(bittelPackage, object : AckSystem.AckSystemNotify {
                    override fun onFailure() {
                        try {
                            mutableAckAwaitingList.removeAt(0)
                        }catch (e : Exception) {
                            e.printStackTrace()
                        }
                    }

                    override fun onSuccess() {
                        try {
                            val message = mutableAckAwaitingList[0]
                            mutableAckAwaitingList.removeAt(0)
                            syncMessageReceivedStatus(message)
                        }catch (e : Exception) {
                            e.printStackTrace()
                        }
                        // TODO: change message to Received
                    }

                })
                ackSystem.delayTS = if(bittelPackage.stardustControlByte.stardustDeliveryType == StardustControlByte.StardustDeliveryType.HR) DELAY_TS_HR else DELAY_TS_LR
                ackSystem.start()
                mutableAckAwaitingList.add(ackSystem)
            }
        }
    }

    fun syncMessageReceivedStatus(message: AckSystem) {
        Scopes.getDefaultCoroutine().launch {
            val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao())
            val bittelPackage = message.stardustPackage
            messagesRepository.updateAckReceived(chatid = bittelPackage.getDestAsString(),
                messageNumber = bittelPackage.idNumber)

        }
    }

    fun handleAckReceived () {
        if(mutableAckAwaitingList.isNotEmpty()) {
            mutableAckAwaitingList[0].notifySuccess()
        }
    }

    private fun resetTimer(bittelPackage: StardustPackage) {
        this.bittelPackage = bittelPackage
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, StardustPackage.DELAY_TS)
    }

    private fun resetRSSITimer() {
        handlerRSSI.removeCallbacks(readRssiRunnable)
        handlerRSSI.removeCallbacksAndMessages(null)
        handlerRSSI.postDelayed(readRssiRunnable, 1000)
    }

    private fun removeRSSITimer() {
        handlerRSSI.removeCallbacks(readRssiRunnable)
        handlerRSSI.removeCallbacksAndMessages(null)
    }

    private fun clearTimer(){
        try {
            if(mutableMessageList.isNotEmpty()){
                mutableMessageList.removeAt(0)
            }
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()

        }
    }

    private fun logByteArray(tagTitle: String, bDataCodec: ByteArray) {
        val stringBuilder = StringBuilder()
        for (element in bDataCodec) {
            stringBuilder.append("${element},")
        }
    }

    private fun isAck(value: ByteArray): Boolean {
        val ack : ByteArray = byteArrayOf( 0xC1.toByte(), 0x78, 0xED.toByte())
        val newValue = value.copyOfRange(1, value.size)
        return newValue.contentEquals(ack)
    }

    fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean =
        properties and property != 0

    fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    fun BluetoothGattCharacteristic.isWritable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)


    private fun reconnectToDevice () {
        disconnectFromDevice ()
        Handler(Looper.myLooper()!!).postDelayed({
            mDevice?.let { connectDevice(it) }
        },2000)
    }
}

