package com.commcrete.stardust.usb

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.USB_SERVICE
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
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
        this.usbManager = DataManager.appContext.getSystemService(USB_SERVICE) as UsbManager
     }

    fun isJboxAudioConnected(): Boolean = isJboxAudioPresent

    private fun isJboxAudioDevice(device: UsbDevice): Boolean {
        val productName = device.productName ?: return false
        return productName == "FT231X USB UART PTT" ||
            productName.contains("j-box", ignoreCase = true) ||
            productName.contains("jbox", ignoreCase = true)
    }

    private fun isStardustDataDevice(device: UsbDevice): Boolean {
        val productName = device.productName ?: return false
        return productName == "FT231X USB UART" || productName.contains("stardust", ignoreCase = true)
    }

    fun connectToUnknownDevice (device: UsbDevice) {
        if (isJboxAudioDevice(device)) {
            isJboxAudioPresent = true
            connectToAudioDevice( device)
        } else if (isStardustDataDevice(device)) {
            connectToDevice(device)
        }
    }

    fun disconnectToUnknownDevice (device: UsbDevice) {
        // Tear down only the role that actually detached. Previously this unconditionally called
        // both disconnect() and disconnectAudio(), so unplugging the audio J-box also killed the
        // data link (and vice-versa). The per-type branches were also swapped.
        when {
            isJboxAudioDevice(device) -> disconnectAudio()
            isStardustDataDevice(device) -> disconnect()
            else -> {
                // Unknown device: be safe and tear down both.
                disconnect()
                disconnectAudio()
            }
        }
    }

    fun getConnectedDevicesStartup () {
        disconnect()
        disconnectAudio()
        val manager = DataManager.appContext.getSystemService(USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        usbDevicePermissionHandler.requestPermissionsForDevices(deviceList.values.toList())
    }

    private fun  initDataToUsb () {
        RegisteredUserUtils.currentUserFlow.value ?: return
        if(!BleManager.isUSBConnected || StardustInitConnectionHandler.hasUnsyncableError()) { return }

        CoroutineScope(Dispatchers.Default).launch {
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
        try {
            isConnected = false
            uartManager?.disconnect()
            uartManager = null
            stardustDevice = null
            BleManager.isUSBConnected = false
            BleManager.usbConnectionStatus.value = false
            BleManager.updateStatus ()
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    fun disconnectAudio () {
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
        uartManager?.send(bittelPackage.getStardustPackageToSend())
    }


    private fun connectToAudioDevice (device: UsbDevice) {
        if(!isConnectedAudio) {
            Timber.tag("SerialInOutputManager").d("connectToAudioDevice : ${device.productName}")
            uartManagerAudio = UARTManager()
            val connectionStatus = uartManagerAudio?.connectDevice(
                object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
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
        uartManager = UARTManager()
        val connectionStatus = uartManager?.connectDevice(object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                Timber.tag("SerialInOutputManager").d("onNewData : ${device.productName}")

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
                // Handle errors
            }
        }, device.deviceId)
        Timber.tag("SerialInOutputManager").d("connectionStatus : $connectionStatus")
        if(connectionStatus == true) {
            stardustDevice = device
            Scopes.getMainCoroutine().launch {
                BleManager.isUSBConnected = true
                BleManager.usbConnectionStatus.value = true
                initDataToUsb()
                BleManager.updateStatus ()
            }
        }else {
            Timber.tag("SerialInOutputManager").d("cant connect")
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
        usbDevicePermissionHandler.registerReceiverOnce()
    }

    fun unregisterReceiver(){
        usbDevicePermissionHandler.unregister()
    }

    private fun getUartPortType(): StardustConfigurationParser.PortType {
        return when(ConfigurationUtils.bittelConfiguration.value?.portType) {
            StardustConfigurationParser.PortType.BLUETOOTH_DISABLED_BLE,
            StardustConfigurationParser.PortType.BLUETOOTH_DISABLED_USB -> StardustConfigurationParser.PortType.BLUETOOTH_DISABLED_USB
            else -> StardustConfigurationParser.PortType.BLUETOOTH_ENABLED_USB
        }
    }

    override fun updateBlePort() {
        val (src, dst) = requireLocalSrcDst() ?: return

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