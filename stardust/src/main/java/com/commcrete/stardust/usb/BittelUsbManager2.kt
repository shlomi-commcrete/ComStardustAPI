package com.commcrete.stardust.usb

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.commcrete.bittell.util.bittel_package.UARTManager
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.OpenStardustControlByte
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.BittelProtocol
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.HandlerObject
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.audio.ButtonListener
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

@SuppressLint("StaticFieldLeak")
object BittelUsbManager2 : BittelProtocol {

    var usbManager : UsbManager? = null

    const val ACTION_USB_PERMISSION = "com.commcrete.bittell.USB_PERMISSION"
    private var context : Context? = null
    private var isConnected = false;
    private var isConnectedAudio = false;
    private var uartManager : UARTManager? = null
    private var uartManagerAudio : UARTManager? = null
    val SYNC_BYTES = byteArrayOf(0x37.toByte(), 0x65.toByte(), 0x21.toByte(), 0x84.toByte())

    private val reconnectTimeout : Long = 10000

    private var tempDevice : UsbDevice? = null

    private var audioDevice : UsbDevice? = null
    private var stardustDevice : UsbDevice? = null

    private val echoPackage : ByteArray = byteArrayOf(0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01 )

    private val usbDevicePermissionHandler : UsbDevicePermissionHandler  = UsbDevicePermissionHandler


    private var handlerObject : HandlerObject? = null

    private val mDeviceList : MutableSet<UsbDevice> = mutableSetOf()

    fun init(context: Context) {
        this.context = context
        this.usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    fun connectToUnknownDevice (context: Context, device: UsbDevice) {
        if(device.productName == "FT231X USB UART PTT" || device.productName?.toLowerCase()?.contains("j-box") == true
            || device.productName?.toLowerCase()?.contains("jbox") == true) {
            connectToAudioDevice(context, device)
        }else if (device.productName == "FT231X USB UART"|| device.productName?.toLowerCase()?.contains("stardust") == true ) {
            connectToDevice(context, device)
        }
    }

    fun disconnectToUnknownDevice (context: Context, device: UsbDevice) {
        if(device.productName == "FT231X USB UART PTT" || device.productName?.toLowerCase()?.contains("j-box") == true
            || device.productName?.toLowerCase()?.contains("jbox") == true) {

            disconnect()
        }else if (device.productName == "FT231X USB UART"|| device.productName?.toLowerCase()?.contains("stardust") == true ) {
            disconnectAudio()
        }
        disconnect()
        disconnectAudio()
    }

    fun getConnectedDevicesStartup (context: Context) {
        disconnect()
        disconnectAudio()
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        usbDevicePermissionHandler.requestPermissionsForDevices(deviceList.values.toList(), context)
    }

    private fun initDataToUsb (context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let {
                if(!BleManager.isUSBConnected || StardustInitConnectionHandler.hasUnsyncableError()) {
                    return@launch
                }
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
    }

    fun reconnectToDevice () {
            initDataToUsb(DataManager.context)
    }

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
            uartManagerAudio?.disconnect()
            uartManagerAudio = null
            audioDevice = null
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    fun sendDataToUart (bittelPackage: StardustPackage) {
//        Timber.tag("SerialInputOutputManager").d("uartManager.send $bittelPackage")
        context?.let { context ->
            uartManager?.send(bittelPackage.getStardustPackageToSend(context))
        }
    }


    private fun connectToAudioDevice (context: Context, device: UsbDevice) {
        if(!isConnectedAudio){
            Timber.tag("SerialInOutputManager").d("connectToAudioDevice : ${device.productName}")
            uartManagerAudio = UARTManager(context)
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
                    Timber.tag("SerialInOutputManager").d("onCTSChanged : $isActive")
                    Timber.tag("notifyData PTT").d("onCTSChanged : ${isActive}")
                    ButtonListener.notifyData(isActive, DataManager.context)
                }

            })
            if(connectionStatus == true) {}
        } else { }
    }

    private fun connectToDevice(context: Context, device: UsbDevice) {
        Timber.tag("SerialInOutputManager").d("connectToDevice : ${device.productName}")
        uartManager = UARTManager(context)
        val connectionStatus = uartManager?.connectDevice(object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                Timber.tag("SerialInOutputManager").d("onNewData : ${device.productName}")

                Timber.tag("SerialInOutputManager").d("onNewData uartManager")
//                Timber.tag("SerialInputOutputManager").d(data.toHex())
                // Handle incoming data
                processReceivedData(context, data)
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
                initDataToUsb(context)
                BleManager.updateStatus ()
            }
        }else {
            Timber.tag("SerialInOutputManager").d("cant connect")
        }
    }

    fun processReceivedData(context: Context, data: ByteArray) {
        try {
//            Timber.tag("SerialInputOutputManager").d("Received data: %s", "")
            StardustPackageUtils.handlePackageReceived(context, data, "USB")
        }catch (e : Exception) {
            e.printStackTrace()
        }
        // Process the data received from the device
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerReceiver(context: Context){
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(UsbDevicePermissionHandler.usbPermissionReceiver, filter, RECEIVER_EXPORTED)
        }else {
            context.registerReceiver(UsbDevicePermissionHandler.usbPermissionReceiver, filter)

        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun unregisterReceiver(context: Context){
//        context.unregisterReceiver(usbReceiver)
        context.unregisterReceiver(UsbDevicePermissionHandler.usbPermissionReceiver)
    }

    private fun getUartPortType(): StardustConfigurationParser.PortType {
        return when(ConfigurationUtils.bittelConfiguration.value?.portType) {
            StardustConfigurationParser.PortType.BLUETOOTH_DISABLED_BLE,
            StardustConfigurationParser.PortType.BLUETOOTH_DISABLED_USB -> StardustConfigurationParser.PortType.BLUETOOTH_DISABLED_USB
            else -> StardustConfigurationParser.PortType.BLUETOOTH_ENABLED_USB
        }
    }

    override fun updateBlePort() {
        context?.let {
            context ->
            SharedPreferencesUtil.getAppUser(context)?.let {
                val src = it.appId
                val dst = it.bittelId
                if(src != null && dst != null) {
                    val uartPort = getUartPortType().type.intToByteArray().reversedArray()
                    val data = StardustPackageUtils.byteArrayToIntArray(uartPort)
                    val txPackage = StardustPackageUtils.getStardustPackage(
                        context = context,
                        source = src ,
                        destenation = dst,
                        stardustOpCode =StardustPackageUtils.StardustOpCode.UPDATE_UART_PORT,
                        data = data)
                    DataManager.getClientConnection(context).addMessageToQueue(txPackage)
                }
            }
        }
    }

    override fun saveConfiguration() {
        context?.let {
            context ->
            SharedPreferencesUtil.getAppUser(context)?.let {
                val src = it.appId
                val dst = it.bittelId
                if(src != null && dst != null) {
                    val configurationSavePackage = StardustPackageUtils.getStardustPackage(
                        context = context,
                        source = src ,
                        destenation = dst,
                        stardustOpCode =StardustPackageUtils.StardustOpCode.SAVE_CONFIGURATION)
                    DataManager.getClientConnection(context)?.addMessageToQueue(configurationSavePackage)
                }
            }
        }
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