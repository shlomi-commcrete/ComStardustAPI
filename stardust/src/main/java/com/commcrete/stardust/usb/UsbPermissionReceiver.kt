package com.commcrete.stardust.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.commcrete.stardust.util.connectivity.InboundWakeLock

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Attach/detach: the system keeps the CPU up only while onReceive runs, and the handling
        // below continues on coroutines after it returns.
        context?.let { InboundWakeLock.onInbound(it) }
        // Logged BEFORE any handler logic runs: this line appearing at all is the proof that the
        // broadcast was delivered. Its ABSENCE after the user taps "Allow" is the fingerprint of
        // the implicit-intent delivery failure on Android 14+ (fix #1 defect B).
        UsbDiag.log("Receiver.onReceive", "ENTER action=${intent?.action} contextNull=${context == null}")
        UsbDiag.describeIntent("Receiver.onReceive", intent)
        UsbDevicePermissionHandler.handlePermissionIntent(intent)
        UsbDiag.log("Receiver.onReceive", "EXIT action=${intent?.action}")
    }
}