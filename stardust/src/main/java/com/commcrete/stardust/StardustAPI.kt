package com.commcrete.stardust

import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.enums.ConnectionType
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.model.SOSPackage
import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.stardust.model.config.CurrentPreset
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

/**
 * Where an outgoing PTT recording is in its lifecycle. Reported through
 * [StardustAPICallbacks.onPttRecordingStateChanged].
 *
 * The normal sequence is [STARTED] → [STOPPED] → [SENT]. They are distinct moments, not synonyms: the
 * microphone is released at key-up ([STOPPED]) while encoding and transmission continue for some time
 * afterwards ([SENT]). [ERROR] is terminal and replaces whatever would have followed — a recording that
 * errors never reports [SENT].
 *
 * Each state is reported at most once per recording.
 */
enum class PttRecordingState {
    /** The microphone is open and capturing. Not merely "startPTT was called". */
    STARTED,
    /** Key-up: the microphone has been released. Encoding and transmission are still in progress. */
    STOPPED,
    /** Every packet of this recording has been handed to the radio link. Terminal. */
    SENT,
    /** The recording failed and produced nothing further — see [PttRecordingEvent.error]. Terminal. */
    ERROR,
}

/** Why a PTT recording failed. Accompanies [PttRecordingState.ERROR]. */
enum class PttRecordingError {
    /** A recording was already in progress, so this key-down was ignored. */
    ALREADY_RECORDING,
    /** The microphone could not be opened — typically another app or a call holds it. */
    MIC_UNAVAILABLE,
    /** The encoder was unavailable (e.g. the AI models are not loaded in this process). */
    ENCODER_UNAVAILABLE,
    /** Anything else: the capture/encode/transmit pipeline failed or was cancelled. */
    UNKNOWN,
}

/**
 * One lifecycle event of one outgoing PTT recording.
 *
 * [recordingId] is minted by the SDK at key-down and is unique per recording (including for a key-down
 * rejected with [PttRecordingError.ALREADY_RECORDING], which gets its own id). Use it to correlate the
 * events of a single recording — a [SENT][PttRecordingState.SENT] can arrive after the NEXT recording has
 * already started, so matching on [receiverId] alone is not sufficient.
 *
 * [chatId], [receiverId] and [codeType] echo the values passed to [StardustAPI.startPTT].
 */
data class PttRecordingEvent(
    val recordingId: String,
    val state: PttRecordingState,
    val chatId: String,
    val receiverId: String,
    val codeType: RecorderUtils.CODE_TYPE?,
    /** Set only when [state] is [PttRecordingState.ERROR]. */
    val error: PttRecordingError? = null,
)

interface StardustAPI {

    // Send to the SDK
    fun sendMessage(chatId: String, stardustAPIPackage: StardustAPIPackage, text : String)
    fun startPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE): File?
    fun stopPTT(chatId: String, stardustAPIPackage: StardustAPIPackage, codeType: RecorderUtils.CODE_TYPE, file: File)

    /**
     * Set the playback level of ONE incoming PTT stream, independently of every other stream.
     *
     * [streamId] identifies the stream and comes from the receive callbacks — it is
     * `stardustAPIPackage.groupId ?: stardustAPIPackage.senderId` as delivered by
     * [StardustAPICallbacks.startedReceivingPTT] / [StardustAPICallbacks.receivePTT]. Note a group PTT is
     * one stream per GROUP, not per talker.
     *
     * [level] is clamped to `0f..1f` (`0f` silence, `1f` unity); values above 1 give no extra boost. Any
     * level greater than 0 also un-mutes the stream.
     *
     * Applies immediately to a live stream and is remembered for a stream that has not started yet, so it
     * is safe to call before the first packet arrives. The value persists after the stream ends, so the
     * next PTT from the same peer/group reuses it. There is no getter — keep the level in the app if the
     * UI needs to display it. No-op while the SDK is on the legacy PTT pipeline.
     */
    fun setPttVolume(streamId: String, level: Float)

    /**
     * Mute or un-mute ONE incoming PTT stream without losing its chosen level: un-muting restores the
     * level previously set via [setPttVolume] rather than snapping to unity. See [setPttVolume] for where
     * [streamId] comes from. No-op while the SDK is on the legacy PTT pipeline.
     */
    fun setPttMuted(streamId: String, muted: Boolean)
    fun sendLocation(chatId: String, stardustAPIPackage: StardustAPIPackage, location: Location)
    fun sendImage(data: FileTransferData.Send, onFileStatusChange: FileSender.OnFileStatusChange): Deferred<Boolean>
    fun sendFile(data: FileTransferData.Send, onFileStatusChange: FileSender.OnFileStatusChange): Deferred<Boolean>
    fun stopSendFile(data: FileTransferData.Send)
    fun requestLocation(stardustAPIPackage: StardustAPIPackage)
    fun sendSOS(stardustAPIPackage: StardustAPIPackage, location: Location, type: SOSUtils.SOS_REPORT_TYPES?)
    fun init(appContext: Context, pluginContext: Context, fileLocation : String)
    fun scanForDevice(): MutableLiveData<List<ScanResult>>
    fun connectToDevice(device: ScanResult)
    fun disconnectFromDevice(disconnectByForce: Boolean)
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

    fun switchToPreset(preset: CurrentPreset)

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

    /**
     * One outgoing PTT recording changed state — started, stopped, fully sent, or failed. Replaces the
     * separate started/stopped callbacks so every stage carries the same payload
     * ([PttRecordingEvent]: recording id, chat, receiver, codec, and the error when there is one).
     *
     * Called on whichever SDK thread reached that moment — the capture thread for
     * [PttRecordingState.STARTED] / [PttRecordingState.STOPPED], a background thread for
     * [PttRecordingState.SENT] — so hop to the main thread before touching UI. Exceptions thrown here are
     * caught and logged by the SDK; on the capture path an escaping exception would otherwise kill the
     * very recording being announced.
     */
    fun onPttRecordingStateChanged(event: PttRecordingEvent)
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
    /**
     * An incoming file/image transfer did not deliver its file — packages were lost
     * ([FileReceiver.FileFailure.MISSING]), this side could not save it
     * ([FileReceiver.FileFailure.ERROR]), or the radio went away mid-transfer
     * ([FileReceiver.FileFailure.DISCONNECTED]).
     *
     * A FAILED attachment message carrying the reason has already been written to the
     * conversation by the time this is called, so re-reading the thread here is safe.
     * The row has no file path — a failed transfer writes nothing to disk. The app owns
     * the wording shown for each reason.
     *
     * Called on the main thread. The outgoing counterpart is
     * [com.commcrete.stardust.util.FileSender.OnFileStatusChange.failedSending].
     */
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