package com.commcrete.stardust.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.usb.BittelUsbManager2.ACTION_USB_PERMISSION
import com.commcrete.stardust.usb.BittelUsbManager2.connectToUnknownDevice
import com.commcrete.stardust.usb.BittelUsbManager2.disconnectToUnknownDevice
import com.commcrete.stardust.usb.BittelUsbManager2.usbManager
import com.commcrete.stardust.util.DataManager
import timber.log.Timber
import java.util.LinkedList
import java.util.Locale
import java.util.Queue

object UsbDevicePermissionHandler {

    private val devicesQueue: Queue<UsbDevice> = LinkedList()
    private var isRequestingPermission: Boolean = false
    private var currentDevice: UsbDevice? = null
    val usbPermissionReceiver : UsbPermissionReceiver = UsbPermissionReceiver()

    private val handler = Handler(Looper.getMainLooper())
    private val permissionTimeoutRunnable = Runnable {
        Timber.tag("UsbPermission").d("Permission request timeout.")
        isRequestingPermission = false
        requestNextPermission()
    }

    fun requestPermissionsForDevices(devices: List<UsbDevice>) {
        devicesQueue.addAll(devices)
        registerReceiverOnce()
        if (!isRequestingPermission) {
            requestNextPermission()
        }
    }

    private fun requestNextPermission() {
        if (devicesQueue.isNotEmpty()) {
            isRequestingPermission = true
            currentDevice = devicesQueue.poll()
            val productName = currentDevice?.productName?.lowercase()
            if (currentDevice?.productName?.contains("FT231X USB UART") == true|| productName?.contains("stardust") == true
                || productName?.contains("j-box") == true
                || productName?.contains("jbox") == true) {

                Timber.tag("SerialInputOutputManager").d("Requesting permission for device: ${currentDevice?.productName}")
                val permissionIntent = PendingIntent.getBroadcast(
                    DataManager.appContext, 0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
                )
                usbManager?.requestPermission(currentDevice, permissionIntent)

                // Schedule a timeout to reset isRequestingPermission after 10 seconds
                handler.postDelayed(permissionTimeoutRunnable, 10000)
            } else {
                // If the device is null or does not match the condition, continue with the next device
                requestNextPermission()
            }
        } else {
            isRequestingPermission = false
        }
    }

    fun handlePermissionIntent(intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { device ->
                    Timber.tag("UsbPermission").d("Device attached: ${device.productName}")
                    devicesQueue.add(device)
                    if (!isRequestingPermission) {
                        requestNextPermission()
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { device ->
                    Timber.tag("UsbPermission").d("Device detached: ${device.productName}")
                    disconnectToUnknownDevice(device)
                }
            }

            ACTION_USB_PERMISSION -> {
                synchronized(this) {
                    handler.removeCallbacks(permissionTimeoutRunnable)
                    currentDevice?.let { device ->
                        Timber.tag("UsbPermission").d("Permission granted for device: ${device.productName}")
                        connectToUnknownDevice(device)
                    }
                    isRequestingPermission = false
                    requestNextPermission()
                }
            }
        }
    }

    private var receiverRegistered = false
    private fun registerReceiverOnce() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                DataManager.appContext.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                DataManager.appContext.registerReceiver(usbPermissionReceiver, filter)
            }
            receiverRegistered = true
        }
    }
}