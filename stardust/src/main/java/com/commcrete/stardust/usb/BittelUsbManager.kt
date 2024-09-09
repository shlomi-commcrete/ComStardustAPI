package com.commcrete.bittell.util.bittel_package

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.BittelProtocol
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

object BittelUsbManager : BittelProtocol {

    var usbManager : UsbManager? = null

    private const val ACTION_USB_PERMISSION = "com.android.commcrete.USB_PERMISSION"

    private var isConnected = false;
    private var uartManager : UARTManager? = null

    private var context : Context? = null

    internal val clientConnection : ClientConnection = DataManager.getClientConnection(DataManager.context)

    fun init(context: Context) {
        this.context = context
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        startPollingForUsbDevices(context)
    }

    fun startPollingForUsbDevices(context: Context) {
        // Start a background coroutine to poll for connected devices
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                checkConnectedUsbDevices(context)
                delay(5000) // Poll every 5 seconds (adjust the interval as needed)
            }
        }
    }

    fun checkConnectedUsbDevices(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList

        deviceList.values.forEach { device ->
            if (!usbManager.hasPermission(device)) {
                requestPermission(device)
            } else {
                Timber.tag("USB").d("Device already has permission: ${device.deviceName}")
            }
        }
    }


    fun getConnectedDevices (context: Context) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        deviceList.values.forEach { device ->
            Timber.tag("SerialInputOutputManager").d("USB Device : ${device.deviceName}")
            connectToDevice(context)
//           requestPermission(device)
        }
    }

    private fun initDataToUsb () {
        CoroutineScope(Dispatchers.Default).launch {
            delay(2000)
            var output = 0
            while (output < 1){
                delay(100)
                val mPackage = StardustPackageUtils.getStardustPackage(
                    source = "0" , destenation = "1", stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADDRESS)
                Timber.tag("SerialInputOutputManager").d("uartManager.send")
                sendDataToUart(mPackage)
                delay(2000)
                updateBlePort()
                delay(2000)
                saveConfiguration()
                output++
            }
        }
    }

    fun disconnect () {
        isConnected = false
        uartManager?.disconnect()
        BleManager.isUSBConnected = false
        BleManager.usbConnectionStatus.value = false
    }

    fun sendDataToUart (stardustPackage: StardustPackage) {
        uartManager?.send(stardustPackage.getStardustPackageToSend())
    }

    private fun connectToDevice(context: Context) {
        if(!isConnected){
            uartManager  = UARTManager(context)
            uartManager?.connectDevice(object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    Scopes.getMainCoroutine().launch {
                        BleManager.isUSBConnected = true
                        BleManager.usbConnectionStatus.value = true
                    }
                    Timber.tag("SerialInputOutputManager").d("onNewData")
                    Timber.tag("SerialInputOutputManager").d(data.toHex())
                    processReceivedData(data)
                    // Handle incoming data
                }

                override fun onRunError(e: Exception) {
                    Timber.tag("SerialInputOutputManager").d("onRunError")
                    Timber.tag("SerialInputOutputManager").d(e.message)
                    // Handle errors
                }
            })
            isConnected = true
            initDataToUsb()
        }
    }

    fun processReceivedData(data: ByteArray) {
        try {
            Timber.tag("SerialInputOutputManager").d("Received data: %s", data.decodeToString())
            StardustPackageUtils.handlePackageReceived(data)
        }catch (e : Exception) {
            e.printStackTrace()
        }
        // Process the data received from the device
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Timber.tag("SerialInputOutputManager").d("onReceive")
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                Timber.tag("SerialInputOutputManager").d("UsbManager.ACTION_USB_DEVICE_ATTACHED")
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (device != null) {
                    Timber.tag("SerialInputOutputManager").d("Found device")
                    requestPermission(device)
                }else {
                    Timber.tag("SerialInputOutputManager").d("Device not found")
                }
            }else if (intent.action == ACTION_USB_PERMISSION) {
                Timber.tag("SerialInputOutputManager").d("ACTION_USB_PERMISSION")
                getConnectedDevices(context)
            }else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                Timber.tag("SerialInputOutputManager").d("UsbManager.ACTION_USB_DEVICE_DETACHED")
                disconnect()
            }
        }
    }

    fun reconnectToDevice () {
        disconnect()
        this.context?.let { checkConnectedUsbDevices(it) }
    }

    fun requestPermission (device: UsbDevice) {
        if(device.productName == "FT231X USB UART"){
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(
                ACTION_USB_PERMISSION
            ), PendingIntent.FLAG_IMMUTABLE)
            usbManager?.requestPermission(device, permissionIntent)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerReceiver(context: Context){
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        }else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    override fun updateBlePort() {
        SharedPreferencesUtil.getAppUser(context!!)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val uartPort = (StardustConfigurationParser.PortType.USB.type).intToByteArray().reversedArray()
                val data = StardustPackageUtils.byteArrayToIntArray(uartPort)
                val txPackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode =StardustPackageUtils.StardustOpCode.UPDATE_UART_PORT,
                    data = data)
                clientConnection?.addMessageToQueue(txPackage)
            }
        }
    }

    override fun saveConfiguration() {
        SharedPreferencesUtil.getAppUser(context!!)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val configurationSavePackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode =StardustPackageUtils.StardustOpCode.SAVE_CONFIGURATION)
                clientConnection?.addMessageToQueue(configurationSavePackage)
            }
        }
    }
}