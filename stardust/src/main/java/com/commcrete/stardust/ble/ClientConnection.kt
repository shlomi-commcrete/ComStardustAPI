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
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.commcrete.stardust.stardust.AckSystem
import com.commcrete.stardust.stardust.AckSystem.Companion.DELAY_TS_LR
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.BittelProtocol
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.SharedPreferencesUtil.getAppUser
import com.commcrete.stardust.util.SharedPreferencesUtil.setAppUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.nordicsemi.andorid.ble.test.spec.Characteristics
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import no.nordicsemi.android.ble.BleManager as NordicBleManager

internal class ClientConnection(
    private val context: Context
) : NordicBleManager(context), BittelProtocol {

    companion object{
        const val LOG_TAG = "stardust_tag"
        const val MAX_WRITE_RETRIES = 3
        const val RETRY_DELAY_MS = 500L
        const val WRITE_ERROR_CODE = 2147483647
    }
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

    private val connectionTimeout : Long = 20000
    private val bondTimeout : Long = 5000
    private val pingTimeout : Long = 10000

    private var bluetoothGattCallback: BluetoothGattCallback? = null


    private val connectionHandler : Handler = Handler(Looper.getMainLooper())
    private val connectionRunnable : Runnable = kotlinx.coroutines.Runnable {
        reconnectToDevice()
    }

    private val pingRunnable : Runnable = kotlinx.coroutines.Runnable {
        sendPing ()
        resetPingTimer()
    }
    private val pingHandler : Handler = Handler(Looper.getMainLooper())

    init {
        initBleStatus ()
    }

    fun sendPing () {
        RegisteredUserUtils.mRegisterUser.value?.let {
            val src = it.appId
            val dst = it.deviceId
            if(src != null && dst != null) {
                val versionPackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destination = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.PING)
                addMessageToQueue(versionPackage)
            }
        }
    }

    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        if(mutableMessageList.isNotEmpty()){
            sendMessage(mutableMessageList[0])
        }
    }

    var lastPlayedTS : Long = 0

    private val bondRunnable : Runnable = kotlinx.coroutines.Runnable {

        // TODO: show fail
    }
    private val bondHandler : Handler = Handler(Looper.getMainLooper())

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
        if (bluetoothGattCallback == null) {
            bluetoothGattCallback = object : BluetoothGattCallback(){

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
                    Timber.tag(LOG_TAG).d("status : $status\nnewState : $newState")
                    val mtu = gatt?.requestMtu(200)
                    Timber.tag("SetMtu").d("$mtu")
                    if(status == 0 && newState == 2){
                        Handler(Looper.getMainLooper()).postDelayed({ gatt?.discoverServices() } , 2000)
                    } else {
                        Scopes.getMainCoroutine().launch {
                            Timber.tag("Bittel Disconnected").d("Status Changed")
                            Timber.tag(LOG_TAG).d("Bittel Disconnected")
                            BleManager.isBleConnected = false
                            BleManager.bleConnectionStatus.value = false
                            BleManager.updateStatus()
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
                        if(BleManager.isPaired.value == true){
                            Timber.tag(LOG_TAG).d("gattConnection")
                            gattConnection = gatt
                            BleManager.isBleConnected = true
                            BleManager.bleConnectionStatus.value = true
                            BleManager.updateStatus()
                            resetRSSITimer()
                        }
                    }
                    gatt?.requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH)
                    val id = deviceLastDigit
                    val readUUID = Characteristics.getReadChar(id)

                    val readChar = gatt?.getService(Characteristics.getConnectChar(id))
                        ?.getCharacteristic(readUUID)

                    if(readChar != null){
                        Timber.tag(LOG_TAG).d("has Char")
                        gatt.setCharacteristicNotification(readChar, true)
                        val desc = readChar.descriptors?.get(0)
                        desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(desc)
                    }
                    StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)

                    Handler(Looper.getMainLooper()).postDelayed({
                        RegisteredUserUtils.mRegisterUser.value?.let {
                            if(it.appId != null
                                && getBlePairedStardustDevice() != null
                                && StardustInitConnectionHandler.isSearchingToConnect()) {

                                StardustInitConnectionHandler.listener = object : StardustInitConnectionHandler.InitConnectionListener {}

                                StardustInitConnectionHandler.start()

                                resetConnectionTimer()
                                resetPingTimer()
                            }
                        }
                    }, 500)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int
                ) {
                    super.onCharacteristicWrite(gatt, characteristic, status)
                }

                fun fastRandomId(length: Int = 6): String {
                    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                    val random = kotlin.random.Random
                    val sb = StringBuilder(length)

                    repeat(length) {
                        sb.append(chars[random.nextInt(chars.length)])
                    }
                    return sb.toString()
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    val randomID = fastRandomId()
                    Log.d("Ble write $randomID", "onCharacteristicChanged")
//                    Timber.tag("onCharacteristicChanged").d("onCharacteristicChanged2")
                    characteristic.value?.let {
//                        Timber.tag("onCharacteristicChanged").d("without Value")
                        StardustPackageUtils.handlePackageReceived(context, it, randomID)
                        clearTimer()
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    super.onCharacteristicChanged(gatt, characteristic, value)
                    Log.d("Ble write", "onCharacteristicChanged")
//                    Timber.tag("onCharacteristicChanged").d("onCharacteristicChanged3")
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt?,
                    descriptor: BluetoothGattDescriptor?,
                    status: Int
                ) {
                    super.onDescriptorWrite(gatt, descriptor, status)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag("NotificationSetup").d("Notification successfully enabled for ${descriptor?.characteristic?.uuid}")
                    } else {
                        Timber.tag("NotificationSetup").e("Failed to enable notification for ${descriptor?.characteristic?.uuid}, status: $status")
                    }
                }

                override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
                    super.onReliableWriteCompleted(gatt, status)
                }

                override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                    super.onReadRemoteRssi(gatt, rssi, status)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Handle the RSSI value
                        Scopes.getMainCoroutine().launch {
                            BleManager.rssi.value = rssi
                            DataManager.getCallbacks()?.onRSSIChanged(rssi)
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    super.onMtuChanged(gatt, mtu, status)

                }


            }
        }
            return bluetoothGattCallback!!
    }

    fun initBleStatus () {
        BluetoothStateManager.bluetoothState.observeForever(object : Observer<Boolean> {
            override fun onChanged(isConnected: Boolean) {
                if(!isConnected) {
                    disconnectFromBLEDevice(true)
                } else if(
                    !BleManager.isBleConnected
                    && BleManager.isBluetoothToggleEnabled){
                        mDevice?.let { connectDevice(it) }
                }
            }

        })
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
    fun disconnectFromBLEDevice (disconnectByForce: Boolean = false) {
        if(!disconnectByForce && !StardustInitConnectionHandler.isConnected()) return
        gattConnection?.disconnect()
        gattConnection?.close()
        bleGatChar = null
        gattConnection = null
        hasCallback = false
        ConfigurationUtils.reset()
        CarriersUtils.reset()
        Scopes.getMainCoroutine().launch {
            Timber.tag("Bittel Disconnected").d("Called Function")
            Timber.tag(LOG_TAG).d("Bittel Disconnected")
//            com.commcrete.stardust.ble.BleManager.isBleConnected = false
//            com.commcrete.stardust.ble.BleManager.bleConnectionStatus.value = false
            BleManager.updateStatus()
            removeRSSITimer()
            removePingTimer()
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
        device.name?.let {
            val connectedDevice = getBleConnectedDevice(device.address)
            if(connectedDevice != null) {
                BleManager.isPaired.value = true
                connectDevice(device)
                return
            }
        }
        this.deviceName = deviceName
        resetBondTimer()
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
            Timber.tag(LOG_TAG).d("bondToBleDevice")
            device.connectGatt(context, false, object  : BluetoothGattCallback() {})
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun bondToBleDeviceStartup(connectedDevice: BluetoothDevice) {
        Scopes.getMainCoroutine().launch {
            BleManager.isPaired.value = true
        }
        connectDevice(connectedDevice)
        this.deviceName = connectedDevice.name
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            with(intent) {
                Timber.tag(LOG_TAG).d(" broadcastReceiver onReceive")
                if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED ) {
                    val device = getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val previousBondState = getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    val bondState = getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val bondTransition = "${previousBondState.toBondStateDescription()} to " +
                            bondState.toBondStateDescription()
                    Log.w("Bond state change", "${device?.address} bond state changed | $bondTransition")
                    if(bondState == BluetoothDevice.BOND_BONDED && previousBondState == BluetoothDevice.BOND_BONDING) {
                        //device?.address?.let { SharedPreferencesUtil.setBittelDevice(context, it) }
                        //device?.name?.let { SharedPreferencesUtil.setBittelDeviceName(context, it) }
                        device?.let {
                            CoroutineScope(Dispatchers.IO).launch {
                                Scopes.getMainCoroutine().launch {
                                    BleManager.isPaired.value = true
                                }
                                connectDevice(device)
                                removeBondTimer()
                            }
                        }
                    } else if(bondState == BluetoothDevice.BOND_BONDING && previousBondState == BluetoothDevice.BOND_NONE) {
                        disconnectFromBLEDevice()
                    } else{
                        device?.let {
                            try {
                                it::class.java.getMethod("removeBond").invoke(it)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        disconnectFromBLEDevice()
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
    private fun requestUnbond(device: BluetoothDevice): Boolean {
        // Hidden Android API; may fail on some devices/OS versions.
        return runCatching {
            val method = device.javaClass.getMethod("removeBond")
            (method.invoke(device) as? Boolean) == true
        }.onFailure {
            Timber.tag(LOG_TAG).e(it, "Failed to request unbond for ${device.address}")
        }.getOrDefault(false)
    }

    fun removeBittelBond () {
        val deviceToUnbond = mDevice
            ?: SharedPreferencesUtil.getBittelDevice(context)
                ?.takeIf { it.isNotBlank() && !it.equals("empty", ignoreCase = true) }
                ?.let { getBleConnectedStardustDeviceBySavedAddress(it) }

        disconnectFromBLEDevice(true)

        deviceToUnbond?.let {
            val started = requestUnbond(it)
            Timber.tag(LOG_TAG).d("Unbond requested for ${it.address}: started=$started")
        }

        Scopes.getDefaultCoroutine().launch {
            SharedPreferencesUtil.removeBittelDevice(DataManager.context)
            SharedPreferencesUtil.removeBittelDeviceName(DataManager.context)
        }

        mDevice = null
        Scopes.getMainCoroutine().launch {
            BleManager.isPaired.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun getBleConnectedDevice(uuid : String) : BluetoothDevice?{
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
                if(device.address.equals(uuid)){
                    return device
                }
            }
        }
        return null
    }
    @SuppressLint("MissingPermission")
    fun getBleConnectedStardustDeviceBySavedAddress(savedAddress : String) : BluetoothDevice?{
        val btManager = context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if(btManager == null || btManager.adapter == null) {
            return null
        }
        val pairedDevices = btManager.adapter.bondedDevices
        if (pairedDevices.size > 0) {

            for (device in pairedDevices) {
                val macAddress = device.address
                if(macAddress == savedAddress) {
                    return device
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun getBleConnectedStardustDevice() : BluetoothDevice? {
        val btManager = context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if(btManager == null || btManager.adapter == null) {
            return null
        }
        val pairedDevices = btManager.adapter.bondedDevices

        if (pairedDevices.size > 0) {
            val savedAddress = SharedPreferencesUtil.getBittelDevice(DataManager.context)

            for (device in pairedDevices) {
                val deviceName = device.name
                val macAddress = device.address
                if(savedAddress == macAddress) {
                    return device
                }
                val aliasing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.alias?.lowercase(Locale.getDefault())
                } else {
                    null
                }

                Log.i(
                    " pairedDevices ",
                    "paired device: $deviceName at $macAddress + $aliasing "
                )
                aliasing?.let {
                     if(listOf("bittle", "bittel", "stardust").find { aliasing.contains(it) } != null) {
                         return device
                     }
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun getBlePairedStardustDevice() : BluetoothDevice? {
        val savedAddress = SharedPreferencesUtil.getBittelDevice(DataManager.context)

        if(savedAddress.isNullOrBlank()) { return null }

        val btManager = context.getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        if(btManager == null || btManager.adapter == null) {
            return null
        }
        val pairedDevices = btManager.adapter.bondedDevices

        if (pairedDevices.size > 0) {

            for (device in pairedDevices) {
                val macAddress = device.address
                if(savedAddress == macAddress) {
                    return device
                }
            }
        }
        return null
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
                    || aliasing?.lowercase(Locale.getDefault())?.contains("bittel") == true
                    || aliasing?.lowercase(Locale.getDefault())?.contains("stardust") == true){
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

    fun isNeedAck (opCode: StardustPackageUtils.StardustOpCode) : Boolean {
        return opCode != StardustPackageUtils.StardustOpCode.SEND_PTT_AI
    }

    private var bleGatChar : BluetoothGattCharacteristic? = null
    @SuppressLint("MissingPermission")
    fun sendMessage(bittelPackage: StardustPackage, randomID : String = "") {
        // TODO: check if FunctionalityType is valid by licence here ??
        if(mutableAckAwaitingList.isNotEmpty() && isNeedAck(bittelPackage.stardustOpCode)) {
            Handler(Looper.getMainLooper()).postDelayed({
                sendMessage(bittelPackage, randomID)
            }, 100)
            return
        }
        bittelPackage.stardustControlByte.stardustServer = StardustControlByte.StardustServer.NOT_SERVER
//        bittelPackage.StardustControlByte.bittelServer = if(SharedPreferencesUtil.getIsStardustServerBitEnabled(DataManager.context))
//            StardustControlByte.StardustServer.SERVER else StardustControlByte.StardustServer.NOT_SERVER
        Log.d("checkXor $randomID", "sendMessage")
        bittelPackage.checkXor = StardustPackageUtils.getCheckXor(bittelPackage.getStardustPackageToCheckXor())
        Log.d("checkXorfini $randomID", "sendMessage")
        if(bittelPackage.isAbleToSendAgain()){
            if(!BleManager.isBluetoothEnabled() && !BleManager.isUSBConnected){
                Timber.tag(LOG_TAG).d("Bluetooth not available, either settings or disconnected")
            }
            Log.d("isAbleToSendAgain $randomID", "sendMessage")

            Timber.tag(LOG_TAG).d("Sending Package $randomID")
            Scopes.getDefaultCoroutine().launch {
                resetTimer(bittelPackage)
            }
            SharedPreferencesUtil.getAppUser(context)?.let {
                Log.d("getAppUser $randomID", "sendMessage")

                val id = deviceLastDigit
                val uuid = Characteristics.getWriteChar(id)
                bittelPackage.updateRetryCounter()
                if(BleManager.isUSBConnected) {
                    BittelUsbManager2.sendDataToUart(bittelPackage)
                }else {
                    gattConnection?.getService(Characteristics.getConnectChar(id))?.getCharacteristic(uuid)
                        ?.let {
                            writePackage(it, bittelPackage, randomID = randomID)
                        }
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
        count: Int = 0,
        randomID: String = ""
    ) {
        Timber.tag(LOG_TAG).d("writePackage attempt $count/${MAX_WRITE_RETRIES} - opCode: ${bittelPackage.stardustOpCode}")

        if (count > MAX_WRITE_RETRIES) {
            Timber.tag(LOG_TAG).w("Max write retries exceeded for ${bittelPackage.stardustOpCode}")
            return
        }

        if (count > 0) {
            scheduleRetry(bluetoothGattCharacteristic, bittelPackage, count, randomID)
        } else {
            performWrite(bluetoothGattCharacteristic, bittelPackage, count, randomID)
        }
    }

    /**
     * Schedules a delayed retry of the write operation.
     */
    private fun scheduleRetry(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count: Int,
        randomID: String
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            performWrite(bluetoothGattCharacteristic, bittelPackage, count, randomID)
        }, RETRY_DELAY_MS)
    }

    /**
     * Performs the actual write operation using platform-appropriate API.
     */
    @SuppressLint("MissingPermission")
    private fun performWrite(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count: Int,
        randomID: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            performWriteAndroid13Plus(bluetoothGattCharacteristic, bittelPackage, count, randomID)
        } else {
            performWriteLegacy(bluetoothGattCharacteristic, bittelPackage, count, randomID)
        }
    }

    /**
     * Android 13+ write path using new BluetoothGatt API.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("MissingPermission")
    private fun performWriteAndroid13Plus(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count: Int,
        randomID: String
    ) {
        val writeResult = gattConnection?.writeCharacteristic(
            bluetoothGattCharacteristic,
            bittelPackage.getStardustPackageToSend(context),
            WRITE_TYPE_DEFAULT
        )

        when {
            writeResult == null -> {
                Timber.tag(LOG_TAG).e("gattConnection is null, cannot write")
            }
            writeResult == 0 -> {
                Timber.tag(LOG_TAG).d("Write succeeded on attempt ${count + 1}")
                checkIfPackageDemandsAck(bittelPackage)
            }
            writeResult == WRITE_ERROR_CODE -> {
                Timber.tag(LOG_TAG).e("Write error code received, reconnecting...")
                reconnectToDevice()
            }
            else -> {
                Timber.tag(LOG_TAG).w("Write returned: $writeResult, retrying...")
                // Re-enter via writePackage so retry delay and retry cap are enforced.
                writePackage(bluetoothGattCharacteristic, bittelPackage, count + 1, randomID)
            }
        }
    }

    /**
     * Legacy (Android < 13) write path using deprecated but stable API.
     */
    @SuppressLint("MissingPermission", "Deprecation")
    private fun performWriteLegacy(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count: Int,
        randomID: String
    ) {
        bluetoothGattCharacteristic.value = bittelPackage.getStardustPackageToSend(context)
        val writeSuccess = gattConnection?.writeCharacteristic(bluetoothGattCharacteristic) ?: false

        when {
            writeSuccess -> {
                Timber.tag(LOG_TAG).d("Write succeeded on attempt ${count + 1}")
                checkIfPackageDemandsAck(bittelPackage)
            }
            else -> {
                Timber.tag(LOG_TAG).w("Write failed, retrying... (attempt ${count + 1})")
                performWrite(bluetoothGattCharacteristic, bittelPackage, count + 1, randomID)
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

    /**
     * Sets up ACK tracking for packages that demand acknowledgment.
     * Initiates a timeout-based ACK system that removes the package from queue on success/failure.
     */
    private fun checkIfPackageDemandsAck(bittelPackage: StardustPackage) {
        if (!shouldDemandAck(bittelPackage)) return
        if (!isDemandAckEnabled(bittelPackage)) return

        createAndStartAckSystem(bittelPackage)
    }

    /**
     * Determines if a package type requires ACK based on opcode and control byte flags.
     */
    private fun shouldDemandAck(bittelPackage: StardustPackage): Boolean {
        val isTextMessage = bittelPackage.stardustOpCode == StardustPackageUtils.StardustOpCode.SEND_MESSAGE &&
            bittelPackage.stardustControlByte.stardustPackageType == StardustControlByte.StardustPackageType.DATA &&
            bittelPackage.stardustControlByte.stardustMessageType != StardustControlByte.StardustMessageType.SNIFFED

        val isLocationRequest = bittelPackage.stardustOpCode == StardustPackageUtils.StardustOpCode.REQUEST_LOCATION

        return isTextMessage || isLocationRequest
    }

    /**
     * Checks if the specific packet has the DEMAND_ACK flag set.
     */
    private fun isDemandAckEnabled(bittelPackage: StardustPackage): Boolean =
        bittelPackage.stardustControlByte.stardustAcknowledgeType == StardustControlByte.StardustAcknowledgeType.DEMAND_ACK

    /**
     * Creates an AckSystem for the package and adds it to the waiting queue.
     */
    private fun createAndStartAckSystem(bittelPackage: StardustPackage) {
        val ackSystem = AckSystem(bittelPackage, createAckCallback())
        ackSystem.delayTS = DELAY_TS_LR
        ackSystem.start()
        mutableAckAwaitingList.add(ackSystem)
        Timber.tag(LOG_TAG).d("ACK tracking started for opCode: ${bittelPackage.stardustOpCode}")
    }

    /**
     * Creates the callback handler for ACK success/failure.
     */
    private fun createAckCallback(): AckSystem.AckSystemNotify =
        object : AckSystem.AckSystemNotify {
            override fun onFailure() {
                removeFirstAckFromQueue("ACK timeout")
            }

            override fun onSuccess() {
                if (mutableAckAwaitingList.isNotEmpty()) {
                    val ackSystem = mutableAckAwaitingList.removeAt(0)
                    syncMessageReceivedStatus(ackSystem)
                    Timber.tag(LOG_TAG).d("ACK received and processed")
                } else {
                    Timber.tag(LOG_TAG).w("ACK received but no pending ACK in queue")
                }
            }
        }

    /**
     * Safely removes the first ACK from the queue, logging any issues.
     */
    private fun removeFirstAckFromQueue(reason: String) {
        if (mutableAckAwaitingList.isNotEmpty()) {
            mutableAckAwaitingList.removeAt(0)
            Timber.tag(LOG_TAG).d("ACK removed from queue - reason: $reason")
        } else {
            Timber.tag(LOG_TAG).w("Attempted to remove ACK but queue is empty - reason: $reason")
        }
    }

    fun syncMessageReceivedStatus(message: AckSystem) {
        val msgId = message.stardustPackage.idNumber ?: return
        CoroutineScope(Dispatchers.IO).launch {
            DataManager.getAppRepo(context).updateMessageReceived(msgId)
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

    private fun resetBondTimer() {
        bondHandler.removeCallbacks(bondRunnable)
        bondHandler.removeCallbacksAndMessages(null)
        bondHandler.postDelayed(bondRunnable, bondTimeout)
    }

    fun removeBondTimer() {
        try {
            bondHandler.removeCallbacks(bondRunnable)
            bondHandler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    private fun resetConnectionTimer() {
        connectionHandler.removeCallbacks(connectionRunnable)
        connectionHandler.removeCallbacksAndMessages(null)
        connectionHandler.postDelayed(connectionRunnable, connectionTimeout)
    }

    fun removeConnectionTimer() {
        try {
            connectionHandler.removeCallbacks(connectionRunnable)
            connectionHandler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
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

    private fun resetPingTimer() {
        pingHandler.removeCallbacks(pingRunnable)
        pingHandler.removeCallbacksAndMessages(null)
        pingHandler.postDelayed(pingRunnable, pingTimeout)
    }

    private fun removePingTimer() {
        try {
            pingHandler.removeCallbacks(pingRunnable)
            pingHandler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
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


    fun reconnectToDevice () {
        disconnectFromBLEDevice (true)
        Handler(Looper.myLooper()!!).postDelayed({
            mDevice?.let { connectDevice(it) }
        },2000)
    }

    fun reconnectToDeviceFast () {
        disconnectFromBLEDevice ()
        Handler(Looper.myLooper()!!).postDelayed({
            mDevice?.let { connectDevice(it) }
        },100)
    }

    override fun updateBlePort() {
        RegisteredUserUtils.mRegisterUser.value?.let {
            val src = it.appId
            val dst = it.deviceId
            if(src != null && dst != null) {
                val uartPort = (StardustConfigurationParser.PortType.BLUETOOTH_ENABLED_BLE.type).intToByteArray().reversedArray()
                val data = StardustPackageUtils.byteArrayToIntArray(uartPort)
                val txPackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src ,
                    destination = dst,
                    stardustOpCode =StardustPackageUtils.StardustOpCode.UPDATE_UART_PORT,
                    data = data)
                addMessageToQueue(txPackage)
            }
        }
    }

    override fun saveConfiguration() {
        val user = RegisteredUserUtils.mRegisterUser.value ?: return
        val src = user.appId  ?: return
        val dst = user.deviceId  ?: return
        val configurationSavePackage = StardustPackageUtils.getStardustPackage(
            context = context,
            source = src ,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SAVE_CONFIGURATION)
        addMessageToQueue(configurationSavePackage)
    }
}



