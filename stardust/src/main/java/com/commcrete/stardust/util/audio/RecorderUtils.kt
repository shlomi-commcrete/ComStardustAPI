package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.ai.codec.PttSession
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.ai.codec.AudioRecorderAI
import com.commcrete.stardust.util.SharedPreferencesUtil
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

    /**
     * True while an AI or CODEC2 recording session is in flight. Guards [startRecording]
     * against a second, overlapping session: [PttAudioProcessor] is a process-wide singleton
     * (shared filter chain + resampler state) and [AudioDsp]'s kernel cache is likewise
     * process-wide, so two concurrent recordings — one on the AI recorder's coroutine, one on
     * CODEC2's dedicated recording thread — would drive both through the same unguarded state.
     * Plain [java.util.concurrent.atomic.AtomicBoolean] rather than [canRecord] itself: this
     * must be checked/set atomically from whatever thread calls [startRecording], while
     * [canRecord] is `MutableLiveData` and can only be mutated from the main thread.
     */
    private val recordingInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    var dirToSaveFile: File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Stardust_ptt_files")
            .also { it.mkdirs() }

    // ──────────────────────────────────────────────────────────────────────

    fun init(pttInterface: PttInterface) {
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

        if (!recordingInProgress.compareAndSet(false, true)) {
            Log.w("AudioRecorder", "startRecording ignored: a recording session is already in progress")
            return null
        }

        try {
            Scopes.getMainCoroutine().launch { canRecord.value = false }

            return if (codeType == CODE_TYPE.CODEC2) {
                startCodec2Recording(destination, carrier)
            } else {
                startAIRecording(destination, carrier)
            }
        } catch (t: Throwable) {
            // Don't leave the guard stuck on if the start path itself throws before a
            // matching stopRecording() call would otherwise clear it.
            recordingInProgress.set(false)
            throw t
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
        val session = PttSendManager.restart(
            onMaxTimeoutReached = {
                Log.d(LOG_TAG, "AI session: max PTT timeout reached — stopping recorder")
                aiRecorder?.stop()
            }
        )

        val enableNoiseCancellation = SharedPreferencesUtil.getNoiseSuppressorEnableState(DataManager.context)

        aiRecorder = AudioRecorderAI(
            context = DataManager.context,
            chunkDurationMs = 500,
            filesDirProvider = { file }
        ).apply {
            onChunkReady = { pcmArray, chunkIndex, captureRate, deviceType: Int? ->
                forwardAiChunk(
                    pcmArray = pcmArray,
                    captureRate = captureRate,
                    file = file,
                    carrier = carrier,
                    destination = destination,
                    enableNoiseCancellation = enableNoiseCancellation
                )
            }
            onPartialFinalChunk = { pcmArray, chunkIndex, captureRate, deviceType: Int? ->
                forwardAiChunk(
                    pcmArray = pcmArray,
                    captureRate = captureRate,
                    file = file,
                    carrier = carrier,
                    destination = destination,
                    enableNoiseCancellation = enableNoiseCancellation,
                    isFinal = true
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

    /**
     * Hand [pcmArray] straight off to [PttSendManager.addRawFrame] — DSP
     * processing (HPF/Notch/RNNoise/AGC/Dynamics/resample, via
     * [PttAudioProcessor.process]) now runs on that session's own
     * [com.commcrete.stardust.ai.codec.PttSession.dspJob], not here. This
     * function is called synchronously from [AudioRecorderAI]'s capture
     * loop (`Dispatchers.IO`, in between `AudioRecord.read()` calls), so it
     * must stay non-blocking: a slow RNNoise forward pass must never delay
     * draining the microphone, or the whole recording falls behind
     * real-time. See [PttSendManager.launchDspProcessingLoop].
     */
    private fun forwardAiChunk(
        pcmArray: ShortArray,
        captureRate: Int,
        file: File,
        carrier: Carrier?,
        destination: String,
        enableNoiseCancellation: Boolean,
        isFinal: Boolean = false
    ) {
        PttSendManager.addRawFrame(
            pcmArray = pcmArray,
            nativeRate = captureRate,
            enableNoiseCancellation = enableNoiseCancellation,
            isFinal = isFinal,
            file = file,
            carrier = carrier,
            chatID = destination
        )
    }

    /**
     * Resolve the current input route to a [RecordingProfileType] and
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
        encodingType: CODE_TYPE,
        enableNoiseCancellation: Boolean,
        isFinal: Boolean = false
    ): ShortArray {
        val targetRate = when (encodingType) {
            CODE_TYPE.AI -> PttAudioProcessor.AI_TARGET_SAMPLE_RATE
            CODE_TYPE.CODEC2 -> PttAudioProcessor.CODEC2_TARGET_SAMPLE_RATE
        }
        return PttAudioProcessor.process(
            pcmArray = pcmArray,
            nativeRate = nativeRate,
            targetRate = targetRate,
            enableNoiseCancellation = enableNoiseCancellation,
            isFinal = isFinal
        )
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
            recordingInProgress.set(false)
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
            delay(3000)
            PttSendManager.finish(session)
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