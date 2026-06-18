package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.ai.codec.PttSession
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.ai.codec.AudioRecorderAI
import com.ustadmobile.codec2.Codec2
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File


@SuppressLint("StaticFieldLeak")
object RecorderUtils {

    //var file : File? = null
    var ts : Long = 0
    private val LOG_TAG = "AudioRecordTest"

    private var pttInterface : PttInterface? = null
    private var audioRecorderCodec2 : AudioRecorderCodec2? = AudioRecorderCodec2(DataManager.context)
    private var aiRecorder : AudioRecorderAI? = null

    val canRecord : MutableLiveData<Boolean> = MutableLiveData(true)

    var dirToSaveFile: File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Stardust_ptt_files")
            .also { it.mkdirs() }

    // ──────────────────────────────────────────────────────────────────────

    fun init(pttInterface: PttInterface) {
        PttAudioProcessor.loadProfiles(DataManager.context)
        RecorderUtils.pttInterface = pttInterface
        if (!dirToSaveFile.exists()) dirToSaveFile.mkdirs()
    }


    // ----------------------------------------
    // Start Recording
    // ----------------------------------------
    @RequiresPermission(RECORD_AUDIO)
    fun startRecording(
        destination: String,
        carrier: Carrier?,
        codeType: CODE_TYPE?
    ): File? {
        Log.d("AudioRecorder", "Start recording")

        Scopes.getMainCoroutine().launch { canRecord.value = false }

        return if (codeType == CODE_TYPE.CODEC2) {
            startCodec2Recording(destination, carrier)
        } else {
            startAIRecording(destination, carrier)
        }
    }

    private fun startCodec2Recording(destination: String, carrier: Carrier?): File? {
        audioRecorderCodec2 = AudioRecorderCodec2(DataManager.context, pttInterface)
        audioRecorderCodec2 ?: return null
        val file: File? = if (!DataManager.getSavePTTFilesRequired(DataManager.context)) {
            FileUtils.withTempFile(
                context = DataManager.context,
                prefix = destination,
                suffix = DataManager.getSource()
            ) { tempFile ->
                audioRecorderCodec2?.startRecording(tempFile, carrier)
            }
        } else {
            createFile(DataManager.fileLocation, destination, DataManager.getSource())?.also {
                audioRecorderCodec2?.startRecording(it, carrier)
            }
        }
        return file
    }

    private fun startAIRecording(destination: String, carrier: Carrier?): File? {
        Log.d("AudioRecorder", "NAE Recording Started")
        PttSendManager.init(DataManager.context, pttInterface)

        // Determine which file to use
        val file: File? = if (!DataManager.getSavePTTFilesRequired(DataManager.context)) {
            // Use temporary file
            FileUtils.withTempFile(
                context = DataManager.context,
                prefix = destination,
                suffix = DataManager.getSource()
            ) { tempFile ->
                setupAIRecorder(tempFile, destination, carrier)
            }
        } else {
            // Use persistent file
            val persistentFile = createFile(DataManager.fileLocation, destination, DataManager.getSource())
            persistentFile?.let { setupAIRecorder(it, destination, carrier) }
            persistentFile
        }

        return file
    }

    private fun setupAIRecorder(file: File, destination: String, carrier: Carrier?) {
        // Begin a new isolated session. This session owns its own queue,
        // frame buffer and target file — a previous session that hasn't
        // finished encoding/saving yet keeps running independently and
        // will not be corrupted by this one. Capture the handle so the
        // delayed finishAIRecording finalizes THIS session, even if the
        // user starts another recording before the 3 s grace expires.
        //
        // The `onMaxTimeoutReached` lambda mirrors what
        // [AudioRecorderCodec2.onPipelinePacketSent] does inline: when
        // the session crosses [SharedPreferencesUtil.getPTTTimeout],
        // PttSendManager has already fired the SDK / viewModel
        // callbacks; we just need to stop the recorder itself, which
        // PttSendManager can't reach because it lives here. Calling
        // `aiRecorder?.stop()` cancels the capture coroutine, which
        // fires `onStateChanged(false)` → `finishAIRecording(...)` →
        // [PttSendManager.finish] downstream so the session finalizes
        // cleanly.
        val session = PttSendManager.restart(
            onMaxTimeoutReached = {
                Log.d(LOG_TAG, "AI session: max PTT timeout reached — stopping recorder")
                aiRecorder?.stop()
            }
        )

        aiRecorder = AudioRecorderAI(
            context = DataManager.context,
            chunkDurationMs = 500,
            filesDirProvider = { file }
        ).apply {
            onChunkReady = { pcmArray, chunkIndex, captureRate, deviceType: Int? ->
                forwardAiChunk(
                    pcmArray = pcmArray,
                    chunkIndex = chunkIndex,
                    captureRate = captureRate,
                    deviceType = deviceType,
                    file = file,
                    carrier = carrier,
                    destination = destination,
                )
            }
            onPartialFinalChunk = { pcmArray, chunkIndex, captureRate, deviceType: Int? ->
                forwardAiChunk(
                    pcmArray = pcmArray,
                    chunkIndex = chunkIndex,
                    captureRate = captureRate,
                    deviceType = deviceType,
                    file = file,
                    carrier = carrier,
                    destination = destination,
                )
            }
            onError = { throwable ->
                Timber.w(throwable, "AudioRecorderAI error")
            }
            onStateChanged = { recording ->
                Log.d(LOG_TAG, "Recording state changed: $recording (session ${session.id})")
                if (!recording) finishAIRecording(DataManager.context, session)
            }
        }

        aiRecorder?.start()

        Timber.d("AudioRecorderAI started for session ${session.id}")
    }

    private fun forwardAiChunk(
        pcmArray: ShortArray,
        chunkIndex: Int,
        captureRate: Int,
        deviceType: Int?,
        file: File,
        carrier: Carrier?,
        destination: String,
    ) {
        val prepared = preprocessChunkForEncoding(
            pcmArray = pcmArray,
            nativeRate = captureRate,
            actualInputType = deviceType,
            chunkIndex = chunkIndex,
            encodingType = CODE_TYPE.AI,
            chunkDurationMs = 500,
        )
        PttSendManager.addNewFrame(
            pcmArray = prepared,
            file = file,
            carrier = carrier,
            chatID = destination
        )
    }

    /**
     * Resolve the current input route to a [RecordingDeviceType] and
     * delegate the actual filtering + resampling to [PttAudioProcessor].
     *
     * Both encoder paths (AI / CODEC2) come through here so they share
     * identical preprocessing semantics — only the target rate differs
     * (24 kHz for AI, 8 kHz for CODEC2). The active DSP profile for
     * the inferred device type is looked up inside the processor; this
     * facade just handles the device-routing concern.
     */
    fun preprocessChunkForEncoding(
        pcmArray: ShortArray,
        nativeRate: Int,
        actualInputType: Int?,
        chunkIndex: Int,
        encodingType: CODE_TYPE,
        chunkDurationMs: Int? = null,
    ): ShortArray {
        val recordingDeviceType = inferDeviceType(actualInputType)
        val targetRate = when (encodingType) {
            CODE_TYPE.AI -> PttAudioProcessor.AI_TARGET_SAMPLE_RATE
            CODE_TYPE.CODEC2 -> PttAudioProcessor.CODEC2_TARGET_SAMPLE_RATE
        }
        return PttAudioProcessor.process(
            pcmArray = pcmArray,
            nativeRate = nativeRate,
            targetRate = targetRate,
            deviceType = recordingDeviceType,
            flowKey = encodingType.name,
            chunkIndex = chunkIndex,
            chunkDurationMs = chunkDurationMs,
        )
    }


    /**
     * Infer [RecordingDeviceType] from actual route when available,
     * then input-device preference as fallback.
     *
     * USB routes can represent jbox hardware; model prefixes map to:
     * `SD-100*` -> [RecordingDeviceType.JBOX_INTERNAL]
     * `SD-200*` -> [RecordingDeviceType.JBOX_EXTERNAL]
     */
    private fun inferDeviceType(actualInputType: Int? = null): RecordingDeviceType {
        val hasActualUsbRoute = actualInputType == AudioDeviceInfo.TYPE_USB_DEVICE ||
            actualInputType == AudioDeviceInfo.TYPE_USB_HEADSET ||
            actualInputType == AudioDeviceInfo.TYPE_USB_ACCESSORY

        if(BittelUsbManager2.isJboxAudioConnected() && hasActualUsbRoute) {
            val model = ConfigurationUtils.bittelConfiguration.value?.deviceModel
            return when {
                model?.startsWith("SD-100", ignoreCase = true) == true -> RecordingDeviceType.JBOX_EXTERNAL
                model?.startsWith("SD-200", ignoreCase = true) == true -> RecordingDeviceType.JBOX_INTERNAL
                else -> RecordingDeviceType.OTHER
            }
        }
        return RecordingDeviceType.OTHER
    }

    // ----------------------------------------
    // Stop Recording
    // ----------------------------------------
    fun stopRecording(
        chatID: String,
        carrier: Carrier?,
        codeType: CODE_TYPE?,
        file: File?
    ) {
        Log.d("AudioRecorder", "Stop recording")

        if (codeType == CODE_TYPE.CODEC2) stopCodec2Recording(chatID, carrier, file)
        else stopAIRecording()

        Scopes.getMainCoroutine().launch {
            delay(300)
            canRecord.value = true
        }
    }

    private fun stopCodec2Recording(chatID: String, carrier: Carrier?, file: File?) {
        audioRecorderCodec2?.run {
            file?.let { stopRecording(retry = 0, chatID, it.absolutePath, DataManager.context, carrier) }
            Scopes.getDefaultCoroutine().launch {
                delay(50)
                audioRecorderCodec2 = null
            }
        }
    }

    private fun stopAIRecording() {
        Log.d("AudioRecorder", "Stop AI Recording")
        aiRecorder?.stop()
        Scopes.getDefaultCoroutine().launch {
            delay(50)
            aiRecorder = null
        }
    }

    private fun finishAIRecording(context: Context, session: PttSession) {
        Scopes.getDefaultCoroutine().launch {
            // Short grace period so any in-flight `addNewFrame` calls from
            // the recorder's tail (onPartialFinalChunk) reach this
            // session's queue before we close it. The encoding job then
            // drains the queue, finalizes the file ONCE and releases its
            // wake lock — making finalization fully idempotent and safe
            // against duplicate calls.
            delay(3000)
            PttSendManager.finish(session)
            // Note: PttSendManager's session job holds its own wake lock
            // until it has saved the WAV, so it's safe to release the
            // recorder-side lock here even though encoding may still be
            // running.
            AudioRecordingKeepAlive.release()
        }
    }

    enum class CodecValues(val mode : Int,val sampleRate: Int, val charNumOutput : Int){
        MODE700(Codec2.CODEC2_MODE_700C , 4400 , 4 ),
        MODE2400(Codec2.CODEC2_MODE_2400 , 6000 , 8),
        MODE1600(Codec2.CODEC2_MODE_1600 , 6000 , 8),
        MODE3200(Codec2.CODEC2_MODE_3200 , 8000 , 8)
    }
    private fun createFile(fileDir: String, chatID: String, userId: String) : File?{
        try{
            ts = System.currentTimeMillis()
            val directory = File("$fileDir/$chatID")
            val newFile = File("$fileDir/$chatID/$ts-$userId.pcm")
            if(!directory.exists()){
                directory.mkdir()
            }
            if(!newFile.exists()){
                newFile.createNewFile()

            }
            return newFile
        }catch (e : Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun createFileWav(context: Context, chatID: String, userId: String) : File{
        ts = System.currentTimeMillis()
        val directory = File("${context.filesDir}/$chatID")
        val newFile = File("${context.filesDir}/$chatID/$ts-$userId.wav")
        if(!directory.exists()){
            directory.mkdir()
        }
        if(!newFile.exists()){
            newFile.createNewFile()

        }
        return newFile
    }

    enum class CODE_TYPE (val id : Int, val codecName: String){
        AI(1, "Neural Audio Encoder (NAE)"), CODEC2(0, "Classic Codec Encoder")
    }
}