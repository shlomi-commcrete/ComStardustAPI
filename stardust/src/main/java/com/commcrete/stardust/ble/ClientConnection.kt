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
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.commcrete.stardust.ble.BleManager.isUSBConnected
import com.commcrete.stardust.stardust.AckSystem
import com.commcrete.stardust.stardust.AckSystem.Companion.DELAY_TS_LR
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.isDisconnected
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.BleUnavailableReason
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.transport.ConnectionManager
import com.commcrete.stardust.transport.TransportId
import com.commcrete.stardust.transport.toBlocker
import com.commcrete.stardust.stardust.model.config.PortType
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

        /**
         * Settle delay before service discovery, applied once the MTU exchange has answered.
         * Discovering immediately after connecting to a BONDED device hits a known Android race on
         * some OEMs where discovery silently fails, so a short settle is still needed — but the old
         * flat 2000ms was waiting for the MTU callback by guesswork. Now the callback drives it and
         * this is only the settle.
         */
        const val SERVICE_DISCOVERY_SETTLE_MS = 250L

        /**
         * Fallback for the MTU callback never arriving (some stacks skip it when `requestMtu`
         * returns false). Deliberately shorter than the 2000ms it replaces: on the path where the
         * callback DOES arrive — the overwhelming majority — discovery now starts ~1.75s earlier.
         */
        const val SERVICE_DISCOVERY_MTU_TIMEOUT_MS = 1500L

        /**
         * How long a direct (`autoConnect = false`) attempt gets before falling back to Android's
         * background connection. A direct connect to a radio that is on and in range completes well
         * inside this; the fallback is for one that is off or out of range.
         */
        const val DIRECT_CONNECT_TIMEOUT_MS = 6000L

        /**
         * Backstop for starting the init handshake. The real trigger is the CCCD write completing
         * ([BluetoothGattCallback.onDescriptorWrite]) — notifications must be live before the first
         * request goes out or its reply is missed. This fires only if that callback never arrives.
         */
        const val INIT_START_FALLBACK_MS = 500L

        /**
         * How long a connect attempt is left alone before a reconnect may supersede it.
         *
         * A direct attempt that fails escalates to `autoConnect = true`, which Android serves from
         * its background connection list with no timeout of its own — it attaches whenever the radio
         * next advertises, which is exactly the recovery we want. Every `reconnectToDevice()` starts
         * with a force-disconnect, so without this the watchdog would destroy that pending connect
         * on each pass and restart from zero. The deadline is the escape hatch for an attempt that
         * is genuinely wedged.
         */
        const val CONNECT_ATTEMPT_STALE_MS = 60_000L
    }
    private val TAG = ClientConnection::class.java.simpleName

    // Formerly provided by the Nordic BleManager base class. The connection is driven entirely by
    // the raw BluetoothGatt path below, so the Nordic FSM was dead weight; this replaces its only
    // still-used member.
    private val context: Context get() = DataManager.appContext

    // Written on the BLE callback thread (onServicesDiscovered / disconnect), read on send-coroutine
    // threads — @Volatile so the send path always sees the current (or null-after-close) GATT.
    @Volatile
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

    // Both lists are plain ArrayLists touched from binder GATT callbacks (onCharacteristicChanged),
    // Dispatchers.Default/IO retry coroutines, the main-looper resend runnable, and arbitrary caller
    // threads. ArrayList is not thread-safe, so ALL access goes through this one lock and each
    // check-then-act (isNotEmpty -> removeAt(0)) is made atomic.
    private val queueLock = Any()

    private fun enqueueMessage(pkg: StardustPackage) = synchronized(queueLock) { mutableMessageList.add(pkg) }
    private fun peekFirstMessage(): StardustPackage? = synchronized(queueLock) { mutableMessageList.firstOrNull() }

    /**
     * Removes the SPECIFIC package [pkg] by object identity (not index 0). The inbound ACK carries
     * no message id (idNumber is a client-only DB tag, never sent to the radio) and the protocol
     * serialises to one outstanding ACK, so the object reference is the correlation key: each site
     * removes exactly the package it sent/acked. This stops clearTimer — which fires on EVERY
     * inbound notification — from dropping a different, queued-but-unsent package (the lost-packet
     * bug of a blind removeAt(0)). A double-remove of the same package is a harmless no-op.
     */
    private fun removeMessage(pkg: StardustPackage?) {
        pkg ?: return
        synchronized(queueLock) {
            val i = mutableMessageList.indexOfFirst { it === pkg }
            if (i >= 0) mutableMessageList.removeAt(i)
        }
    }

    private fun addAwaitingAck(ack: AckSystem) = synchronized(queueLock) { mutableAckAwaitingList.add(ack) }
    private fun isAckAwaiting(): Boolean = synchronized(queueLock) { mutableAckAwaitingList.isNotEmpty() }
    private fun removeFirstAwaitingAck(): AckSystem? = synchronized(queueLock) { if (mutableAckAwaitingList.isNotEmpty()) mutableAckAwaitingList.removeAt(0) else null }
    private fun firstAwaitingAck(): AckSystem? = synchronized(queueLock) { mutableAckAwaitingList.firstOrNull() }


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

    fun sendPing() {
        if(!StardustInitConnectionHandler.isConnected()) return
        val (src, dst) = requireLocalSrcDst() ?: return

        val versionPackage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.PING)
        addMessageToQueue(versionPackage)
    }

    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        peekFirstMessage()?.let { sendMessage(it) }
    }

    var lastPlayedTS : Long = 0

    private val bondRunnable : Runnable = kotlinx.coroutines.Runnable {

        // TODO: show fail
    }
    private val bondHandler : Handler = Handler(Looper.getMainLooper())

    val handlerRSSI = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    val readRssiRunnable = Runnable {
        if (BlePermissions.hasConnectPermission(context)) {
            gattConnection?.readRemoteRssi()
        }
        resetRSSITimer()
    }



    var uuid : UUID? = null


    var hasCallback = false

    // Guards connectGatt so exactly ONE connection can be in flight/active at a time. Must be a
    // CAS, not a plain boolean check — see connectDevice() for the double-connection this prevents.
    private val connectInFlight = AtomicBoolean(false)
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

    /** Ensures exactly one `discoverServices()` per connection, whichever trigger gets there first. */
    private val discoveryRequested = AtomicBoolean(false)

    /**
     * The GATT client returned by `connectGatt`, held from the moment the connect is issued.
     * [gattConnection] is only assigned once services are discovered, so without this a connect
     * that never completes leaves an un-closeable client behind — and Android allows only a handful
     * per process before `connectGatt` starts failing outright.
     */
    private var pendingGatt: BluetoothGatt? = null

    /** True while a direct attempt is running that should fall back to a background connect. */
    private val directConnectPending = AtomicBoolean(false)
    private var patientConnectJob: Job? = null

    /** elapsedRealtime of the last `connectGatt`; ages the attempt for [CONNECT_ATTEMPT_STALE_MS]. */
    @Volatile
    private var connectStartedAtMs = 0L

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
                    Log.d("ConfigDebug", "onConnectionStateChange status=$status newState=$newState hasCallback=$hasCallback mDevice=${mDevice?.address}")
                    Timber.tag(LOG_TAG).d("status : $status\nnewState : $newState")
                    if(status == 0 && newState == 2){
                        // If the user disconnected (or logged out) while this reconnect's connectGatt
                        // was in flight, disconnectFromBLEDevice already cleared hasCallback. The
                        // connection is unwanted, so tear it down instead of resurrecting the link.
                        if (!hasCallback || !RegisteredUserUtils.isUserLoggedIn()) {
                            Timber.tag(LOG_TAG).d("Connected but no active intent (user disconnected) — closing stray GATT")
                            gatt?.disconnect()
                            gatt?.close()
                            connectInFlight.set(false)
                            return
                        }
                        // Request the larger MTU once, only now that we're actually connected —
                        // previously this fired on every state change (including disconnects/errors),
                        // where it is meaningless and just logs failures.
                        // The link is up, so the direct attempt succeeded — cancel the fallback to
                        // a background connect before it can tear this connection down.
                        directConnectPending.set(false)
                        patientConnectJob?.cancel()
                        patientConnectJob = null

                        if (mtuRequested.compareAndSet(false, true)) {
                            val requested = gatt?.requestMtu(200)
                            Timber.tag("SetMtu").d("requestMtu(200) initiated=$requested")
                        }
                        // Normally superseded by onMtuChanged, which schedules discovery as soon as
                        // the exchange answers instead of waiting out a fixed delay.
                        scheduleServiceDiscovery(gatt, SERVICE_DISCOVERY_MTU_TIMEOUT_MS, "MTU callback timeout")
                    } else {
                        resetDiscoveryState()
                        // The link is down. Release the connect gate so a later reconnect can run —
                        // a drop that doesn't route through disconnectFromBLEDevice would otherwise
                        // leave the CAS latched and block every future connectGatt.
                        connectInFlight.set(false)
                        // A direct attempt that failed (typically status 133) escalates immediately
                        // rather than waiting out DIRECT_CONNECT_TIMEOUT_MS — the radio is off or out
                        // of range, which is exactly what the background connect is for. Must run
                        // AFTER the gate is released, or the escalated connectGatt is refused by it.
                        if (directConnectPending.get()) {
                            mDevice?.let { escalateToBackgroundConnect(it, "direct connect failed (status=$status)") }
                        }
                        Scopes.getMainCoroutine().launch {
                            Timber.tag("Bittel Disconnected").d("Status Changed")
                            Timber.tag(LOG_TAG).d("Bittel Disconnected")

                            Log.d("StardustDataManager", "BleManager.isBleConnected = false")
                            BleManager.isBleConnected = false
                            BleManager.bleConnectionStatus.value = false
                            // BEFORE updateStatus, which publishes the new state: the flags above
                            // already make the registry report no active transport, so this can open
                            // the grace window first and updateStatus then derives Searching. Called
                            // after, the host would still see one Disconnected emission — the exact
                            // flash this is meant to remove. It also asks for the reconnect now,
                            // instead of waiting out the next watchdog tick.
                            ConnectionManager.onLinkLost(TransportId.BLE)
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
                    characteristic.value?.let {
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
                    Log.d("ConfigDebug", "onDescriptorWrite status=$status characteristic=${descriptor?.characteristic?.uuid} — notifications ${if (status == BluetoothGatt.GATT_SUCCESS) "ENABLED" else "FAILED"}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag("NotificationSetup").d("Notification successfully enabled for ${descriptor?.characteristic?.uuid}")
                        // Notifications are live, which is the only thing the init flow was waiting
                        // for — start now instead of sitting out the fallback delay.
                        Scopes.getDefaultCoroutine().launch { startInitIfReady("CCCD write complete") }
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
                    // The exchange is done either way — discovery only had to wait for it to stop
                    // competing, not to succeed.
                    scheduleServiceDiscovery(gatt, SERVICE_DISCOVERY_SETTLE_MS, "MTU exchange answered")
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
        // A disconnect (or logout) after discoverServices() was already issued clears hasCallback —
        // don't proceed to init on a connection the user no longer wants.
        if (!hasCallback || !RegisteredUserUtils.isUserLoggedIn()) {
            Timber.tag(LOG_TAG).d("onServicesDiscovered ignored (no active connect intent)")
            return false
        }
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
        Log.d("ConfigDebug", "handleServicesDiscovered ENTER — services=${gatt?.services?.size ?: 0} lastDigit=$deviceLastDigit isPaired=${BleManager.isPaired.value}")
        setDevice()
        updateConnectionState(gatt)
        enableNotifications(gatt)
        if(bluetoothStateObserver == null) initBleStatus()
        Log.d("StardustDataManager", "isDisconnected() ${isDisconnected() }")
        val shouldTrigger = isDisconnected() || StardustInitConnectionHandler.isSearchingToConnect()
        Log.d("ConfigDebug", "handleServicesDiscovered done — willTriggerInit=$shouldTrigger isDisconnected=${isDisconnected()} isSearching=${StardustInitConnectionHandler.isSearchingToConnect()}")
        if(shouldTrigger) triggerInitSequence(gatt)
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
        val service = gatt?.getService(Characteristics.getConnectChar(id))
        val readChar = service?.getCharacteristic(Characteristics.getReadChar(id))
        Log.d("ConfigDebug",
            "enableNotifications lastDigit=$id serviceFound=${service != null} readCharFound=${readChar != null} " +
                "descriptorCount=${readChar?.descriptors?.size ?: 0}"
        )
        if (readChar == null) {
            Log.w("ConfigDebug", "enableNotifications ABORT — read characteristic not found")
            return
        }

        Timber.tag(LOG_TAG).d("has Char")
        val notifSet = gatt.setCharacteristicNotification(readChar, true)
        Log.d("ConfigDebug", "setCharacteristicNotification(true) returned=$notifSet")
        val desc = readChar.descriptors?.get(0)
        if (desc == null) {
            Log.w("ConfigDebug", "enableNotifications ABORT — CCCD descriptor missing")
            return
        }
        // Serialised through the same GATT queue as data writes — a descriptor write and a
        // characteristic write are both single-outstanding GATT operations and must not overlap.
        enqueueGattOp(GattOp("descriptor:notif:${readChar.uuid}") {
            val initiated = writeNotificationDescriptor(gatt, desc)
            Log.d("ConfigDebug", "CCCD writeDescriptor initiated=$initiated for ${readChar.uuid}")
            initiated
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
        Log.d("ConfigDebug", "triggerInitSequence — state SEARCHING; init starts on the CCCD write, " +
            "fallback in ${INIT_START_FALLBACK_MS}ms")
        StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)

        initStartJob?.cancel()
        initStartJob = Scopes.getDefaultCoroutine().launch {
            delay(INIT_START_FALLBACK_MS)
            startInitIfReady("fallback delay")
        }
    }

    /**
     * Starts the init handshake, at most once per connection. Both callers race deliberately — the
     * CCCD-write callback (fast path) and the fallback delay — and [canStartInit] arbitrates with a
     * CAS, so whichever arrives second is a no-op.
     */
    private fun startInitIfReady(source: String) {
        if (!canStartInit()) {
            Log.w("ConfigDebug", "init start SKIPPED ($source) — canStartInit() false (see prior canStartInit log)")
            return
        }
        initStartJob?.cancel()
        initStartJob = null

        Log.d("ConfigDebug", "init start ($source) → StardustInitConnectionHandler.start()")
        StardustInitConnectionHandler.listener = object : StardustInitConnectionHandler.InitConnectionListener {}
        StardustInitConnectionHandler.start()

        resetConnectionTimer()
        resetPingTimer()
    }

    /**
     * Issues `discoverServices()` once per connection, after [delayMs]. Callers race (MTU callback
     * vs. its timeout); [discoveryRequested] makes the loser a no-op.
     */
    @SuppressLint("MissingPermission")
    private fun scheduleServiceDiscovery(gatt: BluetoothGatt?, delayMs: Long, reason: String) {
        if (discoveryRequested.get()) return
        discoverServicesJob?.cancel()
        discoverServicesJob = Scopes.getDefaultCoroutine().launch {
            delay(delayMs)
            if (!discoveryRequested.compareAndSet(false, true)) return@launch
            val started = gatt?.discoverServices()
            Log.d("ConfigDebug", "discoverServices() initiated=$started after ${delayMs}ms ($reason)")
        }
    }

    /** Returns true only when all preconditions for starting the init flow are met. */
    private fun canStartInit(): Boolean {
        val user = RegisteredUserUtils.currentUserFlow.value
        val paired = getBlePairedStardustDevice()
        val searching = StardustInitConnectionHandler.isSearchingToConnect()
        val armed = initStartTriggered.get()
        Log.d("ConfigDebug",
            "canStartInit user.appId=${user?.appId} user.deviceId=${user?.deviceId} " +
                "paired=${paired?.address} isSearching=$searching alreadyTriggered=$armed"
        )
        return user?.appId != null
            && paired != null
            && searching
            && initStartTriggered.compareAndSet(false, true)
    }

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
                            // An intentional disconnect/unpair must not be undone from here.
                            // LiveData.setValue dispatches on EVERY ACTION_STATE_CHANGED broadcast,
                            // equal value or not, so without this any adapter-state noise after the
                            // user disconnected silently reconnects — and publishes SEARCHING while
                            // doing it, which is what shows as "searching" after an unpair.
                            if (ConnectionManager.isAutoConnectSuppressed()) {
                                Log.d("ConfigDebug", "BT-on reconnect suppressed — user disconnected intentionally")
                                return@let
                            }
                            // Case 2c (BT off → on with saved device): normalize per-session
                            // state the same way bondOnStartup / adopt do, so a session that was
                            // torn down by the BT-off branch above doesn't leave singleton state
                            // (attempts / initStartTriggered / etc.) that silently blocks init on
                            // the re-attach.
                            StardustInitConnectionHandler.resetForNewSession()
                            resetForNewSession()
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


    /**
     * @param autoConnect false = direct connect: fast, but only succeeds if the device is
     *   currently connectable (right for a fresh user pick / just-bonded device). true = background
     *   connect: the OS patiently waits for the device to appear (right for startup reconnect and
     *   the background auto-reconnect watchdog, where the radio may still be off).
     */
    /**
     * Returns the reason a BLE connection can't proceed right now (unsupported / permission /
     * adapter off), or null if it can. device.address is safe without permission; device.name is
     * NOT, so callers must report with the address, never the name.
     */
    @SuppressLint("MissingPermission")
    private fun bleConnectBlockReason(): BleUnavailableReason? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return BleUnavailableReason.BLUETOOTH_UNSUPPORTED
        if (!BlePermissions.hasConnectPermission(context)) return BleUnavailableReason.CONNECT_PERMISSION_MISSING
        if (!adapter.isEnabled) return BleUnavailableReason.BLUETOOTH_DISABLED
        return null
    }

    /** @return true when a `connectGatt` was actually issued, false when a guard refused it. */
    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice, autoConnect: Boolean = false): Boolean {
        Log.d("ConfigDebug",
            "connectDevice addr=${device.address} autoConnect=$autoConnect hasCallback=$hasCallback " +
                "loggedIn=${RegisteredUserUtils.isUserLoggedIn()} mDevice=${mDevice?.address}"
        )
        // Only connect while a user is logged in — startup, reconnect-watchdog, fast-reconnect and
        // the Bluetooth-on observer all funnel through here, so this one gate suppresses every
        // automatic BLE connect attempt when logged out.
        if (!RegisteredUserUtils.isUserLoggedIn()) {
            Timber.tag(LOG_TAG).d("Skipping connect to ${device.address}: no user logged in")
            return false
        }
        bleConnectBlockReason()?.let { reason ->
            Timber.tag(LOG_TAG).e("Cannot connect to ${device.address}: $reason")
            // Unified stream first (what hosts should collect), then the deprecated callback.
            ConnectionManager.reportBlocked(reason.toBlocker())
            @Suppress("DEPRECATION")
            DataManager.getCallbacks()?.onConnectionUnavailable(reason, deviceName ?: device.address)
            return false
        }
        Log.d("StardustDataManager", "connectDevice: ${device.address}, hasCallback: $hasCallback, autoConnect=$autoConnect")
        // ATOMIC connect gate. The old `if (!hasCallback)` was a non-atomic check-then-act:
        // hasCallback is only set inside getBleGattCallback(), which is evaluated as the 3rd
        // ARGUMENT of connectGatt — so two callers (e.g. bondOnStartup + the BT-state observer,
        // or two bondOnStartup calls) could both pass the check and issue TWO connectGatt calls
        // for the same device. Both share the cached callback object, so every GATT event and
        // every notification is then delivered twice — observed live as doubled
        // onConnectionStateChange / handleServicesDiscovered / CCCD writes, and every protocol
        // reply arriving twice with the second dropped by the duplicate filter.
        if (!connectInFlight.compareAndSet(false, true)) {
            Log.w("ConfigDebug", "connectDevice SKIPPED for ${device.address} — a connectGatt is already in flight/active")
            return false
        }
        resetDiscoveryState()
        connectStartedAtMs = SystemClock.elapsedRealtime()
        pendingGatt = device.connectGatt(context, autoConnect, getBleGattCallback(device))
        return true
    }

    /**
     * Whether a `connectGatt` issued earlier is still working. [connectInFlight] is the signal —
     * it is set when the connect is issued and released by the disconnect callback or an explicit
     * teardown — bounded by [CONNECT_ATTEMPT_STALE_MS] so a wedged attempt can't block recovery
     * forever.
     */
    private fun isConnectAttemptOutstanding(): Boolean {
        if (!connectInFlight.get()) return false
        val age = SystemClock.elapsedRealtime() - connectStartedAtMs
        if (age >= CONNECT_ATTEMPT_STALE_MS) {
            Log.d("ConfigDebug", "connect attempt is ${age}ms old — stale, allowing it to be superseded")
            return false
        }
        return true
    }

    /**
     * Connects preferring speed, falling back to patience: a direct attempt first, escalating to
     * Android's background connection only if it doesn't land.
     *
     * Every automatic connect used to pass `autoConnect = true` outright. That request goes on the
     * platform's background connection list and is served by a low-duty-cycle scan, so it routinely
     * takes 5-30s to attach to a radio that is switched on and advertising a metre away — while a
     * direct connect to the same radio lands in well under two seconds. The patience only ever
     * mattered for a radio that is OFF, and this keeps it for exactly that case.
     */
    @SuppressLint("MissingPermission")
    fun connectDevicePatiently(device: BluetoothDevice) {
        patientConnectJob?.cancel()
        directConnectPending.set(true)
        if (!connectDevice(device, autoConnect = false)) {
            // A guard refused it (logged out, blocked, or another connect already in flight).
            // Escalating would be wrong — at best it hits the same guard, at worst it closes the
            // other attempt's GATT client.
            directConnectPending.set(false)
            return
        }

        patientConnectJob = Scopes.getDefaultCoroutine().launch {
            delay(DIRECT_CONNECT_TIMEOUT_MS)
            escalateToBackgroundConnect(device, "direct connect silent for ${DIRECT_CONNECT_TIMEOUT_MS}ms")
        }
    }

    /**
     * Switches a failed/stalled direct attempt over to a background connect. Tears the half-open
     * client down first: [connectDevice]'s CAS gate would otherwise refuse the second attempt, and
     * the abandoned GATT client would count against the per-process limit.
     */
    @SuppressLint("MissingPermission")
    private fun escalateToBackgroundConnect(device: BluetoothDevice, reason: String) {
        // Whoever gets here first wins; the other trigger (callback vs. timeout) drops out.
        if (!directConnectPending.compareAndSet(true, false)) return
        patientConnectJob?.cancel()
        patientConnectJob = null

        Log.d("ConfigDebug", "escalating ${device.address} to background connect — $reason")
        closePendingGatt()
        connectInFlight.set(false)
        hasCallback = false
        connectDevice(device, autoConnect = true)
    }

    /** Closes the in-flight GATT client unless it is the established connection. */
    @SuppressLint("MissingPermission")
    private fun closePendingGatt() {
        pendingGatt?.takeIf { it !== gattConnection }?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        pendingGatt = null
    }

    @SuppressLint("MissingPermission")
    /**
     * Tears down the BLE link. **BLE-scoped only.**
     *
     * BLE and USB are mutually exclusive, and when USB is carrying the session this function must
     * not touch anything the USB session owns. It used to unconditionally
     * `ConfigurationUtils.reset()`, `CarriersUtils.reset()` and drive the init state to
     * `DISCONNECTED` — so unpairing BLE while USB was connected wiped the USB session's
     * configuration and dropped [com.commcrete.stardust.transport.ConnectionState] from
     * `Ready(USB)` to `LinkUp(USB)`. The UART stayed open, but from the user's point of view the
     * device had disconnected.
     */
    fun disconnectFromBLEDevice(disconnectByForce: Boolean = false, withStateUpdate: Boolean = true) {
        if(!disconnectByForce && !StardustInitConnectionHandler.isConnected()) return

        // Whether USB currently owns the shared session state.
        val usbOwnsSession = BleManager.isUSBConnected

        // Mark the link down NOW, synchronously, before anything below publishes state.
        //
        // These two assignments used to live in the main-thread coroutine at the end of this
        // function (and were commented out there), so `isBleConnected` stayed true until the GATT
        // callback arrived milliseconds later. The `withStateUpdate` block below runs BEFORE that:
        // it set the handshake state to DISCONNECTED while the transport flag still said BLE, and
        // ConnectionManager.derive(active = BLE, s = DISCONNECTED) has exactly one answer for that
        // combination — LinkUp(BLE). That is the phantom state a host sees between Ready and
        // Disconnected on every disconnect and unpair.
        //
        // Skipped when USB owns the session: the `isUSBConnected` setter already cleared
        // `isBleConnected`, and this path is then only tearing the BLE link out from under USB.
        if (!usbOwnsSession) {
            BleManager.isBleConnected = false
            // postValue: this runs on whichever thread called disconnect (binder, IO, main).
            BleManager.bleConnectionStatus.postValue(false)
        }

        // ── BLE-local teardown: always safe, USB holds none of this ──
        reconnectJob?.cancel()
        reconnectJob = null
        // An intentional teardown must not be resurrected by a pending escalation.
        directConnectPending.set(false)
        patientConnectJob?.cancel()
        patientConnectJob = null
        resetDiscoveryState()
        clearGattQueue()
        gattConnection?.disconnect()
        gattConnection?.close()
        // Covers a connect that never reached onServicesDiscovered, where gattConnection is null
        // and this is the only reference to the client.
        closePendingGatt()
        bleGatChar = null
        gattConnection = null
        hasCallback = false
        connectInFlight.set(false)

        // ── Shared session state: belongs to whichever transport is active ──
        if (usbOwnsSession) {
            Log.d("ConfigDebug", "disconnectFromBLEDevice: USB is active — keeping configuration, carriers and init state")
        } else {
            ConfigurationUtils.reset()
            CarriersUtils.reset()
        }

        Scopes.getMainCoroutine().launch {
            Timber.tag("Bittel Disconnected").d("Called Function")
            Timber.tag(LOG_TAG).d("Bittel Disconnected")
//            com.commcrete.stardust.ble.BleManager.isBleConnected = false
//            com.commcrete.stardust.ble.BleManager.bleConnectionStatus.value = false
            BleManager.updateStatus()
            // RSSI and the internal ping timer are BLE-only, so these are always correct to stop.
            removeRSSITimer()
            removePingTimer()
            // So is the 20s connection watchdog, and it was the one being left behind: it is armed
            // at init start and only cancelled when addresses arrive, so disconnecting mid-handshake
            // left it pending to fire requestReconnect(BLE) long after the user said stop.
            removeConnectionTimer()
        }
        if(withStateUpdate && !usbOwnsSession) {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
        }
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
        discoveryRequested.set(false)
        discoverServicesJob?.cancel()
        discoverServicesJob = null
        initStartJob?.cancel()
        initStartJob = null
    }

    /**
     * Explicit "prepare for a new session" reset for the per-instance guards that would otherwise
     * silently no-op a reconnect: the discovery/init CAS flags AND `hasCallback` (the gate that
     * makes [connectDevice] a no-op if a prior connect didn't tear down). Call from
     * [com.commcrete.stardust.util.DataManager.bondOnStartup] before every reconnect entry.
     */
    fun resetForNewSession() {
        resetDiscoveryState()
        hasCallback = false
        connectInFlight.set(false)
        pendingBondAddress = null
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
        if (!RegisteredUserUtils.isUserLoggedIn()) {
            Timber.tag(LOG_TAG).d("Skipping bond to ${device.address}: no user logged in")
            return
        }
        bleConnectBlockReason()?.let { reason ->
            Timber.tag(LOG_TAG).e("Cannot bond ${device.address}: $reason")
            // Unified stream first (what hosts should collect), then the deprecated callback.
            ConnectionManager.reportBlocked(reason.toBlocker())
            @Suppress("DEPRECATION")
            DataManager.getCallbacks()?.onConnectionUnavailable(reason, deviceName ?: device.address)
            return
        }
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
        val user = RegisteredUserUtils.currentUserFlow.value
        Log.d("ConfigDebug",
            "bondToBleDeviceStartup addr=${connectedDevice.address} name=${connectedDevice.name} " +
                "hasCallback=$hasCallback isBleConnected=${BleManager.isBleConnected} isPaired=${BleManager.isPaired.value} " +
                "user.appId=${user?.appId} user.deviceId=${user?.deviceId}"
        )
        Scopes.getMainCoroutine().launch {
            BleManager.isPaired.value = true
        }
        // Direct first (fast when the radio is on and near), escalating to a patient background
        // connect only if that doesn't land — the radio may still be off/out of range.
        connectDevicePatiently(connectedDevice)
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
        // Unpairing is intentional by definition, so kill auto-reconnect here rather than relying on
        // the caller: DataManager.unpairDeviceBLE() goes through disconnectFromDevice() which does
        // it, but EraseUtils calls straight in here and would otherwise leave the watchdog armed —
        // and the teardown below would read as an unexpected drop worth reconnecting.
        //
        // suppressAutoConnect, not disableAutoReconnect: unpair is the one case where the paths that
        // ignore the watchdog (Bluetooth-on observer, bondOnStartup) must also stand down.
        ConnectionManager.suppressAutoConnect()
        val deviceToUnbond = mDevice
            ?: SharedPreferencesUtil.getBittelDevice()
                ?.takeIf { it.isNotBlank() && !it.equals("empty", ignoreCase = true) }
                ?.let { getBleConnectedStardustDeviceBySavedAddress(it) }

        if (deviceToUnbond == null) {
            if (forceClearLocal) clearLocalPairing()
            disconnectFromBLEDevice(true)
            publishUnpairedState()
            return UnpairResult.NOT_PAIRED
        }

        val unbondStarted = requestUnbond(deviceToUnbond)
        // android.util.Log, not Timber: this library never plants a tree, so Timber output is
        // invisible in the host and this result could not be seen in a capture.
        Log.d("ConfigDebug",
            "removeBittelBond ${deviceToUnbond.address}: osUnbondStarted=$unbondStarted force=$forceClearLocal")
        disconnectFromBLEDevice(true)
        publishUnpairedState()

        return if (unbondStarted || forceClearLocal) {
            clearLocalPairing()
            UnpairResult.UNBONDED
        } else {
            // `removeBond` is a hidden API and is routinely blocked on recent Android, so this
            // branch is not rare. Keeping the local pairing is deliberate (app and OS agree), but
            // auto-connect stays suppressed either way — the user asked to unpair, and the SDK must
            // not reconnect just because the OS bond survived.
            Log.w("ConfigDebug",
                "OS unbond FAILED for ${deviceToUnbond.address} — local pairing kept; host should send the user to Bluetooth settings")
            UnpairResult.STILL_BONDED_KEPT
        }
    }

    /**
     * Final republish after an unpair. Every individual step of the teardown can legitimately be a
     * no-op (link already down, handshake state already DISCONNECTED), which would otherwise leave
     * the host showing whatever it was shown last — the reason an unpair during a reconnect kept
     * reading as "searching".
     */
    private fun publishUnpairedState() {
        StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
        ConnectionManager.refresh()
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
        if (!BlePermissions.hasConnectPermission(context)) {
            Timber.tag(LOG_TAG).w("BLUETOOTH_CONNECT not granted; bonded devices unavailable")
            return emptySet()
        }
        val btManager = context.getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptySet()
        return btManager.adapter?.bondedDevices ?: emptySet()
    }

    @SuppressLint("MissingPermission")
    private fun logPairedDevice(device: BluetoothDevice) {
        val aliasing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else "Empty"
        Timber.tag(LOG_TAG).d("paired device: ${device.name} at ${device.address} + $aliasing")
    }

    fun addMessageToQueue(bittelPackage: StardustPackage) {
        enqueueMessage(bittelPackage)
        peekFirstMessage()?.let { sendMessage(it) }
    }

    fun isNeedAck (opCode: StardustPackageUtils.StardustOpCode) : Boolean {
        return opCode != StardustPackageUtils.StardustOpCode.SEND_PTT_AI
    }

    private var bleGatChar : BluetoothGattCharacteristic? = null
    @SuppressLint("MissingPermission")
    fun sendMessage(bittelPackage: StardustPackage, randomID : String = "") {
        // TODO: check if FunctionalityType is valid by licence here ??
        if(isAckAwaiting() && isNeedAck(bittelPackage.stardustOpCode)) {
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
                removeMessage(bittelPackage)
            }
        }else {
            removeMessage(bittelPackage)
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
        // Snapshot once so getService and writeCharacteristic act on the same GATT even if a
        // reconnect swaps gattConnection mid-call.
        val gatt = gattConnection ?: return
        gatt.getService(Characteristics.getConnectChar(deviceLastDigit))?.getCharacteristic(uuid)
            ?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val write = gatt.writeCharacteristic(
                        it,
                        byteArray,
                        WRITE_TYPE_NO_RESPONSE
                    )
                } else {
                    val write = gatt.writeCharacteristic(it)

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
        addAwaitingAck(ackSystem)
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
                val ackSystem = removeFirstAwaitingAck()
                if (ackSystem != null) {
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
        if (removeFirstAwaitingAck() != null) {
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
        firstAwaitingAck()?.notifySuccess()
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
            // Remove only the in-flight package (tracked by resetTimer), by identity — never the
            // blind head, which on an unrelated inbound notification could be a queued-unsent packet.
            removeMessage(bittelPackage)
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
        // Leave an outstanding attempt alone — see CONNECT_ATTEMPT_STALE_MS. Deliberately not
        // applied to reconnectToDeviceFast(), which is the host acting on the user's behalf and
        // should always win.
        if (isConnectAttemptOutstanding()) {
            Log.d("ConfigDebug", "reconnectToDevice SKIPPED — a connect attempt is still outstanding")
            return
        }
        disconnectFromBLEDevice(disconnectByForce = true, withStateUpdate = false)
        reconnectJob?.cancel()
        reconnectJob = Scopes.getDefaultCoroutine().launch {
            delay(2000)
            // Direct first, then patient: a reconnect after an out-of-range blip lands immediately,
            // while a battery-died radio still gets the background attempt.
            mDevice?.let { connectDevicePatiently(it) }
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

    /**
     * Tells the radio to run in BLE-active mode (BLUETOOTH_ENABLED_BLE). Call ONLY from a BLE
     * session — sending this over USB would flip the radio away from USB. The old shared name
     * `updateBlePort` on the [BittelProtocol] interface was removed for this reason: [ClientConnection]
     * and [com.commcrete.stardust.usb.BittelUsbManager2] used to override the same name with OPPOSITE
     * semantics, which is what caused the reconnect-after-pair sync errors.
     */
    fun setBlePortModeOnRadio() {
        val (src, dst) = requireLocalSrcDst() ?: return
        Log.d("ConfigDebug", "ClientConnection.setBlePortModeOnRadio → BLUETOOTH_ENABLED_BLE (keep BLE) isUSBConnected=${BleManager.isUSBConnected} isBleConnected=${BleManager.isBleConnected}")

        val uartPort = (PortType.BLUETOOTH_ENABLED_BLE.type).intToByteArray().reversedArray()
        val data = StardustPackageUtils.byteArrayToIntArray(uartPort)
        val txPackage = StardustPackageUtils.getStardustPackage(
            source = src ,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_UART_PORT,
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





