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
import timber.log.Timber
import java.util.LinkedList
import java.util.Locale
import java.util.Queue

object UsbDevicePermissionHandler {

    private val devicesQueue: Queue<UsbDevice> = LinkedList()
    private var isRequestingPermission: Boolean = false
    private var currentDevice: UsbDevice? = null
    private var context: Context? = null
    val usbPermissionReceiver : UsbPermissionReceiver = UsbPermissionReceiver()

    private val handler = Handler(Looper.getMainLooper())
    private val permissionTimeoutRunnable = Runnable {
        Timber.tag("UsbPermission").d("Permission request timeout.")
        isRequestingPermission = false
        requestNextPermission()
    }

    fun requestPermissionsForDevices(devices: List<UsbDevice>, context: Context) {
        this.context = context.applicationContext
        devicesQueue.addAll(devices)
        registerReceiverOnce(context)
        if (!isRequestingPermission) {
            requestNextPermission()
        }
    }

    private fun requestNextPermission() {
        if (devicesQueue.isNotEmpty()) {
            isRequestingPermission = true
            currentDevice = devicesQueue.poll()
            if (currentDevice?.productName?.contains("FT231X USB UART") == true|| currentDevice?.productName?.toLowerCase()?.contains("stardust") == true
                || currentDevice?.productName?.toLowerCase()?.contains("j-box") == true
                || currentDevice?.productName?.toLowerCase()?.contains("jbox") == true) {

                Timber.tag("SerialInputOutputManager").d("Requesting permission for device: ${currentDevice?.productName}")
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0,
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

    fun handlePermissionIntent(context: Context?, intent: Intent?) {
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
                    context?.let { disconnectToUnknownDevice(it, device) }
                }
            }

            ACTION_USB_PERMISSION -> {
                synchronized(this) {
                    handler.removeCallbacks(permissionTimeoutRunnable)
                    currentDevice?.let { device ->
                        Timber.tag("UsbPermission").d("Permission granted for device: ${device.productName}")
                        context?.let { connectToUnknownDevice(it, device) }
                    }
                    isRequestingPermission = false
                    requestNextPermission()
                }
            }
        }
    }

    private var receiverRegistered = false
    private fun registerReceiverOnce(context: Context) {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(usbPermissionReceiver, filter)
            }
            receiverRegistered = true
        }
    }
}