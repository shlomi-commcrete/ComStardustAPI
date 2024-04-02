package com.commcrete.stardust

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.MutableLiveData

interface StardustAPI {
    fun sendMessage (stardustAPIPackage: StardustAPIPackage, text : String)
    fun startPTT (stardustAPIPackage: StardustAPIPackage)
    fun stopPTT (stardustAPIPackage: StardustAPIPackage)
    fun sendLocation (stardustAPIPackage: StardustAPIPackage, location: Location)
    fun init(context: Context)
    fun scanForDevice() : MutableLiveData<List<ScanResult>>
    fun connectToDevice(device: BluetoothDevice)
    fun disconnectFromDevice()
}