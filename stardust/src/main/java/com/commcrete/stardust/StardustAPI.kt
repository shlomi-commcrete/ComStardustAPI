package com.commcrete.stardust

import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.enums.ConnectionType
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.model.SOSPackage
import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.FileReceiver
import com.commcrete.stardust.util.FileSender
import com.commcrete.stardust.util.FileUtils.FileTransferData
import com.commcrete.stardust.util.SOSUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.Deferred
import java.io.File

interface StardustAPI {

    // Send to the SDK
    fun sendMessage(chatId: String, stardustAPIPackage: StardustAPIPackage, text : String)
    fun startPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.AudioEncoderType): File?
    fun stopPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.AudioEncoderType, file: File)
    fun sendLocation(chatId: String, stardustAPIPackage: StardustAPIPackage, location: Location)
    fun sendImage(data: FileTransferData.Send, onFileStatusChange: FileSender.OnFileStatusChange): Deferred<Boolean>
    fun sendFile(data: FileTransferData.Send, onFileStatusChange: FileSender.OnFileStatusChange): Deferred<Boolean>
    fun stopSendFile(data: FileTransferData.Send)
    fun requestLocation(stardustAPIPackage: StardustAPIPackage)
    fun sendSOS(stardustAPIPackage: StardustAPIPackage, location: Location, type: SOSUtils.SOS_REPORT_TYPES?)
    fun init(appContext: Context, pluginContext: Context, fileLocation : String)
    fun scanForDevice(): MutableLiveData<List<ScanResult>>
    fun connectToDevice(device: ScanResult)
    fun disconnectFromDevice()
    fun logout()
    fun setCallback(stardustAPICallbacks: StardustAPICallbacks)
    fun getCarriers (): List<Carrier>?
    fun sendRealSOS(location: Location)
    fun AckSOS(stardustAPIPackage: StardustAPIPackage)
    fun setSecurityKey(key: String, name : String)
    fun setSecurityKeyDefault()
    fun getSecurityKey(): ByteArray
    fun reconnectToCurrentDevice()
    fun canRecord(): MutableLiveData<Boolean>
}

// Receive from the SDK
interface StardustAPICallbacks {
    fun pttMaxTimeoutReached ()
    fun receiveMessage(stardustAPIPackage: StardustAPIPackage, text : String)
    fun receiveLocation(stardustAPIPackage: StardustAPIPackage, location: Location)
    fun receiveSOS(stardustAPIPackage: StardustAPIPackage, sosPackage: SOSPackage)
    fun receiveRealSOS(stardustAPIPackage: StardustAPIPackage, location: Location)
    fun handleSOSAck(stardustAPIPackage: StardustAPIPackage)
    fun receivePTT(stardustAPIPackage: StardustAPIPackage, byteArray : ByteArray)
    fun startedReceivingPTT(stardustAPIPackage: StardustAPIPackage, file: File)
    fun stopReceivingPTT(stardustAPIPackage: StardustAPIPackage)
    fun receiveImage(data: FileTransferData.Receive, file: File)
    fun receiveFile(data: FileTransferData.Receive, file: File)
    fun receiveFileStatus(
        data: FileTransferData.Receive,
        percentage: Int,
    )
    fun receiveFailure(
        data: FileTransferData.Receive,
        failure: FileReceiver.FileFailure
    )
    fun connectionStatusChanged(connectionType: ConnectionType?)
    fun onRSSIChanged(rssi : Int)
    fun onBatteryChanged(battery : Int)
    fun onAppEvent(stardustAppEventPackage: StardustAppEventPackage)
    fun onPermissionDenied(deviceName : String)
    fun onDeviceInitialized(state: StardustInitConnectionHandler.State)
}