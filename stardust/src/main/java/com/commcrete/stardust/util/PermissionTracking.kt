package com.commcrete.stardust.util

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import pub.devrel.easypermissions.EasyPermissions

object PermissionTracking {

    fun hasCOntactPermissions(context: Context):Boolean =
        EasyPermissions.hasPermissions(
            context,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
        )

    fun hasBlePermissions(context: Context):Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            EasyPermissions.hasPermissions(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            EasyPermissions.hasPermissions(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }


    fun hasMicrophonePermission(context: Context):Boolean =
        EasyPermissions.hasPermissions(
            context,
            android.Manifest.permission.RECORD_AUDIO
        )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun hasNotificationPermission(context: Context):Boolean =
        EasyPermissions.hasPermissions(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    fun hasLocationPermission(context: Context):Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            EasyPermissions.hasPermissions(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        } else {
            EasyPermissions.hasPermissions(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    fun hasLocationPermissionNoBackground(context: Context):Boolean = EasyPermissions.hasPermissions(
        context,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasLocationPermissionForeground(context: Context):Boolean =
        EasyPermissions.hasPermissions(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
}