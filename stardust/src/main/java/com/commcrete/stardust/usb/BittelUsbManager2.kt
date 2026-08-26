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
                "isConnectedAudio=$isConnectedAudio isJboxAudioPresent=$isJboxAudioPresent"
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

    private fun  initDataToUsb () {
        val user = RegisteredUserUtils.currentUserFlow.value
        UsbDiag.log(
            "initDataToUsb",
            "ENTER user.appId=${user?.appId} isUSBConnected=${BleManager.isUSBConnected} " +
                "hasUnsyncableError=${StardustInitConnectionHandler.hasUnsyncableError()}"
        )
        if (user == null) {
            android.util.Log.w("ConfigDebug", "initDataToUsb ABORT — no logged-in user; USB link is open but the init handshake will NOT run")
            UsbDiag.verdict("USB port is OPEN but the handshake will not run: no logged-in user. The link will look connected and stay silent.")
            return
        }
        if(!BleManager.isUSBConnected || (BleManager.isUSBConnected && StardustInitConnectionHandler.hasUnsyncableError())) {
            Log.w("ConfigDebug",
                "initDataToUsb ABORT — isUSBConnected=${BleManager.isUSBConnected} " +
                    "hasUnsyncableError=${StardustInitConnectionHandler.hasUnsyncableError()}"
            )
            if (!BleManager.isUSBConnected && uartManager == null) {
                // Observed live, repeating every ~11s during a *BLE* session: PortUtils' ping
                // watchdog calls ConnectionManager.requestReconnect(TransportId.USB) with the
                // transport HARD-CODED to USB, so a BLE ping timeout lands here instead of
                // reconnecting BLE — and it burns the single-flight reconnect slot on the way.
                UsbDiag.warn(
                    "initDataToUsb",
                    "SPURIOUS USB reconnect: no USB port is open and isUSBConnected=false. " +
                        "Caller is almost certainly PortUtils' hard-coded " +
                        "requestReconnect(TransportId.USB, \"USB ping timeout\") — see PortUtils.kt:25. " +
                        "This also suppresses the BLE reconnect that should have happened."
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

    fun reconnectToDevice() { initDataToUsb() }

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
            isConnected = false
            uartManager?.disconnect()
            uartManager = null
            stardustDevice = null
            BleManager.isUSBConnected = false
            BleManager.usbConnectionStatus.value = false
            BleManager.updateStatus ()
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
        if (uartManager == null) {
            UsbDiag.warn(
                "sendDataToUart",
                "DROPPED ${bittelPackage.stardustOpCode} — uartManager is NULL while " +
                    "isUSBConnected=${BleManager.isUSBConnected}"
            )
        } else {
            UsbDiag.log("sendDataToUart", "TX ${bittelPackage.stardustOpCode}")
        }
        uartManager?.send(bittelPackage.getStardustPackageToSend())
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
                    // Handle errors
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
                UsbDiag.error("connectToDevice.onRunError", "serial IO thread died — link is dead but nothing tears it down", e)
                // Handle errors
            }
        }, device.deviceId)
        Timber.tag("SerialInOutputManager").d("connectionStatus : $connectionStatus")
        android.util.Log.d("ConfigDebug", "connectToDevice UART open result=$connectionStatus for '${device.productName}'")
        UsbDiag.log("connectToDevice", "UART open result=$connectionStatus")
        if(connectionStatus == true) {
            stardustDevice = device
            Scopes.getMainCoroutine().launch {
                android.util.Log.d("ConfigDebug", "USB up → isUSBConnected=true, initDataToUsb(), updateStatus() (this is what disconnects BLE)")
                UsbDiag.log("connectToDevice", "USB UP — setting isUSBConnected=true; note initDataToUsb() runs BEFORE the BLE teardown in updateStatus()")
                BleManager.isUSBConnected = true
                BleManager.usbConnectionStatus.value = true
                initDataToUsb()
                BleManager.updateStatus ()
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