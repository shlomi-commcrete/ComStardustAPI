package com.commcrete.stardust.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Central runtime-permission checks for BLE (B5 fix).
 *
 * On Android 12+ (API 31), GATT/device operations require the runtime `BLUETOOTH_CONNECT`
 * permission and scanning requires `BLUETOOTH_SCAN`; calling them without the grant throws
 * `SecurityException`. The SDK relies on the host app to request these, but must not CRASH when
 * they're missing — callers gate on these helpers and fail gracefully instead.
 */
object BlePermissions {

    /** Whether `BLUETOOTH_CONNECT` is held (always true pre-Android-12, where it isn't a runtime grant). */
    fun hasConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Whether `BLUETOOTH_SCAN` is held (always true pre-Android-12). */
    fun hasScanPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
