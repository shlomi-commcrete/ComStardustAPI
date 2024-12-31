package com.commcrete.stardust.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.usb.BittelUsbManager2.ACTION_USB_PERMISSION
import com.commcrete.stardust.usb.BittelUsbManager2.connectToUnknownDevice
import com.commcrete.stardust.usb.BittelUsbManager2.disconnectToUnknownDevice
import com.commcrete.stardust.usb.BittelUsbManager2.usbManager
import com.commcrete.stardust.util.DataManager
import timber.log.Timber
import java.util.LinkedList
import java.util.Queue

object UsbDevicePermissionHandler {

    private val devicesQueue: Queue<UsbDevice> = LinkedList()
    private var isRequestingPermission: Boolean = false
    private var currentDevice: UsbDevice? = null
    private var context : Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val permissionTimeoutRunnable = Runnable {
        // Reset the isRequestingPermission flag after timeout
        Timber.tag("UsbPermission").d("Permission request timeout. Resetting isRequestingPermission.")
        isRequestingPermission = false
        requestNextPermission()
    }
    val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // A new USB device is attached, add it to the queue and request permission
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        Timber.tag("UsbPermission").d("Device attached: ${device.productName}")
                        devicesQueue.add(device)
                        if (!isRequestingPermission) {
                            requestNextPermission()
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // A new USB device is attached, add it to the queue and request permission
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        Timber.tag("UsbPermission").d("Device detached: ${device.productName}")
                        context?.let { disconnectToUnknownDevice(it, device) }
                    }
                }

                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        if (currentDevice != null) {
                            // Cancel the timeout runnable since we received a response
                            handler.removeCallbacks(permissionTimeoutRunnable)

//                            if (permissionGranted) {
                                // Permission granted for this device
                                Timber.tag("UsbPermission").d("Permission granted for device: ${currentDevice?.productName}")
                            context?.let { connectToUnknownDevice(it, currentDevice!!) }
                                // Handle the connected device as needed
//                            } else {
                                // Permission denied
//                                Timber.tag("UsbPermission").d("Permission denied for device: ${currentDevice?.productName}")
//                            }
                        }

                        // Proceed to request permission for the next device, if any
                        isRequestingPermission = false
                        requestNextPermission()
                    }
                }

            }
        }
    }
    fun requestPermissionsForDevices(devices: List<UsbDevice>, context: Context) {
        // Add devices to the queue
        this.context = context
        devicesQueue.addAll(devices)
        // Start requesting permission for the first device in the queue
        if (!isRequestingPermission) {
            requestNextPermission()
        }
    }

    private fun requestNextPermission() {
        if (devicesQueue.isNotEmpty()) {
            isRequestingPermission = true
            currentDevice = devicesQueue.poll()
            if (currentDevice?.productName?.contains("FT231X USB UART") == true) {
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

}