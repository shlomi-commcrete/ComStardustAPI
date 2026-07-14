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
import com.commcrete.stardust.transport.ConnectionManager
import com.commcrete.stardust.transport.TransportId
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
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class ClientConnection(): BittelProtocol {

    companion object{
        const val LOG_TAG = "stardust_tag"
        const val MAX_WRITE_RETRIES = 3
        const val RETRY_DELAY_MS = 500L
        const val WRITE_ERROR_CODE = 2147483647

        // Safety valve: if a GATT op's completion callback never arrives, advance the queue anyway
        // so one lost callback can't wedge all subsequent writes.
        const val GATT_OP_TIMEOUT_MS = 5000L
    }
    private val TAG = ClientConnection::class.java.simpleName

    // Formerly provided by the Nordic BleManager base class. The connection is driven entirely by
    // the raw BluetoothGatt path below, so the Nordic FSM was dead weight; this replaces its only
    // still-used member.
    private val context: Context get() = DataManager.appContext

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
        if(!StardustInitConnectionHandler.isConnected()) {
            ConnectionManager.requestReconnect(TransportId.BLE, "BLE connection watchdog")
        }
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
    private val mtuRequested = AtomicBoolean(false)
    private var bluetoothStateObserver: Observer<Boolean>? = null
    private val bluetoothStateObserverLock = Any()
    private val bleStatusHandler = Handler(Looper.getMainLooper())
    private val bleStatusRegistrationScheduled = AtomicBoolean(false)
    private var initStartJob: Job? = null
    private var discoverServicesJob: Job? = null
    private var reconnectJob: Job? = null

    // Address we are actively bonding to. Lets the bond-state receiver recognise our target
    // before [mDevice] is assigned, and — combined with the receiver's address filter — stops
    // us from reacting to bond changes on unrelated Bluetooth devices (e.g. the user's headset).
    private var pendingBondAddress: String? = null

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
                    if(status == 0 && newState == 2){
                        // Request the larger MTU once, only now that we're actually connected —
                        // previously this fired on every state change (including disconnects/errors),
                        // where it is meaningless and just logs failures.
                        if (mtuRequested.compareAndSet(false, true)) {
                            val requested = gatt?.requestMtu(200)
                            Timber.tag("SetMtu").d("requestMtu(200) initiated=$requested")
                        }
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
                    // The previous write finished (success or error) — release the queue so the
                    // next GATT op can run. This is what makes the queue a real serial queue.
                    completeGattOp()
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
                    completeGattOp()
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
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag("SetMtu").d("MTU negotiated: $mtu")
                    } else {
                        Timber.tag("SetMtu").w("MTU change failed, status=$status")
                    }
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
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

        val id = deviceLastDigit
        val readChar = gatt
            ?.getService(Characteristics.getConnectChar(id))
            ?.getCharacteristic(Characteristics.getReadChar(id))
            ?: return

        Timber.tag(LOG_TAG).d("has Char")
        gatt.setCharacteristicNotification(readChar, true)
        val desc = readChar.descriptors?.get(0) ?: return
        // Serialised through the same GATT queue as data writes — a descriptor write and a
        // characteristic write are both single-outstanding GATT operations and must not overlap.
        enqueueGattOp(GattOp("descriptor:notif:${readChar.uuid}") {
            writeNotificationDescriptor(gatt, desc)
        })
    }

    /** Writes the CCCD enable-notification value, version-appropriately. Returns whether initiated. */
    @SuppressLint("MissingPermission")
    private fun writeNotificationDescriptor(gatt: BluetoothGatt, desc: BluetoothGattDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        } else {
            @Suppress("DEPRECATION")
            run {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }
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
        clearGattQueue()
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
        mtuRequested.set(false)
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
        disconnectFromBLEDevice(disconnectByForce = true)
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
                    pendingBondAddress = device.address
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

                    // Only act on OUR device. ACTION_BOND_STATE_CHANGED is a system-wide broadcast,
                    // so without this filter a bond change on ANY device (e.g. the user pairing a
                    // headset) would fall into the else-branch below and unbond/disconnect us.
                    val target = mDevice?.address ?: pendingBondAddress
                    val eventAddress = device?.address
                    if (target == null || eventAddress == null ||
                        !eventAddress.equals(target, ignoreCase = true)) {
                        Timber.tag(LOG_TAG).d("Ignoring bond change for $eventAddress (target=$target)")
                        return
                    }

                    if(bondState == BluetoothDevice.BOND_BONDED && previousBondState == BluetoothDevice.BOND_BONDING) {
                        //device?.address?.let { SharedPreferencesUtil.setBittelDevice(context, it) }
                        //device?.name?.let { SharedPreferencesUtil.setBittelDeviceName(context, it) }
                        pendingBondAddress = null
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
                        // Bond removed (e.g. BONDED->NONE) or the attempt failed for our device.
                        // Reconcile app pairing with the OS instead of forcing another unbond.
                        pendingBondAddress = null
                        disconnectFromBLEDevice(withStateUpdate = false)
                        PairingRepository.reconcile()
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

    enum class UnpairResult {
        /** OS unbond initiated (or forced); local pairing cleared. */
        UNBONDED,
        /** OS `removeBond()` failed and clearing was not forced; local pairing kept so app == OS. */
        STILL_BONDED_KEPT,
        /** Nothing was paired. */
        NOT_PAIRED,
    }

    /**
     * Removes the pairing to the current Bittel device.
     *
     * By default this is HONEST: the persisted address/name and [BleManager.isPaired] are only
     * cleared if the OS unbond actually starts. If the hidden `removeBond()` reflection call fails
     * (common on some OEMs) the local record is KEPT, so we never strand a device that is still
     * bonded in Android but erased from the app (which would leave it unpairable).
     *
     * @param forceClearLocal clear the local record regardless of unbond success. Used by the
     *   security-erase flow, where the app data must be destroyed no matter what; a leftover OS
     *   bond is then recoverable on next launch via [PairingRepository] adoption.
     */
    fun removeBittelBond(forceClearLocal: Boolean = false): UnpairResult {
        val deviceToUnbond = mDevice
            ?: SharedPreferencesUtil.getBittelDevice()
                ?.takeIf { it.isNotBlank() && !it.equals("empty", ignoreCase = true) }
                ?.let { getBleConnectedStardustDeviceBySavedAddress(it) }

        if (deviceToUnbond == null) {
            if (forceClearLocal) clearLocalPairing()
            disconnectFromBLEDevice(true)
            return UnpairResult.NOT_PAIRED
        }

        val unbondStarted = requestUnbond(deviceToUnbond)
        Timber.tag(LOG_TAG).d("Unbond for ${deviceToUnbond.address}: started=$unbondStarted force=$forceClearLocal")
        disconnectFromBLEDevice(true)

        return if (unbondStarted || forceClearLocal) {
            clearLocalPairing()
            UnpairResult.UNBONDED
        } else {
            Timber.tag(LOG_TAG).w("OS unbond failed for ${deviceToUnbond.address}; keeping local pairing so app and OS agree")
            UnpairResult.STILL_BONDED_KEPT
        }
    }

    private fun clearLocalPairing() {
        Scopes.getDefaultCoroutine().launch {
            SharedPreferencesUtil.removeBittelDevice()
            SharedPreferencesUtil.removeBittelDeviceName()
        }
        mDevice = null
        pendingBondAddress = null
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
        // NOTE: this used to write the sentinel "empty" into the saved device address/name here,
        // which erased a valid pairing on any lookup miss. Pairing lifecycle is now owned by
        // PairingRepository / removeBittelBond, so this query no longer mutates persisted state.
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

    // ── GATT operation queue (B2 fix) ────────────────────────────────────────
    // Android BLE allows only ONE outstanding GATT operation per connection; issuing a second
    // write before the previous one's callback fires makes the framework silently drop it. Every
    // characteristic write and the notification-descriptor write goes through this serial queue,
    // which starts the next op only when the prior op's completion callback (onCharacteristicWrite
    // / onDescriptorWrite) fires — or a timeout elapses as a safety valve.

    /** A single GATT operation. [execute] performs it and returns whether it was actually initiated
     *  (i.e. whether a completion callback should be expected). */
    private class GattOp(val label: String, val execute: () -> Boolean)

    private val gattOpQueue = ArrayDeque<GattOp>()
    private var gattOpInFlight = false
    private var gattOpExpectsCallback = false
    // A callback that arrives AFTER its op has been abandoned by the timeout would otherwise
    // complete the *next* op early (reintroducing overlap). We count such abandoned-but-initiated
    // ops and swallow that many subsequent completion callbacks.
    private var staleCallbacksToIgnore = 0
    private val gattOpHandler = Handler(Looper.getMainLooper())
    private var gattOpTimeoutRunnable: Runnable? = null

    @Synchronized
    private fun enqueueGattOp(op: GattOp) {
        gattOpQueue.addLast(op)
        pumpGattQueue()
    }

    @Synchronized
    private fun pumpGattQueue() {
        if (gattOpInFlight || gattOpQueue.isEmpty()) return
        val op = gattOpQueue.removeFirst()
        gattOpInFlight = true
        gattOpExpectsCallback = false

        val timeout = Runnable { onGattOpTimeout(op.label) }
        gattOpTimeoutRunnable = timeout
        gattOpHandler.postDelayed(timeout, GATT_OP_TIMEOUT_MS)

        val initiated = try {
            op.execute()
        } catch (e: Exception) {
            Timber.tag(LOG_TAG).e(e, "GATT op '${op.label}' threw")
            false
        }
        gattOpExpectsCallback = initiated
        // If the op never actually started, no completion callback will arrive — advance now.
        if (!initiated) advanceGattQueue()
    }

    /** Called from the real GATT completion callbacks (onCharacteristicWrite / onDescriptorWrite). */
    @Synchronized
    private fun completeGattOp() {
        if (staleCallbacksToIgnore > 0) {
            staleCallbacksToIgnore--
            return
        }
        if (gattOpInFlight) advanceGattQueue()
    }

    @Synchronized
    private fun onGattOpTimeout(label: String) {
        if (!gattOpInFlight) return
        Timber.tag(LOG_TAG).w("GATT op '$label' timed out; advancing queue")
        // The op was initiated, so its completion callback may still arrive late — ignore it.
        if (gattOpExpectsCallback) staleCallbacksToIgnore++
        advanceGattQueue()
    }

    private fun advanceGattQueue() {
        gattOpTimeoutRunnable?.let { gattOpHandler.removeCallbacks(it) }
        gattOpTimeoutRunnable = null
        gattOpInFlight = false
        gattOpExpectsCallback = false
        pumpGattQueue()
    }

    /** Drops all pending ops and clears in-flight state (used on disconnect). */
    @Synchronized
    private fun clearGattQueue() {
        gattOpTimeoutRunnable?.let { gattOpHandler.removeCallbacks(it) }
        gattOpTimeoutRunnable = null
        gattOpQueue.clear()
        gattOpInFlight = false
        gattOpExpectsCallback = false
        staleCallbacksToIgnore = 0
    }

    @SuppressLint("MissingPermission")
    private fun writePackage(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count: Int = 0,
        randomID: String = ""
    ) {
        if (count > MAX_WRITE_RETRIES) {
            Timber.tag(LOG_TAG).w("Max write retries exceeded for ${bittelPackage.stardustOpCode}")
            return
        }
        Timber.tag(LOG_TAG).d("writePackage enqueue attempt $count/${MAX_WRITE_RETRIES} - opCode: ${bittelPackage.stardustOpCode}")
        enqueueGattOp(GattOp("write:${bittelPackage.stardustOpCode}:$count") {
            performGattWrite(bluetoothGattCharacteristic, bittelPackage, count, randomID)
        })
    }

    /**
     * Performs a single characteristic write. Returns true if the write was initiated (a completion
     * callback is expected); false if it failed to start (queue advances immediately, and a retry
     * or reconnect is scheduled as appropriate).
     */
    @SuppressLint("MissingPermission")
    private fun performGattWrite(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count: Int,
        randomID: String
    ): Boolean {
        val gatt = gattConnection ?: run {
            Timber.tag(LOG_TAG).e("gattConnection is null, cannot write")
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (val writeResult = gatt.writeCharacteristic(
                bluetoothGattCharacteristic,
                bittelPackage.getStardustPackageToSend(),
                WRITE_TYPE_DEFAULT
            )) {
                0 -> {
                    Timber.tag(LOG_TAG).d("Write initiated on attempt ${count + 1}")
                    checkIfPackageDemandsAck(bittelPackage)
                    true
                }
                WRITE_ERROR_CODE -> {
                    Timber.tag(LOG_TAG).e("Write error code received, reconnecting...")
                    reconnectToDevice()
                    false
                }
                else -> {
                    Timber.tag(LOG_TAG).w("Write returned: $writeResult, scheduling retry...")
                    scheduleWriteRetry(bluetoothGattCharacteristic, bittelPackage, count, randomID)
                    false
                }
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                bluetoothGattCharacteristic.value = bittelPackage.getStardustPackageToSend()
                if (gatt.writeCharacteristic(bluetoothGattCharacteristic)) {
                    Timber.tag(LOG_TAG).d("Write initiated on attempt ${count + 1}")
                    checkIfPackageDemandsAck(bittelPackage)
                    true
                } else {
                    Timber.tag(LOG_TAG).w("Write failed, scheduling retry... (attempt ${count + 1})")
                    scheduleWriteRetry(bluetoothGattCharacteristic, bittelPackage, count, randomID)
                    false
                }
            }
        }
    }

    /** Re-enqueues the write after a delay, preserving the retry cap. */
    private fun scheduleWriteRetry(
        bluetoothGattCharacteristic: BluetoothGattCharacteristic,
        bittelPackage: StardustPackage,
        count: Int,
        randomID: String
    ) {
        Scopes.getDefaultCoroutine().launch {
            delay(RETRY_DELAY_MS)
            writePackage(bluetoothGattCharacteristic, bittelPackage, count + 1, randomID)
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





