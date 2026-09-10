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
            if (currentDevice?.productName?.contains("FT231X USB UART") == true|| currentDevice?.productName?.lowercase()?.contains("stardust") == true
                || currentDevice?.productName?.lowercase()?.contains("j-box") == true
                || currentDevice?.productName?.lowercase()?.contains("jbox") == true) {

                val appContext = context
                if (appContext == null) {
                    Timber.tag("UsbPermission").w("No context available, cannot request permission.")
                    isRequestingPermission = false
                    return
                }

                Timber.tag("SerialInputOutputManager").d("Requesting permission for device: ${currentDevice?.productName}")
                // UsbManager reports the result by writing EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED into
                // the Intent it sends through this PendingIntent, so the PendingIntent has to be MUTABLE
                // or those extras never arrive. From API 34 a mutable PendingIntent may not wrap an
                // implicit Intent, hence the explicit setPackage().
                val permissionIntent = PendingIntent.getBroadcast(
                    appContext, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                    pendingIntentFlags()
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
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: currentDevice
                    if (granted && device != null) {
                        Timber.tag("UsbPermission").d("Permission granted for device: ${device.productName}")
                        context?.let { connectToUnknownDevice(it, device) }
                    } else {
                        Timber.tag("UsbPermission").d("Permission denied for device: ${device?.productName}")
                    }
                    isRequestingPermission = false
                    requestNextPermission()
                }
            }
        }
    }

    private fun pendingIntentFlags(): Int {
        // FLAG_MUTABLE only exists from API 31; below that PendingIntents are mutable by default.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private var receiverRegistered = false

    /**
     * Single owner of the USB receiver registration. [BittelUsbManager2.registerReceiver] delegates
     * here rather than registering the same receiver instance a second time.
     */
    @Synchronized
    fun registerReceiverOnce(context: Context) {
        if (receiverRegistered) return
        val appContext = context.applicationContext
        this.context = appContext
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // NOT_EXPORTED is correct for every action in this filter: the two USB actions are protected
        // system broadcasts, and the permission result arrives via our own PendingIntent (same UID).
        // EXPORTED would let any app on the device spoof ACTION_USB_PERMISSION.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(usbPermissionReceiver, filter)
        }
        receiverRegistered = true
    }

    /**
     * Counterpart to [registerReceiverOnce]. Clearing [receiverRegistered] here is what allows the
     * receiver to be registered again after an ATAK plugin unload/reload cycle - these are `object`
     * singletons that outlive the plugin's own lifecycle.
     */
    @Synchronized
    fun unregisterReceiverOnce(context: Context) {
        if (!receiverRegistered) return
        val appContext = this.context ?: context.applicationContext
        try {
            appContext.unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.tag("UsbPermission").w("Receiver was not registered: ${e.message}")
        }
        receiverRegistered = false
    }
}