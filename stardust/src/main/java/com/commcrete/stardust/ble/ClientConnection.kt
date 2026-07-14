package com.commcrete.stardust.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import android.content.BroadcastReceiver
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Context.RECEIVER_EXPORTED
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.commcrete.stardust.ble.BleManager.isUSBConnected
import com.commcrete.stardust.stardust.AckSystem
import com.commcrete.stardust.stardust.AckSystem.Companion.DELAY_TS_LR
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.isDisconnected
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nordicsemi.andorid.ble.test.spec.Characteristics
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import no.nordicsemi.android.ble.BleManager as NordicBleManager

internal class ClientConnection(): NordicBleManager(DataManager.appContext), BittelProtocol {

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
        get() {
            if(field == null) {
                val savedAddress = SharedPreferencesUtil.getBittelDevice()
                if(savedAddress != null && savedAddress.isNotBlank() && !savedAddress.equals("empty", ignoreCase = true)) {
                    field = getBleConnectedStardustDeviceBySavedAddress(savedAddress)
                }
            }
            return field
        }
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
        if(!StardustInitConnectionHandler.isConnected()) reconnectToDevice()
    }

    private val pingRunnable : Runnable = kotlinx.coroutines.Runnable {
        sendPing ()
        resetPingTimer()
    }
    private val pingHandler : Handler = Handler(Looper.getMainLooper())

    fun sendPing () {
        val (src, dst) = requireLocalSrcDst() ?: return

        val versionPackage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.PING)
        addMessageToQueue(versionPackage)
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

    val readRssiRunnable = Runnable @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) {
        gattConnection?.readRemoteRssi()
        resetRSSITimer()
    }



    var uuid : UUID? = null


    var hasCallback = false
    var deviceName : String?  = ""
    private val servicesDiscoveredHandled = AtomicBoolean(false)
    private val initStartTriggered = AtomicBoolean(false)
    private var bluetoothStateObserver: Observer<Boolean>? = null
    private val bluetoothStateObserverLock = Any()
    private val bleStatusHandler = Handler(Looper.getMainLooper())
    private val bleStatusRegistrationScheduled = AtomicBoolean(false)
    private var initStartJob: Job? = null
    private var discoverServicesJob: Job? = null
    private var reconnectJob: Job? = null

    val mapHRLR : MutableMap<String, Boolean> = mutableMapOf()

    init {
        initBleStatus()
    }

    private fun gettCallback () : BluetoothGattCallback{
        Log.d("StardustDataManager", " gettCallback")

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

                    Log.d("StardustDataManager", " onConnectionStateChange")
                    Timber.tag(LOG_TAG).d("status : $status\nnewState : $newState")
                    val mtu = gatt?.requestMtu(200)
                    Timber.tag("SetMtu").d("$mtu")
                    if(status == 0 && newState == 2){
                        discoverServicesJob?.cancel()
                        discoverServicesJob = Scopes.getDefaultCoroutine().launch {
                            delay(2000)
                            gatt?.discoverServices()
                        }
                    } else {
                        resetDiscoveryState()
                        Scopes.getMainCoroutine().launch {
                            Timber.tag("Bittel Disconnected").d("Status Changed")
                            Timber.tag(LOG_TAG).d("Bittel Disconnected")

                            Log.d("StardustDataManager", "BleManager.isBleConnected = false")
                            BleManager.isBleConnected = false
                            BleManager.bleConnectionStatus.value = false
                            BleManager.updateStatus()
                        }
                    }
                }

                @SuppressLint("MissingPermission")
                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    super.onServicesDiscovered(gatt, status)
                    Log.d("StardustDataManager", " onServicesDiscovered")
                    if (!isServicesDiscoveredHandleable(status)) return

                    Log.d("StardustDataManager", " isServicesDiscoveredHandleable")
                    Scopes.getDefaultCoroutine().launch {
                        handleServicesDiscovered(gatt)
                    }
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
                    Timber.tag(LOG_TAG).d("onCharacteristicChanged id=$randomID")
//                    Timber.tag("onCharacteristicChanged").d("onCharacteristicChanged2")
                    characteristic.value?.let {
//                        Timber.tag("onCharacteristicChanged").d("without Value")
                        StardustPackageUtils.handlePackageReceived(it, randomID)
                        clearTimer()
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    super.onCharacteristicChanged(gatt, characteristic, value)
                    Timber.tag(LOG_TAG).d("onCharacteristicChanged (value overload)")
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
                            if(StardustInitConnectionHandler.isConnected() || rssi >= 0) DataManager.getCallbacks()?.onDeviceConnectionRSSIChanged(rssi)
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

    // ── onServicesDiscovered helpers ─────────────────────────────────────

    /** Resolves device identity (last-digit, UUID, name, address) from [mDevice]. */
    @SuppressLint("MissingPermission")
    private fun setDevice() {
        mDevice?.name?.let {
            deviceLastDigit = it.takeLast(2)
            uuid = Characteristics.getWriteChar(deviceLastDigit)
        }
        deviceName?.let { name ->
            val resolvedName = name.ifEmpty {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) mDevice?.alias else mDevice?.name
            }
            resolvedName?.let { SharedPreferencesUtil.setBittelDeviceName(it) }
        }
        mDevice?.address?.let { SharedPreferencesUtil.setBittelDevice(it) }
    }

    /**
     * Guards re-entrancy and GATT_SUCCESS before the real discovery work begins.
     * Returns false if the event should be silently ignored.
     */
    private fun isServicesDiscoveredHandleable(status: Int): Boolean {
        if (StardustInitConnectionHandler.isConnectedSuccessfully() || StardustInitConnectionHandler.isSyncing()) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.tag(LOG_TAG).w("onServicesDiscovered failed with status=$status")
            return false
        }
        if (!servicesDiscoveredHandled.compareAndSet(false, true)) {
            Timber.tag(LOG_TAG).d("onServicesDiscovered ignored (already handled)")
            return false
        }
        return true
    }

    /**
     * Main body of services-discovered handling, run on the IO coroutine.
     * Broken into named steps so each concern is independently readable/testable.
     */
    @SuppressLint("MissingPermission")
    private fun handleServicesDiscovered(gatt: BluetoothGatt?) {
        setDevice()
        updateConnectionState(gatt)
        enableNotifications(gatt)
        if(bluetoothStateObserver == null) initBleStatus()
        Log.d("StardustDataManager", "isDisconnected() ${isDisconnected() }")
        if(isDisconnected() || StardustInitConnectionHandler.isSearchingToConnect()) triggerInitSequence(gatt)
    }

    /** Updates BLE connection state and RSSI polling when the device is paired. */
    private fun updateConnectionState(gatt: BluetoothGatt?) {
        Log.d("StardustDataManager", "BleManager.isPaired.value ${BleManager.isPaired.value}")
        if (BleManager.isPaired.value != true) return
        Scopes.getMainCoroutine().launch {
            Timber.tag(LOG_TAG).d("gattConnection")
            gattConnection = gatt
            BleManager.isBleConnected = true
            BleManager.bleConnectionStatus.value = true
            BleManager.updateStatus()
            resetRSSITimer()
        }
    }

    /** Requests high-priority connection and enables notifications on the read characteristic. */
    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt?) {
        gatt?.requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH)

        val id = deviceLastDigit
        val readChar = gatt
            ?.getService(Characteristics.getConnectChar(id))
            ?.getCharacteristic(Characteristics.getReadChar(id))
            ?: return

        Timber.tag(LOG_TAG).d("has Char")
        gatt.setCharacteristicNotification(readChar, true)
        val desc = readChar.descriptors?.get(0) ?: return
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }

    /**
     * Transitions state to SEARCHING, then schedules the init-start job which
     * starts [StardustInitConnectionHandler] once the device is confirmed paired.
     */
    @SuppressLint("MissingPermission")
    private fun triggerInitSequence(gatt: BluetoothGatt?) {
        Log.d("StardustDataManager", "onServicesDiscovered")
        StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)

        initStartJob?.cancel()
        initStartJob = Scopes.getDefaultCoroutine().launch {
            Log.d("StardustDataManager", "initStartJob")
            delay(500)

            if (!canStartInit()) return@launch

            Log.d("StardustDataManager", "canStartInit")
            StardustInitConnectionHandler.listener = object : StardustInitConnectionHandler.InitConnectionListener {}
            StardustInitConnectionHandler.start()

            Log.d("StardustDataManager", "after StardustInitConnectionHandler.start()")
            resetConnectionTimer()
            resetPingTimer()
        }
    }

    /** Returns true only when all preconditions for starting the init flow are met. */
    private fun canStartInit(): Boolean =
        RegisteredUserUtils.currentUserFlow.value?.appId != null
            && getBlePairedStardustDevice() != null
            && StardustInitConnectionHandler.isSearchingToConnect()
            && initStartTriggered.compareAndSet(false, true)

    // ─────────────────────────────────────────────────────────────────────

    fun initBleStatus() {
        if (!bleStatusRegistrationScheduled.compareAndSet(false, true)) return
        bleStatusHandler.post {
            try {
                registerBluetoothStateObserverIfNeeded()
            } finally {
                bleStatusRegistrationScheduled.set(false)
            }
        }
    }

    private fun registerBluetoothStateObserverIfNeeded() {
        synchronized(bluetoothStateObserverLock) {
            if (bluetoothStateObserver != null) return

            val observer = object : Observer<Boolean> {
                override fun onChanged(value: Boolean) {
                    Log.d("StardustDataManager", "Bluetooth state changed: $value, isUSBConnected: $isUSBConnected")
                    if (isUSBConnected) {
                        removeBluetoothStateObserver()
                        return
                    }

                    if(!value) {
                        if(isDisconnected()) return
                        disconnectFromBLEDevice(disconnectByForce = true, false)
                        StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.BLUETOOTH_OFF)
                    } else if(!isUSBConnected && !BleManager.isBleConnected) {
                        mDevice?.let {
                            Log.d("StardustDataManager", "hasCallback $hasCallback, isUSBConnected: $isUSBConnected")

                            if (hasCallback) { return@let }
                            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)
                            bondToBleDeviceStartup(it)
                        }
                    }
                }
            }

            bluetoothStateObserver = observer
            BluetoothStateManager.bluetoothState.observeForever(observer)
        }
    }

    private fun removeBluetoothStateObserver() {
        synchronized(bluetoothStateObserverLock) {
            bluetoothStateObserver?.let { BluetoothStateManager.bluetoothState.removeObserver(it) }
            bluetoothStateObserver = null
        }
    }

    override fun log(priority: Int, message: String) {
        when (priority) {
            2 -> Timber.tag(TAG).v(message)
            3 -> Timber.tag(TAG).d(message)
            4 -> Timber.tag(TAG).i(message)
            5 -> Timber.tag(TAG).w(message)
            6 -> Timber.tag(TAG).e(message)
            7 -> Timber.tag(TAG).wtf(message)
            else -> Timber.tag(TAG).d(message)
        }
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
        resetDiscoveryState()
        characteristic = null
        indicationCharacteristics = null
        reliableCharacteristics = null
        readCharacteristics = null
    }


    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        Log.d("StardustDataManager", "connectDevice: ${device.address}, hasCallback: $hasCallback")
        if(!hasCallback) {
            resetDiscoveryState()
            device.connectGatt(context, true, getBleGattCallback(device))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectFromBLEDevice(disconnectByForce: Boolean = false, withStateUpdate: Boolean = true) {
        if(!disconnectByForce && !StardustInitConnectionHandler.isConnected()) return
        reconnectJob?.cancel()
        reconnectJob = null
        resetDiscoveryState()
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
        if(withStateUpdate) StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
    }


    private fun getBleGattCallback(device: BluetoothDevice): BluetoothGattCallback {
        mDevice = device
        hasCallback = true
        return gettCallback()
    }

    private fun resetDiscoveryState() {
        servicesDiscoveredHandled.set(false)
        initStartTriggered.set(false)
        discoverServicesJob?.cancel()
        discoverServicesJob = null
        initStartJob?.cancel()
        initStartJob = null
    }

    /**
     * Checks if Bluetooth is enabled. If not, shows a dialog prompting the user to enable it.
     * The dialog offers two options:
     * 1. Enable Bluetooth - redirects to system Bluetooth enable request
     * 2. Go to Settings - redirects to Bluetooth settings
     *
     * @return true if Bluetooth is already enabled, false if user needs to enable it
     */
    fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            Timber.tag(LOG_TAG).e("Bluetooth is not supported on this device")
            return false
        }

        return bluetoothAdapter.isEnabled
    }

    fun release() {
        removeBluetoothStateObserver()
        cancelQueue()
        disconnect().enqueue()
    }

    @SuppressLint("MissingPermission")
    fun bondToBleDevice(device: BluetoothDevice, deviceName : String?) {
        this.deviceName = deviceName
        Scopes.getDefaultCoroutine().launch {
            val connectedDevice = device.name?.let { getBleConnectedDevice(device.address) }
            if(connectedDevice != null) {
                Scopes.getMainCoroutine().launch {
                    BleManager.isPaired.value = true
                    connectDevice(device)
                }
                return@launch
            }

            Scopes.getMainCoroutine().launch {
                resetBondTimer()
                try {
                    registerBondStateReceiver()
                    Timber.tag(LOG_TAG).d("bondToBleDevice")
                    device.connectGatt(context, false, object : BluetoothGattCallback() {})
                } catch (e: Exception) {
                    Timber.tag(LOG_TAG).e(e, "Failed to start bond flow")
                }
            }
        }
    }

    private fun registerBondStateReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(
                broadcastReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                RECEIVER_EXPORTED
            )
        } else {
            context.applicationContext.registerReceiver(
                broadcastReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun bondToBleDeviceStartup(connectedDevice: BluetoothDevice) {
        Log.d("StardustDataManager", "bondToBleDeviceStartup")

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
                    Timber.tag(LOG_TAG).w("${device?.address} bond state changed | $bondTransition")
                    if(bondState == BluetoothDevice.BOND_BONDED && previousBondState == BluetoothDevice.BOND_BONDING) {
                        //device?.address?.let { SharedPreferencesUtil.setBittelDevice(context, it) }
                        //device?.name?.let { SharedPreferencesUtil.setBittelDeviceName(context, it) }
                        device?.let {
                            Scopes.getDefaultCoroutine().launch {
                                Scopes.getMainCoroutine().launch {
                                    BleManager.isPaired.value = true
                                }
                                connectDevice(device)
                                removeBondTimer()
                            }
                        }
                    } else if(bondState == BluetoothDevice.BOND_BONDING && previousBondState == BluetoothDevice.BOND_NONE) {
                        disconnectFromBLEDevice(withStateUpdate = false)
                    } else{
                        device?.let {
                            requestUnbond(it)
                        }
                        disconnectFromBLEDevice(withStateUpdate = false)
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

    fun removeBittelBond() {
        val deviceToUnbond = mDevice
            ?: SharedPreferencesUtil.getBittelDevice()
                ?.takeIf { it.isNotBlank() && !it.equals("empty", ignoreCase = true) }
                ?.let { getBleConnectedStardustDeviceBySavedAddress(it) }

        deviceToUnbond?.let {
            val started = requestUnbond(it)
            Timber.tag(LOG_TAG).d("Unbond requested for ${it.address}: started=$started")
        }
        disconnectFromBLEDevice(true)

        Scopes.getDefaultCoroutine().launch {
            SharedPreferencesUtil.removeBittelDevice()
            SharedPreferencesUtil.removeBittelDeviceName()
        }

        mDevice = null
        Scopes.getMainCoroutine().launch {
            BleManager.isPaired.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun getBleConnectedDevice(uuid : String) : BluetoothDevice?{
        for (device in getBondedDevices()) {
            logPairedDevice(device)
            if (device.address == uuid) {
                return device
            }
        }
        return null
    }
    @SuppressLint("MissingPermission")
    fun getBleConnectedStardustDeviceBySavedAddress(savedAddress : String) : BluetoothDevice?{
        for (device in getBondedDevices()) {
            if(device.address == savedAddress) {
                return device
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun getBleConnectedStardustDevice() : BluetoothDevice? {
        val savedAddress = SharedPreferencesUtil.getBittelDevice()

        for (device in getBondedDevices()) {
            if(savedAddress == device.address) {
                return device
            }

            val aliasing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.alias?.lowercase(Locale.getDefault())
            } else {
                null
            }

            logPairedDevice(device)
            aliasing?.let {
                if(listOf("bittle", "bittel", "stardust").any { aliasing.contains(it) }) {
                    return device
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun getBlePairedStardustDevice() : BluetoothDevice? {
        val savedAddress = SharedPreferencesUtil.getBittelDevice()
        Log.d("StardustDataManager", "DataManager.context ${DataManager.appContext} savedAddress $savedAddress")

        if(savedAddress.isNullOrBlank()) { return null }

        for (device in getBondedDevices()) {

            Log.d("StardustDataManager", "device.address ${device.address}")
            if(savedAddress == device.address) {
                return device
            }
        }
        return null
    }
    @SuppressLint("MissingPermission")
    fun getBleConnectedDevices(uuid : String) : BluetoothDevice?{
        for (device in getBondedDevices()) {
            val aliasing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.alias
            } else {
                "Empty"
            }

            logPairedDevice(device)
            if(device.address == uuid
                || aliasing?.lowercase(Locale.getDefault())?.contains("bittle") == true
                || aliasing?.lowercase(Locale.getDefault())?.contains("bittel") == true
                || aliasing?.lowercase(Locale.getDefault())?.contains("stardust") == true){
                return device
            }
        }
        SharedPreferencesUtil.setBittelDevice("empty")
        SharedPreferencesUtil.setBittelDeviceName("empty")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun getBondedDevices(): Set<BluetoothDevice> {
        val btManager = context.getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptySet()
        return btManager.adapter?.bondedDevices ?: emptySet()
    }

    @SuppressLint("MissingPermission")
    private fun logPairedDevice(device: BluetoothDevice) {
        val aliasing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else "Empty"
        Timber.tag(LOG_TAG).d("paired device: ${device.name} at ${device.address} + $aliasing")
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
            Scopes.getDefaultCoroutine().launch {
                delay(100)
                sendMessage(bittelPackage, randomID)
            }
            return
        }
        bittelPackage.stardustControlByte.stardustServer = StardustControlByte.StardustServer.NOT_SERVER
//        bittelPackage.StardustControlByte.bittelServer = if(SharedPreferencesUtil.getIsStardustServerBitEnabled(DataManager.context))
//            StardustControlByte.StardustServer.SERVER else StardustControlByte.StardustServer.NOT_SERVER
        Timber.tag(LOG_TAG).d("checkXor $randomID sendMessage")
        bittelPackage.checkXor = StardustPackageUtils.getCheckXor(bittelPackage.getStardustPackageToCheckXor())
        Timber.tag(LOG_TAG).d("checkXorfini $randomID sendMessage")
        if(bittelPackage.isAbleToSendAgain()){
            if(!BleManager.isBluetoothConnected() && !BleManager.isUSBConnected){
                Timber.tag(LOG_TAG).d("Bluetooth not available, either settings or disconnected")
            }
            Timber.tag(LOG_TAG).d("isAbleToSendAgain $randomID sendMessage")

            Timber.tag(LOG_TAG).d("Sending Package $randomID")
            if (isNeedAck(bittelPackage.stardustOpCode)) {
                // The watchdog's only cancellation path is clearTimer(), fired from an
                // incoming BLE response. Opcodes that never get one (PTT_AI) would have
                // this fire unconditionally 15ms after every send, and — if system load
                // delays the synchronous write+dequeue below past that window — resend
                // whatever is still at mutableMessageList[0], duplicating that packet.
                Scopes.getDefaultCoroutine().launch {
                    resetTimer(bittelPackage)
                }
            }
            SharedPreferencesUtil.getAppUser()?.let {
                Timber.tag(LOG_TAG).d("getAppUser $randomID sendMessage")

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
        Scopes.getDefaultCoroutine().launch {
            delay(RETRY_DELAY_MS)
            performWrite(bluetoothGattCharacteristic, bittelPackage, count, randomID)
        }
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
            bittelPackage.getStardustPackageToSend(),
            WRITE_TYPE_DEFAULT
        )

        when (writeResult) {
            null -> {
                Timber.tag(LOG_TAG).e("gattConnection is null, cannot write")
            }
            0 -> {
                Timber.tag(LOG_TAG).d("Write succeeded on attempt ${count + 1}")
                checkIfPackageDemandsAck(bittelPackage)
            }
            WRITE_ERROR_CODE -> {
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
        bluetoothGattCharacteristic.value = bittelPackage.getStardustPackageToSend()
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
            DataManager.getAppRepo().updateMessageReceived(msgId)
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
        disconnectFromBLEDevice(disconnectByForce = true, withStateUpdate = false)
        reconnectJob?.cancel()
        reconnectJob = Scopes.getDefaultCoroutine().launch {
            delay(2000)
            mDevice?.let { connectDevice(it) }
        }
    }

    fun reconnectToDeviceFast() {
        disconnectFromBLEDevice(disconnectByForce = true, withStateUpdate = false)
        reconnectJob?.cancel()
        reconnectJob = Scopes.getDefaultCoroutine().launch {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)
            delay(100)
            mDevice?.let { connectDevice(it) }
        }
    }

    override fun updateBlePort() {
        val (src, dst) = requireLocalSrcDst() ?: return

        val uartPort = (StardustConfigurationParser.PortType.BLUETOOTH_ENABLED_BLE.type).intToByteArray().reversedArray()
        val data = StardustPackageUtils.byteArrayToIntArray(uartPort)
        val txPackage = StardustPackageUtils.getStardustPackage(
            source = src ,
            destination = dst,
            stardustOpCode =StardustPackageUtils.StardustOpCode.UPDATE_UART_PORT,
            data = data)
        addMessageToQueue(txPackage)
    }

    override fun saveConfiguration() {
        val (src, dst) = requireLocalSrcDst() ?: return

        val configurationSavePackage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SAVE_CONFIGURATION)
        addMessageToQueue(configurationSavePackage)
    }
}





