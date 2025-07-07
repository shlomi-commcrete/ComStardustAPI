package com.commcrete.stardust.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        UsbDevicePermissionHandler.handlePermissionIntent(context, intent)
    }
}