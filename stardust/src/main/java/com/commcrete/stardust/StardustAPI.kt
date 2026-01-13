package com.commcrete.stardust

import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.enums.ConnectionType
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.FileReceivedUtils
import com.commcrete.stardust.util.FileSendUtils
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import java.io.File

interface StardustAPI {

    // Send to the SDK
    fun sendMessage (context: Context,stardustAPIPackage: StardustAPIPackage, text : String)
    fun startPTT (context: Context,stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE): File?
    fun stopPTT (context: Context,stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE, file: File?)
    fun sendLocation (context: Context,stardustAPIPackage: StardustAPIPackage, location: Location)
    fun sendImage (context: Context,stardustAPIPackage: StardustAPIPackage, file: File, onFileStatusChange: FileSendUtils.OnFileStatusChange, fileName : String, fileExt : String)
    fun sendFile (context: Context,stardustAPIPackage: StardustAPIPackage, file: File, onFileStatusChange: FileSendUtils.OnFileStatusChange, fileName : String, fileExt : String)
    fun stopSendFile (context: Context,)
    fun requestLocation (context: Context,stardustAPIPackage: StardustAPIPackage)
    fun sendSOS (context: Context,stardustAPIPackage: StardustAPIPackage, location: Location, type : Int)
    fun init(context: Context, fileLocation : String)
    fun scanForDevice(context: Context,) : MutableLiveData<List<ScanResult>>
    fun connectToDevice(context: Context,device: ScanResult)
    fun disconnectFromDevice(context: Context,)
    fun readChats (context: Context,) : LiveData<List<ChatItem>>
    fun logout(context: Context)
    fun setCallback (stardustAPICallbacks: StardustAPICallbacks)
    fun getCarriers (context: Context) : List<Carrier>?
    fun sendRealSOS(context: Context, stardustAPIPackage: StardustAPIPackage, location: Location)
    fun AckSOS(context: Context, stardustAPIPackage: StardustAPIPackage)
    fun setSecurityKey(context: Context, key: String, name : String)
    fun setSecurityKeyDefault(context: Context)
    fun getSecurityKey(context: Context) : ByteArray
    fun reconnectToCurrentDevice (context: Context)
    fun canRecord (context: Context) : MutableLiveData<Boolean>
}

// Receive from the SDK
interface StardustAPICallbacks {
    fun pttMaxTimeoutReached ()
    fun receiveMessage (stardustAPIPackage: StardustAPIPackage, text : String)
    fun receiveLocation (stardustAPIPackage: StardustAPIPackage, location: Location)
    fun receiveSOS (stardustAPIPackage: StardustAPIPackage, location: Location, type : Int)
    fun receiveRealSOS (stardustAPIPackage: StardustAPIPackage, location: Location)
    fun handleSOSAck (stardustAPIPackage: StardustAPIPackage)
    fun receivePTT (stardustAPIPackage: StardustAPIPackage, byteArray : ByteArray)
    fun startedReceivingPTT (stardustAPIPackage: StardustAPIPackage, file: File)
    fun receiveImage (stardustAPIPackage: StardustAPIPackage, file: File)
    fun receiveFile (stardustAPIPackage: StardustAPIPackage, file: File)
    fun receiveFileStatus (
        index: Int,
        percentage: Int,
        source: String,
        destination: String,
        fileName: String,
        fileEnding: String,
        fileType: FileUtils.FileType
    )
    fun connectionStatusChanged (connectionType: ConnectionType?)
    fun onRSSIChanged (rssi : Int)
    fun onBatteryChanged (battery : Int)
    fun onAppEvent (stardustAppEventPackage: StardustAppEventPackage)
    fun onPermissionDenied (deviceName : String)
    fun receiveFailure (
        failure: FileReceivedUtils.FileReceivedData.FileFailure,
        dataStart: StardustFileStartPackage?
    )
    fun onDeviceInitialized(state: StardustInitConnectionHandler.State)
}