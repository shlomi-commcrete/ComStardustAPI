package com.commcrete.stardust

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.room.chats.ChatItem

interface StardustAPI {

    // Send to the SDK
    fun sendMessage (stardustAPIPackage: StardustAPIPackage, text : String)
    fun startPTT (stardustAPIPackage: StardustAPIPackage)
    fun stopPTT (stardustAPIPackage: StardustAPIPackage)
    fun sendLocation (stardustAPIPackage: StardustAPIPackage, location: Location)
    fun sendSOS (stardustAPIPackage: StardustAPIPackage, location: Location, type : Int)
    fun init(context: Context, fileLocation : String)
    fun scanForDevice() : MutableLiveData<List<ScanResult>>
    fun connectToDevice(device: BluetoothDevice)
    fun disconnectFromDevice()
    fun readChats () : LiveData<List<ChatItem>>

    // Receive from the SDK
    fun setCallback (stardustAPICallbacks: StardustAPICallbacks)
}

interface StardustAPICallbacks {
    fun receiveMessage (stardustAPIPackage: StardustAPIPackage, text : String)

    fun receiveLocation (stardustAPIPackage: StardustAPIPackage, location: Location)

    fun receiveSOS (stardustAPIPackage: StardustAPIPackage, location: Location, type : Int)

    fun receivePTT (stardustAPIPackage: StardustAPIPackage, byteArray : ByteArray)

}