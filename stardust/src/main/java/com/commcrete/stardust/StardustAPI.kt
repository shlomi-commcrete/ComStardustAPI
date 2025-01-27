package com.commcrete.stardust

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.FileSendUtils
import java.io.File

interface StardustAPI {

    // Send to the SDK
    fun sendMessage (context: Context,stardustAPIPackage: StardustAPIPackage, text : String)
    fun startPTT (context: Context,stardustAPIPackage: StardustAPIPackage)
    fun stopPTT (context: Context,stardustAPIPackage: StardustAPIPackage)
    fun sendLocation (context: Context,stardustAPIPackage: StardustAPIPackage, location: Location)
    fun sendImage (context: Context,stardustAPIPackage: StardustAPIPackage, file: File, onFileStatusChange: FileSendUtils.OnFileStatusChange)
    fun sendFile (context: Context,stardustAPIPackage: StardustAPIPackage, file: File, onFileStatusChange: FileSendUtils.OnFileStatusChange)
    fun stopSendFile (context: Context,)
    fun requestLocation (context: Context,stardustAPIPackage: StardustAPIPackage)
    fun sendSOS (context: Context,stardustAPIPackage: StardustAPIPackage, location: Location, type : Int)
    fun init(context: Context, fileLocation : String)
    fun scanForDevice(context: Context,) : MutableLiveData<List<ScanResult>>
    fun connectToDevice(context: Context,device: ScanResult)
    fun disconnectFromDevice(context: Context,)
    fun readChats (context: Context,) : LiveData<List<ChatItem>>
    fun logout(context: Context,)
    fun setCallback (stardustAPICallbacks: StardustAPICallbacks)
    fun getCarriers (context: Context) : List<Carrier>?
}

// Receive from the SDK
interface StardustAPICallbacks {
    fun receiveMessage (stardustAPIPackage: StardustAPIPackage, text : String)
    fun receiveLocation (stardustAPIPackage: StardustAPIPackage, location: Location)
    fun receiveSOS (stardustAPIPackage: StardustAPIPackage, location: Location, type : Int)
    fun receivePTT (stardustAPIPackage: StardustAPIPackage, byteArray : ByteArray)
    fun receiveImage (stardustAPIPackage: StardustAPIPackage, file: File)
    fun receiveFile (stardustAPIPackage: StardustAPIPackage, file: File)
    fun receiveFileStatus (percentage : Int)
    fun connectionStatusChanged (connectionStatus: BleManager.ConnectionStatus)
}