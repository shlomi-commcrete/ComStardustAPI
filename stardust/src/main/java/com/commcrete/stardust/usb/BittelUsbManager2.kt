package com.commcrete.stardust.usb

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.USB_SERVICE
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.config.PortType
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.BittelProtocol
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.HandlerObject
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.audio.ButtonListener
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

@SuppressLint("StaticFieldLeak")
object BittelUsbManager2 : BittelProtocol {

    var usbManager : UsbManager? = null

    const val ACTION_USB_PERMISSION = "com.commcrete.bittell.USB_PERMISSION"
    private var isConnected = false;
    private var isConnectedAudio = false;
    private var uartManager : UARTManager? = null
    private var uartManagerAudio : UARTManager? = null
    val SYNC_BYTES = byteArrayOf(0x37.toByte(), 0x65.toByte(), 0x21.toByte(), 0x84.toByte())

    private val reconnectTimeout : Long = 10000

    private var tempDevice : UsbDevice? = null

    private var audioDevice : UsbDevice? = null
    private var stardustDevice : UsbDevice? = null
    @Volatile
    private var isJboxAudioPresent: Boolean = false

    private val echoPackage : ByteArray = byteArrayOf(0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01 )

    private val usbDevicePermissionHandler : UsbDevicePermissionHandler  = UsbDevicePermissionHandler


    private var handlerObject : HandlerObject? = null

    private val mDeviceList: MutableSet<UsbDevice> = mutableSetOf()

    fun init() {
        Log.d("ConfigDebug",
            "BittelUsbManager2 init ${DataManager.appContext}"
        )
        UsbDiag.log("init", "ENTER — resolving USB_SERVICE")
        this.usbManager = DataManager.appContext.getSystemService(USB_SERVICE) as UsbManager
        UsbDiag.log("init", "usbManager resolved=${usbManager != null}")
        UsbDiag.env("init")
        UsbDiag.dumpAttachedDevices("init")
     }

    fun isJboxAudioConnected(): Boolean = isJboxAudioPresent

    /**
     * On-demand USB diagnostics dump for the "USB never connects" investigation. Safe to call from
     * anywhere (a debug button, a shake gesture, `adb shell am broadcast` → host hook) at any point
     * in the session. Everything lands on the `UsbDiag` logcat tag.
     *
     * Prints: environment (SDK / host targetSdk / usb-host feature / UsbManager resolved), whether
     * the broadcast receiver is registered, every attached device with its full identity and the
     * complete filter/role decision, and the current link state.
     */
    fun dumpUsbDiagnostics(reason: String = "manual") {
        UsbDiag.log("dump", "===== USB DIAGNOSTIC DUMP ($reason) =====")
        UsbDiag.env("dump")
        UsbDiag.log(
            "dump",
            "receiverRegistered=${usbDevicePermissionHandler.isReceiverRegistered()} " +
                "uartManager=${if (uartManager == null) "null" else "open"} " +
                "uartManagerAudio=${if (uartManagerAudio == null) "null" else "open"} " +
                "stardustDevice=${stardustDevice?.deviceId} audioDevice=${audioDevice?.deviceId} " +
                "isConnectedAudio=$isConnectedAudio isJboxAudioPresent=$isJboxAudioPresent " +
                "usbRecovery=${if (isUsbRecoveryInProgress()) "RUNNING" else "idle"} " +
                "dataDeviceStillAttached=${attachedStardustDataDevice()?.deviceId}"
        )
        UsbDiag.dumpAttachedDevices("dump")
        UsbDiag.linkState("dump")
        UsbDiag.log("dump", "===== END DUMP =====")
    }

    private fun isJboxAudioDevice(device: UsbDevice): Boolean {
        val productName = device.productName?.lowercase() ?: return false
        return productName.contains("ft231x usb uart ptt") ||
            productName.contains("j-box", ignoreCase = true) ||
            productName.contains("jbox", ignoreCase = true)
    }

    private fun isStardustDataDevice(device: UsbDevice): Boolean {
        val productName = device.productName?.lowercase() ?: return false
        return productName.contains("ft231x usb uart ptt") ||
            productName.contains("stardust")
    }

    fun connectToUnknownDevice (device: UsbDevice) {
        Log.d("ConfigDebug",
            "connectToUnknownDevice productName='${device.productName}' deviceId=${device.deviceId} " +
                "isJboxAudio=${isJboxAudioDevice(device)} isStardustData=${isStardustDataDevice(device)}"
        )
        UsbDiag.log("connectToUnknownDevice", "ENTER ${UsbDiag.describe(device)} hasPermission=${UsbDiag.hasPermission(device)}")
        UsbDiag.match("connectToUnknownDevice", device)
        if (isJboxAudioDevice(device)) {
            UsbDiag.log("connectToUnknownDevice", "→ AUDIO role (checked first; a name containing 'ft231x usb uart ptt' NEVER reaches the data branch)")
            isJboxAudioPresent = true
            connectToAudioDevice(device)
        } else if (isStardustDataDevice(device)) {
            UsbDiag.log("connectToUnknownDevice", "→ DATA role")
            connectToDevice(device)
        } else {
            UsbDiag.verdict(
                "connectToUnknownDevice reached with NO matching role for " +
                    "${UsbDiag.describe(device)} — permission was granted and is now discarded."
            )
            // Previously silent: the permission filter (contains) is looser than these matchers, so
            // a granted device could land here and simply be ignored.
            android.util.Log.w("ConfigDebug",
                "connectToUnknownDevice NO MATCH for productName='${device.productName}' — permission was " +
                    "granted but neither the audio nor the data matcher accepted it; nothing will connect"
            )
        }
    }

    fun disconnectToUnknownDevice (device: UsbDevice) {
        // Tear down only the role that actually detached. Previously this unconditionally called
        // both disconnect() and disconnectAudio(), so unplugging the audio J-box also killed the
        // data link (and vice-versa). The per-type branches were also swapped.
        UsbDiag.log("disconnectToUnknownDevice", "ENTER ${UsbDiag.describe(device)}")
        when {
            isJboxAudioDevice(device) -> {
                UsbDiag.log("disconnectToUnknownDevice", "→ tearing down AUDIO only (data link untouched)")
                disconnectAudio()
            }
            isStardustDataDevice(device) -> {
                UsbDiag.log("disconnectToUnknownDevice", "→ tearing down DATA only (audio untouched)")
                disconnect()
            }
            else -> {
                UsbDiag.warn("disconnectToUnknownDevice", "unknown device — tearing down BOTH roles")
                // Unknown device: be safe and tear down both.
                disconnect()
                disconnectAudio()
            }
        }
    }

    fun getConnectedDevicesStartup () {
        UsbDiag.env("getConnectedDevicesStartup")
        UsbDiag.log(
            "getConnectedDevicesStartup",
            "ENTER — about to tear down BOTH roles unconditionally " +
                "(receiverRegistered=${usbDevicePermissionHandler.isReceiverRegistered()})"
        )
        UsbDiag.linkState("getConnectedDevicesStartup.before")
        disconnect()
        disconnectAudio()
        val manager = DataManager.appContext.getSystemService(USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        UsbDiag.log("getConnectedDevicesStartup", "enumerated ${deviceList.size} attached device(s)")
        UsbDiag.dumpAttachedDevices("getConnectedDevicesStartup")
        usbDevicePermissionHandler.requestPermissionsForDevices(deviceList.values.toList())
    }

    /**
     * Runs the init handshake over USB.
     *
     * @param freshLink true when called right after a USB port was opened — the handshake must run
     *   unconditionally (per-session state is reset below). false when called as a *reconnect*
     *   (via [reconnectToDevice] ← `UsbTransport.reconnect` ← the keepalive watchdog), where an
     *   already-succeeded or in-flight handshake must be left alone.
     */
    private fun  initDataToUsb (freshLink: Boolean) {
        val user = RegisteredUserUtils.currentUserFlow.value
        UsbDiag.log(
            "initDataToUsb",
            "ENTER freshLink=$freshLink user.appId=${user?.appId} isUSBConnected=${BleManager.isUSBConnected} " +
                "hasUnsyncableError=${StardustInitConnectionHandler.hasUnsyncableError()}"
        )
        if (user == null) {
            android.util.Log.w("ConfigDebug", "initDataToUsb ABORT — no logged-in user; USB link is open but the init handshake will NOT run")
            UsbDiag.verdict("USB port is OPEN but the handshake will not run: no logged-in user. The link will look connected and stay silent.")
            return
        }
        // The terminal-error half of this guard is scoped to a RECONNECT. On a freshly opened port
        // it contradicted this function's own contract ("freshLink == true must NEVER be skipped")
        // and latched a previous session's NO_LICENSE / ENCRYPTION_KEY_ERROR / PRESET_ERROR onto the
        // next one: the abort happens BEFORE resetForNewSession(), the only thing that clears it, so
        // the port would open, isUSBConnected would flip true, and no handshake would ever run — a
        // connected cable permanently reported as not connected. A real error is re-derived by the
        // handshake we now run; it no longer has to be inherited to be reported.
        if(!BleManager.isUSBConnected || (!freshLink && StardustInitConnectionHandler.hasUnsyncableError())) {
            Log.w("ConfigDebug",
                "initDataToUsb ABORT — isUSBConnected=${BleManager.isUSBConnected} " +
                    "hasUnsyncableError=${StardustInitConnectionHandler.hasUnsyncableError()}"
            )
            if (!BleManager.isUSBConnected && uartManager == null) {
                // Was observed repeating every ~11-40s during a *BLE* session: the PortUtils ping
                // watchdog (started by the host via DataManager.getPortUtils().startUpdatingPort())
                // requested a reconnect with the transport HARD-CODED to USB, so a BLE ping timeout
                // landed here instead of reconnecting BLE — burning the single-flight reconnect slot
                // on the way. Fixed in PortUtils; this warning should no longer appear.
                UsbDiag.warn(
                    "initDataToUsb",
                    "SPURIOUS USB reconnect: no USB port is open and isUSBConnected=false. " +
                        "Expected to be extinct after the PortUtils transport-routing fix — if you " +
                        "still see this, find the caller of UsbTransport.reconnect()."
                )
            } else {
                UsbDiag.verdict(
                    "USB link is up but the handshake will not run: " +
                        "isUSBConnected=${BleManager.isUSBConnected} " +
                        "hasUnsyncableError=${StardustInitConnectionHandler.hasUnsyncableError()}"
                )
            }
            return
        }
        // On a RECONNECT (not a freshly opened port), do not restart a handshake that already
        // succeeded or is still in flight. initDataToUsb() doubles as the USB reconnect entry point
        // (UsbTransport.reconnect ← the keepalive-ping watchdog), so a single missed ping on a
        // healthy-but-busy link used to re-run the whole 7-step init from scratch — observed live:
        // SUCCESS at 11:10:48, then READING_VERSION → SUCCESS again at 11:10:58. A genuinely dead
        // port cannot be recovered by re-sending the handshake anyway; the detach event tears it
        // down and a re-attach rebuilds it.
        //
        // freshLink == true must NEVER be skipped: a prior BLE session commonly leaves the handler
        // in SUCCESS, and the USB handshake has to run regardless (the state is reset below).
        if (!freshLink &&
            (StardustInitConnectionHandler.isConnectedSuccessfully() || StardustInitConnectionHandler.isSyncing())
        ) {
            UsbDiag.log(
                "initDataToUsb",
                "SKIPPED reconnect — handshake already " +
                    (if (StardustInitConnectionHandler.isConnectedSuccessfully()) "SUCCEEDED" else "IN PROGRESS") +
                    "; not restarting it"
            )
            return
        }
        android.util.Log.d("ConfigDebug", "initDataToUsb → StardustInitConnectionHandler.start() (USB)")

        CoroutineScope(Dispatchers.Default).launch {
            // USB is another fresh-session entry — normalise singleton state exactly like the BLE
            // Case-2 entries do, so a stale state/attempts map from a prior BLE session can't
            // silently block the USB handshake.
            StardustInitConnectionHandler.resetForNewSession()
            StardustInitConnectionHandler.start()
            StardustInitConnectionHandler.listener = object :
                StardustInitConnectionHandler.InitConnectionListener {}

//                val mPackage = StardustPackageUtils.getStardustPackage(
//                    source = it , destenation = "1", stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADDRESS)
//                mPackage.openControlByte.stardustCryptType = OpenStardustControlByte.StardustCryptType.DECRYPTED
//                Timber.tag("SerialInputOutputManager").d("uartManager.send")
//                sendDataToUart(mPackage)
        }
    }

    /** [com.commcrete.stardust.transport.UsbTransport.reconnect] — a re-entry, not a fresh port. */
    fun reconnectToDevice() { initDataToUsb(freshLink = false) }

    // ───────────────────────── Dead-link recovery ─────────────────────────

    /** First re-probe delay, doubled per attempt up to [RECOVERY_MAX_DELAY_MS]. */
    private const val RECOVERY_BASE_DELAY_MS = 5_000L
    private const val RECOVERY_MAX_DELAY_MS = 30_000L

    private var recoveryJob: Job? = null

    /** One permission request per recovery session — a loop of them would be a dialog storm. */
    @Volatile
    private var recoveryPermissionRequested = false

    fun isUsbRecoveryInProgress(): Boolean = recoveryJob?.isActive == true

    /**
     * The device that would take the DATA role if it were (re-)attached right now, or null.
     *
     * Deliberately excludes anything [isJboxAudioDevice] accepts: that matcher is checked FIRST in
     * [connectToUnknownDevice], so a name it claims can never reach the data branch — re-probing it
     * as a data device would open the wrong port.
     */
    private fun attachedStardustDataDevice(): UsbDevice? {
        val manager = usbManager
            ?: runCatching { DataManager.appContext.getSystemService(USB_SERVICE) as UsbManager }.getOrNull()
            ?: return null
        return runCatching {
            manager.deviceList.values.firstOrNull { isStardustDataDevice(it) && !isJboxAudioDevice(it) }
        }.getOrNull()
    }

    /**
     * The USB data link went down **on its own** — the serial IO thread died, or the keepalive
     * watchdog declared the radio silent ([com.commcrete.stardust.util.connectivity.PortUtils]).
     * Distinct from [disconnect], which is also the intentional teardown used by a detach and by the
     * host.
     *
     * Tears the port down and then, if the radio is still sitting on the bus, starts re-probing it.
     * Without that second half a radio whose battery dies (or whose MCU hangs) while plugged in is
     * unrecoverable: the FTDI bridge stays enumerated and bus-powered, so **no DETACHED/ATTACHED
     * pair is ever broadcast**, and attach is the SDK's only path back to an open port. The observed
     * result was `Ready(USB)` → `Disconnected` when the watchdog fired, then `Disconnected` forever
     * even after the radio booted back up with the cable still in.
     */
    fun onDataLinkLost(reason: String) {
        UsbDiag.warn("onDataLinkLost", "unexpected USB data-link loss: $reason")
        disconnect()
        startUsbRecovery(reason)
    }

    /**
     * Re-probes the still-attached data device until the port reopens or the device leaves the bus.
     *
     * Bounded deliberately:
     * - **Stops the moment the device is unplugged.** A physical unplug stays user-decided — the
     *   host shows its "connect via BLE / unpair" prompt and a later re-attach comes in through the
     *   ATTACHED broadcast. This loop only ever restores a link to hardware that never left.
     * - **Never touches BLE.** A tick is skipped while a BLE link is up, so a speculative probe
     *   cannot tear down a working BLE session (opening the port flips `isUSBConnected`, and
     *   `updateStatus()` would then hand the transport over) only to have the watchdog close it
     *   again 20s later because the radio is still dead.
     * - **Backs off** 5s → 10s → 20s → 30s, so a radio that stays dead costs one open/close per 30s
     *   rather than a hot loop.
     */
    @Synchronized
    private fun startUsbRecovery(reason: String) {
        val device = attachedStardustDataDevice()
        if (device == null) {
            UsbDiag.log(
                "recovery",
                "NOT starting ($reason) — no data device on the bus; the device is gone, so an " +
                    "ATTACHED broadcast (or the host) owns reconnection from here"
            )
            return
        }
        if (recoveryJob?.isActive == true) {
            UsbDiag.log("recovery", "already running — not restarting ($reason)")
            return
        }
        recoveryPermissionRequested = false
        UsbDiag.log(
            "recovery",
            "START ($reason) — ${UsbDiag.describe(device)} is STILL enumerated, so no re-attach event " +
                "will ever come; re-probing it instead"
        )
        // The Disconnected that disconnect() just published STANDS. The verdict that got us here is
        // three unanswered pings — ~30s with nothing on the other end — so the user is told the link
        // is down, truthfully, and these re-probes run behind that. Publishing "searching" for as
        // long as a dead radio stays plugged in would be an indefinite promise.
        recoveryJob = Scopes.getDefaultCoroutine().launch {
            var attempt = 0
            while (isActive) {
                val backoff = (RECOVERY_BASE_DELAY_MS shl attempt.coerceAtMost(3))
                    .coerceAtMost(RECOVERY_MAX_DELAY_MS)
                attempt++
                delay(backoff)

                if (BleManager.isUSBConnected) {
                    UsbDiag.log("recovery", "STOP — the USB data link is up again")
                    break
                }
                val target = attachedStardustDataDevice()
                if (target == null) {
                    UsbDiag.log(
                        "recovery",
                        "STOP — the data device left the bus; an unplug is the user's decision, the " +
                            "host takes it from here"
                    )
                    Scopes.getMainCoroutine().launch { publishDisconnectedIfIdle() }
                    break
                }
                if (BleManager.isBluetoothConnected()) {
                    UsbDiag.log(
                        "recovery",
                        "tick #$attempt skipped — a BLE link is up; not tearing it down for a probe " +
                            "that may find the radio still dead"
                    )
                    continue
                }
                if (usbManager?.hasPermission(target) != true) {
                    if (recoveryPermissionRequested) {
                        UsbDiag.log("recovery", "tick #$attempt skipped — waiting for the permission result")
                        continue
                    }
                    recoveryPermissionRequested = true
                    UsbDiag.warn(
                        "recovery",
                        "permission for ${UsbDiag.describe(target)} is no longer held — requesting it " +
                            "ONCE (a request per tick would be a dialog storm)"
                    )
                    Scopes.getMainCoroutine().launch {
                        usbDevicePermissionHandler.requestPermissionsForDevices(listOf(target))
                    }
                    continue
                }
                UsbDiag.log("recovery", "re-probe #$attempt — reopening the port for ${UsbDiag.describe(target)}")
                // connectToDevice directly, not connectToUnknownDevice: the role was already decided
                // by attachedStardustDataDevice(). Main thread, matching the ATTACHED path.
                Scopes.getMainCoroutine().launch { connectToDevice(target) }
            }
        }
    }

    /** Cancels the re-probe loop. Called from every teardown, so an intentional one stays down. */
    @Synchronized
    private fun stopUsbRecovery(reason: String) {
        if (recoveryJob?.isActive != true) return
        UsbDiag.log("recovery", "CANCELLED — $reason")
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryPermissionRequested = false
    }

    /**
     * Settles the published state on a teardown that `updateStatus()` cannot publish for itself —
     * it early-returns when the transport it computes is unchanged, so a detach arriving while the
     * link is already down (the end of a re-probe loop, say) would publish nothing at all.
     */
    private fun publishDisconnectedIfIdle() {
        if (!BleManager.isUSBConnected && !BleManager.isBluetoothConnected()) {
            StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
        }
    }

    fun resetReconnect () {
//        handlerObject?.removeTimer()
    }

    fun disconnect () {
        UsbDiag.log(
            "disconnect",
            "DATA teardown — uartManager=${if (uartManager == null) "null (nothing open)" else "open"} " +
                "isUSBConnected(before)=${BleManager.isUSBConnected}",
        )
        try {
            // Any teardown that comes through here is either intentional (host / detach) or is
            // about to (re)start recovery itself via onDataLinkLost — in both cases a loop left
            // over from an earlier loss must not survive and reopen the port behind us.
            stopUsbRecovery("disconnect()")
            isConnected = false
            uartManager?.disconnect()
            uartManager = null
            stardustDevice = null
            BleManager.isUSBConnected = false
            BleManager.usbConnectionStatus.value = false
            BleManager.updateStatus ()
            // updateStatus() early-returns when the transport it computes is unchanged, so a
            // teardown that arrives with the transport ALREADY null — a detach that ends a re-probe
            // loop, say — publishes nothing and would leave the loop's SEARCHING on screen.
            publishDisconnectedIfIdle()
            UsbDiag.linkState("disconnect.after")
        }catch (e : Exception) {
            e.printStackTrace()
            UsbDiag.error("disconnect", "teardown threw", e)
        }
    }

    fun disconnectAudio () {
        UsbDiag.log(
            "disconnectAudio",
            "AUDIO teardown — uartManagerAudio=${if (uartManagerAudio == null) "null" else "open"} " +
                "isConnectedAudio=$isConnectedAudio isJboxAudioPresent=$isJboxAudioPresent (no BleManager state is touched)"
        )
        try {

            isConnectedAudio = false
            isJboxAudioPresent = false
            uartManagerAudio?.disconnect()
            uartManagerAudio = null
            audioDevice = null
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    fun sendDataToUart (bittelPackage: StardustPackage) {
        // A null uartManager here while isUSBConnected==true is the "sends go nowhere" failure:
        // the flags say USB is up but no port is open.
        val manager = uartManager
        if (manager == null) {
            UsbDiag.warn(
                "sendDataToUart",
                "DROPPED ${bittelPackage.stardustOpCode} — uartManager is NULL while " +
                    "isUSBConnected=${BleManager.isUSBConnected}"
            )
            return
        }
        val sent = manager.send(bittelPackage.getStardustPackageToSend())
        if (sent) {
            UsbDiag.log("sendDataToUart", "TX ${bittelPackage.stardustOpCode}")
        } else {
            UsbDiag.warn(
                "sendDataToUart",
                "TX FAILED ${bittelPackage.stardustOpCode} — the port rejected the write. If this " +
                    "repeats without an onRunError, the link is dead but still reported as connected."
            )
        }
    }


    private fun connectToAudioDevice(device: UsbDevice) {
        UsbDiag.log("connectToAudioDevice", "ENTER isConnectedAudio=$isConnectedAudio ${UsbDiag.describe(device)}")
        if (isConnectedAudio) {
            UsbDiag.warn("connectToAudioDevice", "SKIPPED — isConnectedAudio is already true")
        }
        if(!isConnectedAudio) {
            Timber.tag("SerialInOutputManager").d("connectToAudioDevice : ${device.productName}")
            UsbDiag.log(
                "connectToAudioDevice",
                "opening audio UART; previousAudioManager=${if (uartManagerAudio == null) "null" else "REPLACED (leak)"} " +
                    "— isConnectedAudio only flips true once an echo frame arrives"
            )
            uartManagerAudio = UARTManager()
            val connectionStatus = uartManagerAudio?.connectDevice(
                object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    UsbDiag.log("connectToAudioDevice.onNewData", "RX ${data.size} bytes isEcho=${data.toHex() == echoPackage.toHex()}")
                    if (data.toHex() == echoPackage.toHex()) {
//                        removeConnectionTimer()
                        Timber.tag("SerialInOutputManager").d("uartManagerAudio : Found PTT Device")
                        audioDevice = device
                        tempDevice = null
                        isConnectedAudio = true
//                        continueConnectingDevices ()
                    } else {
                        Timber.tag("SerialInOutputManager").d("uartManagerAudio : Not PTT Device")
//                        connectToDevice(context, device)
                    }
                    // Handle incoming data
                }

                override fun onRunError(e: Exception) {
                    Timber.tag("SerialInOutputManager").d("onRunError uartManagerAudio")
                    Timber.tag("SerialInOutputManager").d(e.message)
                    UsbDiag.error("connectToAudioDevice.onRunError", "audio serial IO thread died — tearing the audio link down", e)
                    // Same gap as the data link: without this, isConnectedAudio / isJboxAudioPresent
                    // stay true after the port dies, so isJboxAudioConnected() lies and the
                    // !isConnectedAudio guard blocks a genuine re-connect. Off the IO thread for the
                    // same executor-shutdown reason as above.
                    Scopes.getMainCoroutine().launch { disconnectAudio() }
                }
            },
                device.deviceId,
                object : UARTManager.CTSChange {
                override fun onCTSChanged(isActive: Boolean) {
                    ButtonListener.notifyData(isActive)
                }

            })
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        Timber.tag("SerialInOutputManager").d("connectToDevice : ${device.productName}")
        UsbDiag.log(
            "connectToDevice",
            "ENTER ${UsbDiag.describe(device)} previousUartManager=${if (uartManager == null) "null" else "REPLACED (old one leaks unless disconnected)"}"
        )
        UsbDiag.linkState("connectToDevice.before")
        uartManager = UARTManager()
        val connectionStatus = uartManager?.connectDevice(object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                Timber.tag("SerialInOutputManager").d("onNewData : ${device.productName}")
                UsbDiag.log("connectToDevice.onNewData", "RX ${data.size} bytes — the data link is genuinely alive")

                Timber.tag("SerialInOutputManager").d("onNewData uartManager")
//                Timber.tag("SerialInputOutputManager").d(data.toHex())
                // Handle incoming data
                processReceivedData(data)
                Scopes.getMainCoroutine().launch {
                    BleManager.isUSBConnected = true
                    BleManager.usbConnectionStatus.value = true
                    BleManager.updateStatus ()
                }

            }
            override fun onRunError(e: Exception) {
                Timber.tag("SerialInOutputManager").d("onRunError uartManager")
                Timber.tag("SerialInOutputManager").d(e.message)
                UsbDiag.error("connectToDevice.onRunError", "serial IO thread died — tearing the USB data link down", e)
                // The SerialInputOutputManager thread has exited: the port is dead (observed live as
                // `IOException: USB get_status request failed` from CommonUsbSerialPort.testConnection).
                // This used to only log, so isUSBConnected stayed true, uartManager stayed non-null
                // and the SDK kept reporting Ready(USB) forever — writes were swallowed by
                // UARTManager.send and a detach broadcast does not necessarily follow, because the
                // device can still be enumerated.
                //
                // Dispatched to main: disconnect() writes LiveData via setValue, which is
                // main-thread-only, and this callback runs on the serial IO executor. Going through
                // main also avoids shutting that executor down from inside one of its own tasks.
                //
                // onDataLinkLost rather than a bare disconnect(): if the bridge is still enumerated
                // (a hung radio behind a live FTDI), nothing else will ever reopen this port.
                Scopes.getMainCoroutine().launch {
                    onDataLinkLost("serial IO thread died: ${e.message}")
                    UsbDiag.linkState("connectToDevice.onRunError.after")
                }
            }
        }, device.deviceId)
        Timber.tag("SerialInOutputManager").d("connectionStatus : $connectionStatus")
        android.util.Log.d("ConfigDebug", "connectToDevice UART open result=$connectionStatus for '${device.productName}'")
        UsbDiag.log("connectToDevice", "UART open result=$connectionStatus")
        if(connectionStatus == true) {
            stardustDevice = device
            // The port is open again — end the re-probe loop now rather than waiting for its next
            // tick to notice (a tick that would otherwise open a SECOND UARTManager).
            stopUsbRecovery("port reopened")
            Scopes.getMainCoroutine().launch {
                android.util.Log.d("ConfigDebug", "USB up → isUSBConnected=true, initDataToUsb(), updateStatus() (this is what disconnects BLE)")
                UsbDiag.log("connectToDevice", "USB UP — setting isUSBConnected=true; note initDataToUsb() runs BEFORE the BLE teardown in updateStatus()")
                BleManager.isUSBConnected = true
                BleManager.usbConnectionStatus.value = true
                // Order matters: updateStatus() performs the BLE teardown, so it must run BEFORE
                // the handshake starts. Previously initDataToUsb() ran first, so the init sequence
                // began while the BLE GATT was still open — both stacks live at once.
                BleManager.updateStatus ()
                initDataToUsb(freshLink = true)
                UsbDiag.linkState("connectToDevice.afterUpdateStatus")
                UsbDiag.log(
                    "connectToDevice",
                    "bleConnectionStatus LiveData=${BleManager.bleConnectionStatus.value} " +
                        "(stays true unless updateStatus took the BLE→USB takeover branch — see fix #3)"
                )
            }
        }else {
            Timber.tag("SerialInOutputManager").d("cant connect")
            android.util.Log.w("ConfigDebug", "connectToDevice FAILED — UART did not open; isUSBConnected stays false so BLE is NOT handed over")
            UsbDiag.verdict(
                "UART did not open for ${UsbDiag.describe(device)} — permission was granted but the " +
                    "serial port could not be opened. See the UsbDiag 'UART' lines above for which step failed."
            )
        }
    }

    fun processReceivedData(data: ByteArray) {
        try {
//            Timber.tag("SerialInputOutputManager").d("Received data: %s", "")
            StardustPackageUtils.handlePackageReceived(data, "USB")
        }catch (e : Exception) {
            e.printStackTrace()
        }
        // Process the data received from the device
    }

    fun registerReceiver(){
        // Single registration point: delegate to the guarded registrar so the receiver is never
        // registered twice (this used to register the same instance a second time).
        UsbDiag.log("registerReceiver", "host called registerReceiver()")
        usbDevicePermissionHandler.registerReceiverOnce()
    }

    fun unregisterReceiver(){
        UsbDiag.log("unregisterReceiver", "host called unregisterReceiver()")
        usbDevicePermissionHandler.unregister()
    }

    private fun getUartPortType(): PortType {
        return when(ConfigurationUtils.bittelConfiguration.value?.portType) {
            PortType.BLUETOOTH_DISABLED_BLE,
            PortType.BLUETOOTH_DISABLED_USB -> PortType.BLUETOOTH_DISABLED_USB
            else -> PortType.BLUETOOTH_ENABLED_USB
        }
    }

    /**
     * Tells the radio to run in USB-active mode (BLUETOOTH_ENABLED_USB / BLUETOOTH_DISABLED_USB).
     * Call ONLY from a USB session — sending this over BLE would disable BLE on the radio and is
     * exactly the sync-error bug that reconnect-after-pair used to hit when the shared
     * `updateBlePort` interface method routed the wrong implementation.
     */
    fun setUsbPortModeOnRadio() {
        val (src, dst) = requireLocalSrcDst() ?: return
        android.util.Log.d("ConfigDebug", "BittelUsbManager2.setUsbPortModeOnRadio → ${getUartPortType()} (radio to USB mode) isUSBConnected=${BleManager.isUSBConnected} isBleConnected=${BleManager.isBleConnected}")

        val uartPort = getUartPortType().type.intToByteArray().reversedArray()
        val data = StardustPackageUtils.byteArrayToIntArray(uartPort)
        val txPackage = StardustPackageUtils.getStardustPackage(
            source = src ,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_UART_PORT,
            data = data)

        DataManager.getClientConnection().addMessageToQueue(txPackage)
    }

    override fun saveConfiguration() {
        val (src, dst) = requireLocalSrcDst() ?: return

        val configurationSavePackage = StardustPackageUtils.getStardustPackage(
            source = src ,
            destination = dst,
            stardustOpCode =StardustPackageUtils.StardustOpCode.SAVE_CONFIGURATION)

        DataManager.getClientConnection().addMessageToQueue(configurationSavePackage)
    }
}
fun Array<Int>.startsWith(subArray: Array<Int>): Boolean {
    if (this.size < subArray.size) return false

    for (i in subArray.indices) {
        if (this[i] != subArray[i]) return false
    }

    return true
}

fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (this.size < prefix.size) return false
    return this.sliceArray(0 until prefix.size).contentEquals(prefix)
}