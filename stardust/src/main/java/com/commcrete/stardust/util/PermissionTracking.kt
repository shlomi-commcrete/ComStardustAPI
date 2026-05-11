package com.commcrete.stardust.util


import android.os.Build
import androidx.annotation.RequiresApi
import pub.devrel.easypermissions.EasyPermissions

object PermissionTracking {

    fun hasCOntactPermissions():Boolean =
        EasyPermissions.hasPermissions(
            DataManager.appContext,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
        )

    fun hasBlePermissions():Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            EasyPermissions.hasPermissions(
                DataManager.appContext,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            EasyPermissions.hasPermissions(
                DataManager.appContext,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }


    fun hasMicrophonePermission():Boolean =
        EasyPermissions.hasPermissions(
            DataManager.appContext,
            android.Manifest.permission.RECORD_AUDIO
        )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun hasNotificationPermission():Boolean =
        EasyPermissions.hasPermissions(
            DataManager.appContext,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    fun hasLocationPermission():Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            EasyPermissions.hasPermissions(
                DataManager.appContext,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        } else {
            EasyPermissions.hasPermissions(
                DataManager.appContext,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    fun hasLocationPermissionNoBackground():Boolean = EasyPermissions.hasPermissions(
        DataManager.appContext,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasLocationPermissionForeground():Boolean =
        EasyPermissions.hasPermissions(
            DataManager.appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
}