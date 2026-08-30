package com.commcrete.stardust.util.audio

import android.Manifest.permission.RECORD_AUDIO
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.PttRecordingError
import com.commcrete.stardust.PttRecordingEvent
import com.commcrete.stardust.PttRecordingState
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.ai.codec.PttSession
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.flag.PttPipelineFeatureFlag
import com.commcrete.stardust.audio.v2.framework.PttV2Wiring
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.ai.codec.AudioRecorderAI
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.ustadmobile.codec2.Codec2
import com.commcrete.stardust.room.new_db.message.EncoderType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File


object RecorderUtils {

    //var file : File? = null
    var ts : Long = 0
    private val LOG_TAG = "AudioRecordTest"

    private var pttInterface : PttInterface? = null
    private var audioRecorderCodec2 : AudioRecorderCodec2? = AudioRecorderCodec2()
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

    // ── Outgoing-recording lifecycle (StardustAPICallbacks.onPttRecordingStateChanged) ────────────

    /**
     * What one in-flight recording needs in order to describe itself to the host. Kept per recording id
     * rather than in a single "current" field because [PttRecordingState.SENT] can land AFTER the next
     * key-down: on the v2 pipeline the microphone is released at key-up while encoding and the ordered
     * send continue, so recording N's SENT routinely races recording N+1's STARTED.
     *
     * [emitted] makes each state at-most-once per recording, which the retry paths need — legacy
     * `stopRecordingNow` can run up to three times for one key-up.
     */
    private class PttRecordingInfo(
        val id: String,
        val chatId: String,
        val receiverId: String,
        val codeType: CODE_TYPE?,
    ) {
        val emitted: MutableSet<PttRecordingState> = java.util.EnumSet.noneOf(PttRecordingState::class.java)
    }

    private val recordingIdSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val recordings = java.util.concurrent.ConcurrentHashMap<String, PttRecordingInfo>()

    /** Id of the recording that currently owns the mic; the async [PttRecordingState.SENT] uses its own. */
    @Volatile private var currentRecordingId: String? = null

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
        chatId: String,
        receiverId: String,
        carrier: Carrier?,
        codeType: CODE_TYPE?
    ): File? {
        Log.d("AudioRecorder", "Start recording")

        // Minted before the guard so a REJECTED key-down is still reportable: the host asked for a
        // recording and gets a self-describing ERROR event for it, with its own id.
        val recording = PttRecordingInfo(
            id = "ptt-${System.currentTimeMillis()}-${recordingIdSeq.incrementAndGet()}",
            chatId = chatId,
            receiverId = receiverId,
            codeType = codeType,
        )
        recordings[recording.id] = recording

        if (!recordingInProgress.compareAndSet(false, true)) {
            Log.w("AudioRecorder", "startRecording ignored: a recording session is already in progress")
            notifyPttRecordingError(PttRecordingError.ALREADY_RECORDING, recording.id)
            return null
        }
        currentRecordingId = recording.id

        try {
            Scopes.getMainCoroutine().launch { canRecord.value = false }

            // v2 pipeline (flag-guarded). Both codecs route here when enabled.
            if (PttPipelineFeatureFlag.isEnabled(DataManager.appContext)) {
                val codecId = if (codeType == CODE_TYPE.CODEC2) CodecId.CODEC2 else CodecId.WAVTOKENIZER
                return startV2Recording(codecId, chatId, receiverId, carrier, recording.id)
            }

            return if (codeType == CODE_TYPE.CODEC2) {
                // AudioRecorderCodec2 opens the mic synchronously inside startRecording, so by the time it
                // returns the device state is already decisive: capturing → STARTED, otherwise the mic is
                // held by something else. A null file means the file setup failed and nothing started.
                val file = startCodec2Recording(receiverId, carrier)
                when {
                    file == null -> notifyPttRecordingError(PttRecordingError.UNKNOWN, recording.id)
                    audioRecorderCodec2?.isCapturing() == true -> notifyPttRecordingStarted()
                    else -> notifyPttRecordingError(PttRecordingError.MIC_UNAVAILABLE, recording.id)
                }
                file
            } else {
                // AI reports STARTED/STOPPED from AudioRecorderAI's own state callback and ERROR from its
                // onError — the mic is opened on that recorder's coroutine. See setupAIRecorder.
                startAIRecording(chatId, receiverId, carrier)
            }
        } catch (t: Throwable) {
            // Don't leave the guard stuck on if the start path itself throws before a
            // matching stopRecording() call would otherwise clear it.
            recordingInProgress.set(false)
            notifyPttRecordingError(PttRecordingError.UNKNOWN, recording.id)
            throw t
        }
    }

    private fun startCodec2Recording(destination: String, carrier: Carrier?): File? {
        audioRecorderCodec2 = AudioRecorderCodec2(pttInterface)
        audioRecorderCodec2 ?: return null
        val file: File? = if (!DataManager.getSavePTTFilesRequired()) {
            FileUtils.withTempFile(
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

    /**
     * v2 send path (CODEC2 or WavTokenizer). Delegates capture→encode→ordered-send to
     * [PttV2Wiring.recorderBridge]. Returns null: v2 owns its own file/persistence lifecycle, so there
     * is no legacy File handle to hand back (the matching [stopRecording] v2 branch ignores `file`).
     */
    private fun startV2Recording(
        codecId: CodecId,
        chatId: String,
        destination: String,
        carrier: Carrier?,
        recordingId: String,
    ): File? {
        PttV2Wiring.init(DataManager.appContext)
        // Called synchronously (the bridge only enqueues) so key-down is ordered before the key-up that
        // [stopRecording] enqueues. Launching each on its own coroutine imposed no ordering and could
        // drop the key-up entirely, leaving the mic open until the max-PTT watchdog.
        //
        // [recordingId] rides along so the bridge can report SENT/ERROR against the right recording once
        // the transmit gate drains — by then the next recording may already own the mic.
        PttV2Wiring.recorderBridge.startRecording(
            codecId = codecId,
            chatId = chatId,
            source = DataManager.getSource(),
            destination = destination,
            carrier = carrier,
            recordingId = recordingId,
        )
        return null
    }

    /**
     * [PttRecordingState.STARTED] — the microphone is genuinely capturing. Deliberately NOT reported when
     * [startRecording] is entered, because the two differ in every pipeline: the in-progress guard can
     * reject the key-down, the file setup can fail, and on v2 [startRecording] only enqueues the key-down
     * while the `AudioRecord` opens a moment later on the capture thread.
     *
     * Reported from each pipeline's own "mic is open" point — [MicCaptureSource] (v2),
     * [startCodec2Recording]'s return (legacy CODEC2, mic opened synchronously) and [AudioRecorderAI]'s
     * state callback (legacy AI, mic opened on its own coroutine) — so the host sees one signal
     * regardless of which pipeline is active.
     */
    internal fun notifyPttRecordingStarted() = emit(currentRecordingId, PttRecordingState.STARTED)

    /**
     * [PttRecordingState.STOPPED] — key-up, microphone released. Encoding and the ordered send continue
     * after this on the v2 pipeline; [notifyPttRecordingSent] is the "it is all on the link" signal.
     */
    internal fun notifyPttRecordingStopped() = emit(currentRecordingId, PttRecordingState.STOPPED)

    /**
     * [PttRecordingState.SENT] — every packet of [id] has been handed to the radio link. Terminal, so the
     * recording is forgotten here.
     *
     * The v2 pipeline passes [id] explicitly because this lands after key-up, by which time the next
     * recording may already own the mic. The legacy pipelines cannot overlap, so they use the default.
     */
    internal fun notifyPttRecordingSent(id: String? = currentRecordingId) =
        emit(id, PttRecordingState.SENT, terminal = true)

    /**
     * [PttRecordingState.ERROR] — terminal. The first error wins: the recording is forgotten here, so a
     * generic follow-up (e.g. the pipeline reporting a non-LAST terminal reason after a specific
     * `MIC_UNAVAILABLE`) is dropped rather than reaching the host as a second, vaguer event.
     */
    internal fun notifyPttRecordingError(error: PttRecordingError, id: String? = currentRecordingId) =
        emit(id, PttRecordingState.ERROR, error = error, terminal = true)

    /**
     * Deliver one lifecycle event, at most once per state per recording. A [terminal] state also drops the
     * recording, which is what makes ERROR suppress any later SENT (and vice versa).
     *
     * Runs on whichever thread reached the event (the capture thread for STARTED/STOPPED), and swallows a
     * throwing host callback: on the v2 capture path an escaping exception would kill the very recording
     * being announced.
     */
    private fun emit(
        id: String?,
        state: PttRecordingState,
        error: PttRecordingError? = null,
        terminal: Boolean = false,
    ) {
        val info = id?.let { recordings[it] } ?: return
        synchronized(info) {
            if (!info.emitted.add(state)) return
            if (terminal) {
                recordings.remove(info.id)
                // Only on terminal: STOPPED must NOT clear it, because on the legacy pipelines the
                // post-key-up SENT still resolves through `currentRecordingId`.
                if (info.id == currentRecordingId) currentRecordingId = null
            }
        }
        val event = PttRecordingEvent(
            recordingId = info.id,
            state = state,
            chatId = info.chatId,
            receiverId = info.receiverId,
            codeType = info.codeType,
            error = error,
        )
        runCatching { DataManager.getCallbacks()?.onPttRecordingStateChanged(event) }
            .onFailure { Timber.tag(LOG_TAG).w(it, "onPttRecordingStateChanged threw for $event") }
    }

    private fun startAIRecording(chatId: String, receiverId: String, carrier: Carrier?): File? {
        Log.d("AudioRecorder", "NAE Recording Started")
        PttSendManager.init(pttInterface)

        // Determine which file to use
        val file: File? = if (!DataManager.getSavePTTFilesRequired()) {
            // Use temporary file
            FileUtils.withTempFile(
                prefix = receiverId,
                suffix = DataManager.getSource()
            ) { tempFile ->
                setupAIRecorder(tempFile, receiverId, chatId, carrier)
            }
        } else {
            // Use persistent file
            val persistentFile = createFile(DataManager.fileLocation, chatId, receiverId)
            persistentFile?.let { setupAIRecorder(it, receiverId, chatId, carrier) }
            persistentFile
        }

        return file
    }

    private fun setupAIRecorder(file: File, receiverId: String, chatId: String, carrier: Carrier?) {
        val session = PttSendManager.restart(
            onMaxTimeoutReached = {
                Log.d(LOG_TAG, "AI session: max PTT timeout reached — stopping recorder")
                aiRecorder?.stop()
            }
        )

        val enableNoiseCancellation = SharedPreferencesUtil.getNoiseSuppressorEnableState()

        aiRecorder = AudioRecorderAI(
            chunkDurationMs = 500,
            filesDirProvider = { file }
        ).apply {
            onChunkReady = { pcmArray, chunkIndex, captureRate, deviceType: Int? ->
                forwardAiChunk(
                    pcmArray = pcmArray,
                    captureRate = captureRate,
                    file = file,
                    carrier = carrier,
                    receiverId = receiverId,
                    chatId = chatId,
                    enableNoiseCancellation = enableNoiseCancellation
                )
            }
            onPartialFinalChunk = { pcmArray, chunkIndex, captureRate, deviceType: Int? ->
                forwardAiChunk(
                    pcmArray = pcmArray,
                    captureRate = captureRate,
                    file = file,
                    carrier = carrier,
                    receiverId = receiverId,
                    chatId = chatId,
                    enableNoiseCancellation = enableNoiseCancellation,
                    isFinal = true
                )
            }
            onError = { throwable ->
                Timber.w(throwable, "AudioRecorderAI error")
                // Covers "AudioRecord initialization failed" (mic held elsewhere) as well as any capture
                // or encode failure; ERROR is terminal, so it suppresses the SENT that would follow.
                notifyPttRecordingError(PttRecordingError.MIC_UNAVAILABLE)
            }
            onStateChanged = { recording ->
                Log.d(LOG_TAG, "Recording state changed: $recording (session ${session.id})")
                // AudioRecorderAI.start() only launches a coroutine, so this is the first point at which
                // the AI path is genuinely recording — notifying from startRecording would be too early.
                // The false branch is the mic actually being released.
                if (recording) {
                    notifyPttRecordingStarted()
                } else {
                    notifyPttRecordingStopped()
                    finishAIRecording(session)
                }
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
        receiverId: String,
        chatId: String,
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
            chatId = chatId,
            receiverId = receiverId,
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
        chatId: String,
        receiverId: String,
        carrier: Carrier?,
        codeType: CODE_TYPE?,
        file: File?
    ) {
        Log.d("AudioRecorder", "Stop recording")

        if (PttPipelineFeatureFlag.isEnabled(DataManager.appContext)) {
            // init() is idempotent — guards against a stop arriving before any start ever wired the
            // bridge (its `lateinit` would otherwise throw here).
            PttV2Wiring.init(DataManager.appContext)
            PttV2Wiring.recorderBridge.stopRecording()
        } else if (codeType == CODE_TYPE.CODEC2) {
            stopCodec2Recording(chatId, receiverId, carrier, file)
        } else {
            stopAIRecording()
        }

        Scopes.getMainCoroutine().launch {
            delay(300)
            canRecord.value = true
            recordingInProgress.set(false)
        }
    }

    private fun stopCodec2Recording(chatId: String, receiverID: String, carrier: Carrier?, file: File?) {
        audioRecorderCodec2?.run {
            // file can be null if startRecording's own file-creation step
            // failed or was skipped — don't let a null here throw before
            // the recordingInProgress guard above gets a chance to reset,
            // which would otherwise permanently lock out future recordings.
            file?.let {
                stopRecording(
                    chatId = chatId,
                    retry = 0,
                    receiverId = receiverID,
                    path = it.absolutePath,
                    carrier = carrier
                )
            }
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

    private fun finishAIRecording(session: PttSession) {
        Scopes.getDefaultCoroutine().launch {
            delay(3000)
            PttSendManager.finish(session)
            AudioRecordingKeepAlive.release()
            // Best-effort SENT for the legacy AI path: unlike v2's transmit gate there is no
            // "every frame is on the link" signal here, so this is "the session was finalized".
            notifyPttRecordingSent()
        }
    }

    enum class CodecValues(val mode : Int,val sampleRate: Int, val charNumOutput : Int){
        MODE700(Codec2.CODEC2_MODE_700C , 4400 , 4 ),
        MODE2400(Codec2.CODEC2_MODE_2400 , 6000 , 8),
        MODE1600(Codec2.CODEC2_MODE_1600 , 6000 , 8),
        MODE3200(Codec2.CODEC2_MODE_3200 , 8000 , 8)
    }
    private fun createFile(fileDir: String, chatID: String, receiverId: String) : File?{
        try{
            ts = System.currentTimeMillis()
            val directory = File("$fileDir/$chatID")
            val newFile = File("$fileDir/$chatID/$ts-$receiverId.pcm")
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

    private fun createFileWav(chatID: String, userId: String) : File{
        ts = System.currentTimeMillis()
        val context = DataManager.appContext
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
        AI(1, "Neural Audio Encoder (NAE)"), CODEC2(0, "Classic Codec Encoder");

        fun toEncoderType(): EncoderType = when (this) {
            AI -> EncoderType.AI
            CODEC2 -> EncoderType.CODEC2
        }

        companion object {
            fun fromId(id: Int): CODE_TYPE? = entries.firstOrNull { it.id == id }
        }
    }
}