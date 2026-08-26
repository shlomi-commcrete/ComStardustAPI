package com.commcrete.stardust.usb

import android.app.PendingIntent
import android.content.Context.RECEIVER_NOT_EXPORTED
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
import java.util.Queue

object UsbDevicePermissionHandler {

    private val devicesQueue: Queue<UsbDevice> = LinkedList()
    private var isRequestingPermission: Boolean = false
    private var currentDevice: UsbDevice? = null
    val usbPermissionReceiver : UsbPermissionReceiver = UsbPermissionReceiver()

    private val handler = Handler(Looper.getMainLooper())
    private val permissionTimeoutRunnable = Runnable {
        Timber.tag("UsbPermission").d("Permission request timeout.")
        // A timeout means the ACTION_USB_PERMISSION broadcast never came back within 10s. Either
        // the user ignored the dialog, or the broadcast was never delivered (fix #1 defect B).
        // Compare with Receiver.onReceive: if that never logged, delivery is the problem.
        UsbDiag.warn(
            "timeout",
            "PERMISSION TIMEOUT after 10s for ${UsbDiag.describe(currentDevice)} " +
                "hasPermissionNow=${UsbDiag.hasPermission(currentDevice)}"
        )
        if (UsbDiag.hasPermission(currentDevice) == true) {
            UsbDiag.verdict(
                "Timed out waiting for the permission broadcast, but UsbManager.hasPermission is " +
                    "already TRUE — the grant happened and the callback never arrived. " +
                    "Delivery failure, not a user denial (fix #1 defect B)."
            )
        }
        isRequestingPermission = false
        requestNextPermission()
    }

    fun requestPermissionsForDevices(devices: List<UsbDevice>) {
        UsbDiag.env("requestPermissionsForDevices")
        UsbDiag.log(
            "requestPermissionsForDevices",
            "ENTER incoming=${devices.size} queueBefore=${devicesQueue.size} " +
                "isRequestingPermission=$isRequestingPermission"
        )
        devices.forEachIndexed { i, d ->
            UsbDiag.log("requestPermissionsForDevices", "  incoming[$i] ${UsbDiag.describe(d)} hasPermission=${UsbDiag.hasPermission(d)}")
            UsbDiag.match("requestPermissionsForDevices[$i]", d)
        }
        devicesQueue.addAll(devices)
        registerReceiverOnce()
        if (!isRequestingPermission) {
            requestNextPermission()
        } else {
            UsbDiag.log("requestPermissionsForDevices", "not pumping queue — a permission request is already in flight")
        }
    }

    private fun requestNextPermission() {
        UsbDiag.log("requestNextPermission", "ENTER queueSize=${devicesQueue.size}")
        if (devicesQueue.isNotEmpty()) {
            isRequestingPermission = true
            currentDevice = devicesQueue.poll()
            UsbDiag.log("requestNextPermission", "polled ${UsbDiag.describe(currentDevice)}")
            UsbDiag.match("requestNextPermission", currentDevice)

            val productName = currentDevice?.productName?.lowercase()
            if (currentDevice?.productName?.contains("FT231X USB UART") == true|| productName?.contains("stardust") == true
                || productName?.contains("j-box") == true
                || productName?.contains("jbox") == true) {

                Timber.tag("SerialInputOutputManager").d("Requesting permission for device: ${currentDevice?.productName}")

                // If the grant is already persisted ("always allow for this device"), the whole
                // broadcast round-trip is unnecessary — and the fact that we still go through it
                // is why a broken broadcast blocks even a previously-authorised device.
                UsbDiag.log(
                    "requestNextPermission",
                    "about to requestPermission: hasPermissionAlready=${UsbDiag.hasPermission(currentDevice)} " +
                        "usbManagerNull=${usbManager == null}"
                )
                if (usbManager == null) {
                    UsbDiag.verdict(
                        "BittelUsbManager2.usbManager is NULL — requestPermission() will silently " +
                            "no-op and this will look like a 10s timeout. BittelUsbManager2.init() " +
                            "was never called (or ran before DataManager.init). See fix #1(c)."
                    )
                }

                val permissionFlags = PendingIntent.FLAG_IMMUTABLE
                val requestIntent = Intent(ACTION_USB_PERMISSION)
                val permissionIntent = PendingIntent.getBroadcast(
                    DataManager.appContext, 0,
                    requestIntent,
                    permissionFlags
                )
                // Prints implicit=/isImmutable= and raises a VERDICT for each defect it finds.
                UsbDiag.describePendingIntent("requestNextPermission", requestIntent, permissionIntent, permissionFlags)

                usbManager?.requestPermission(currentDevice, permissionIntent)
                UsbDiag.log("requestNextPermission", "requestPermission() returned — now waiting for ACTION_USB_PERMISSION (10s timeout armed)")

                // Schedule a timeout to reset isRequestingPermission after 10 seconds
                handler.postDelayed(permissionTimeoutRunnable, 10000)
            } else {
                // If the device is null or does not match the condition, continue with the next device
                UsbDiag.warn(
                    "requestNextPermission",
                    "SKIPPED by the name filter — no permission will be requested for " +
                        "${UsbDiag.describe(currentDevice)}"
                )
                requestNextPermission()
            }
        } else {
            UsbDiag.log("requestNextPermission", "queue drained — isRequestingPermission=false")
            isRequestingPermission = false
        }
    }

    fun handlePermissionIntent(intent: Intent?) {
        UsbDiag.log("handlePermissionIntent", "action=${intent?.action}")
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { device ->
                    Timber.tag("UsbPermission").d("Device attached: ${device.productName}")
                    UsbDiag.log("ATTACHED", UsbDiag.describe(device))
                    UsbDiag.match("ATTACHED", device)
                    UsbDiag.linkState("ATTACHED")
                    devicesQueue.add(device)
                    if (!isRequestingPermission) {
                        requestNextPermission()
                    } else {
                        UsbDiag.log("ATTACHED", "queued behind an in-flight permission request")
                    }
                } ?: UsbDiag.warn("ATTACHED", "EXTRA_DEVICE was null on an ATTACHED broadcast")
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { device ->
                    Timber.tag("UsbPermission").d("Device detached: ${device.productName}")
                    UsbDiag.log("DETACHED", UsbDiag.describe(device))
                    UsbDiag.match("DETACHED", device)
                    UsbDiag.log(
                        "DETACHED",
                        "pendingQueue=${devicesQueue.size} currentDevice=${currentDevice?.deviceId} " +
                            "isRequestingPermission=$isRequestingPermission (a pending request for this " +
                            "device is NOT purged — see fix #10.8)"
                    )
                    disconnectToUnknownDevice(device)
                    UsbDiag.linkState("DETACHED.after")
                } ?: UsbDiag.warn("DETACHED", "EXTRA_DEVICE was null on a DETACHED broadcast")
            }

            ACTION_USB_PERMISSION -> {
                synchronized(this) {
                    handler.removeCallbacks(permissionTimeoutRunnable)
                    // The broadcast carries the user's actual answer; we used to connect
                    // unconditionally, so a DENIED result looked identical to a granted one and
                    // then failed opaquely inside openDevice().
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    android.util.Log.d("ConfigDebug",
                        "ACTION_USB_PERMISSION granted=$granted currentDevice='${currentDevice?.productName}'"
                    )
                    // Cross-checks `granted` against UsbManager.hasPermission() — the single most
                    // decisive line in this whole investigation. See UsbDiag.permissionResult.
                    UsbDiag.permissionResult("PERMISSION_RESULT", intent, granted, currentDevice)

                    if (currentDevice == null) {
                        android.util.Log.w("ConfigDebug",
                            "ACTION_USB_PERMISSION but currentDevice is NULL (timed out / already drained) — nothing to connect"
                        )
                        UsbDiag.verdict(
                            "currentDevice is NULL when the permission result arrived — the 10s timeout " +
                                "already fired and drained the queue without clearing currentDevice. " +
                                "The grant is discarded."
                        )
                    }
                    currentDevice?.let { device ->
                        if (granted) {
                            Timber.tag("UsbPermission").d("Permission granted for device: ${device.productName}")
                            UsbDiag.log("PERMISSION_RESULT", "→ connectToUnknownDevice ${UsbDiag.describe(device)}")
                            connectToUnknownDevice(device)
                        } else {
                            android.util.Log.w("ConfigDebug", "USB permission DENIED for '${device.productName}' — not connecting")
                            UsbDiag.warn("PERMISSION_RESULT", "NOT connecting — code read the result as denied")
                        }
                    }
                    isRequestingPermission = false
                    requestNextPermission()
                }
            }

            else -> UsbDiag.warn("handlePermissionIntent", "unhandled action=${intent?.action}")
        }
    }

    private var receiverRegistered = false

    /**
     * Registers the USB broadcast receiver exactly once. This is the single registration point for
     * [usbPermissionReceiver] — [BittelUsbManager2.registerReceiver] delegates here rather than
     * registering the same instance a second time.
     *
     * Uses RECEIVER_NOT_EXPORTED: ACTION_USB_PERMISSION is a private action and USB attach/detach
     * are protected system broadcasts, so no other app should be able to deliver these to us.
     */
    fun registerReceiverOnce() {
        if (receiverRegistered) {
            UsbDiag.log("registerReceiverOnce", "already registered — no-op")
            return
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                DataManager.appContext.registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
                UsbDiag.log(
                    "registerReceiverOnce",
                    "registered with RECEIVER_NOT_EXPORTED for [$ACTION_USB_PERMISSION, ATTACHED, DETACHED] " +
                        "— NOT_EXPORTED requires the permission broadcast to be package-scoped on Android 14+"
                )
            } else {
                DataManager.appContext.registerReceiver(usbPermissionReceiver, filter)
                UsbDiag.log("registerReceiverOnce", "registered (pre-Tiramisu, no export flag)")
            }
            receiverRegistered = true
        } catch (e: Exception) {
            UsbDiag.error("registerReceiverOnce", "registerReceiver FAILED — no USB event will ever arrive", e)
            throw e
        }
        UsbDiag.env("registerReceiverOnce")
    }

    /** Unregisters the receiver and resets the guard so a later [registerReceiverOnce] works again. */
    fun unregister() {
        if (!receiverRegistered) {
            UsbDiag.log("unregister", "not registered — no-op")
            return
        }
        try {
            DataManager.appContext.unregisterReceiver(usbPermissionReceiver)
            UsbDiag.log("unregister", "receiver unregistered — USB events will NO LONGER arrive")
        } catch (e: Exception) {
            Timber.tag("UsbPermission").w(e, "unregister receiver failed")
            UsbDiag.error("unregister", "unregisterReceiver failed", e)
        }
        receiverRegistered = false
    }

    /** Diagnostics helper: whether the broadcast receiver is currently registered. */
    internal fun isReceiverRegistered(): Boolean = receiverRegistered
}
