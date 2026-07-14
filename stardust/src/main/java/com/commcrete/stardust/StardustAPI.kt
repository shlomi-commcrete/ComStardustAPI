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

/**
 * A Stardust/Bittel device already bonded to the phone (paired from phone Settings or another
 * app) that the app can adopt. [address] is the BLE MAC; [name] is a display label.
 */
data class AdoptableDevice(val address: String, val name: String)

/**
 * Why a BLE connection couldn't be established/continued, so the host can show the user an
 * actionable message (enable Bluetooth, grant a permission, etc.) instead of a generic failure.
 */
enum class BleUnavailableReason {
    /** The Bluetooth adapter is turned off. */
    BLUETOOTH_DISABLED,
    /** The device has no Bluetooth adapter / BLE hardware. */
    BLUETOOTH_UNSUPPORTED,
    /** `BLUETOOTH_CONNECT` runtime permission (Android 12+) is not granted. */
    CONNECT_PERMISSION_MISSING,
    /** `BLUETOOTH_SCAN` runtime permission (Android 12+) is not granted. */
    SCAN_PERMISSION_MISSING,
    /** Cause could not be determined. */
    UNKNOWN,
}

interface StardustAPI {

    // Send to the SDK
    fun sendMessage(chatId: String, stardustAPIPackage: StardustAPIPackage, text : String)
    fun startPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE): File?
    fun stopPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE, file: File)
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

    /**
     * Stardust devices already bonded to the phone that are not the app's current device —
     * candidates for [adoptDevice]. Empty if Bluetooth is off or CONNECT permission is missing.
     */
    fun getAdoptableDevices(): List<AdoptableDevice>

    /**
     * Adopts an already-bonded device by its MAC [address]: persists it, marks it paired, and
     * connects + syncs to fetch its data. Returns false if the address is not a bonded Stardust
     * device. Use for pre-paired devices surfaced via [StardustAPICallbacks.onAdoptableDevicesFound].
     */
    fun adoptDevice(address: String): Boolean
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
    fun onDeviceConnectionRSSIChanged (rssi : Int)
    fun onSignalRSSIChanged(rssiData: StardustAppEventPackage.RSSIPackage) // called with snr = null if no refresh arrives within 15s (see AppEvents.updateRssiSignalChanged)
    fun onBatteryChanged(battery : Int)
    fun onAppEvent(stardustAppEventPackage: StardustAppEventPackage)
    /**
     * Called when a BLE connection can't be established or is dropped for a determinable reason
     * ([BleUnavailableReason]) — permission missing, Bluetooth off, unsupported, etc. Replaces the
     * old permission-only `onPermissionDenied`. [deviceName] is the target device's name/MAC if
     * known. Default no-op so it's optional to implement.
     */
    fun onConnectionUnavailable(reason: BleUnavailableReason, deviceName: String?) {}
    fun onDeviceInitialized(state: StardustInitConnectionHandler.State)

    /**
     * Called when the app is not paired but finds Stardust devices already bonded to the phone
     * (paired from phone Settings or another app). The host should ask the user whether to use
     * one and, if so, call [StardustAPI.adoptDevice]. Default no-op for backward compatibility.
     */
    fun onAdoptableDevicesFound(devices: List<AdoptableDevice>) {}
}