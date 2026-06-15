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
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
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
    private var wavRecorder : WavRecorder? = WavRecorder(DataManager.context)
    private var aiRecorder : AudioRecorderAI? = null

    private const val AI_TARGET_SAMPLE_RATE = 24_000

    val canRecord : MutableLiveData<Boolean> = MutableLiveData(true)

    var dirToSaveFile: File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Stardust_ptt_files")
            .also { it.mkdirs() }

    // ──────────────────────────────────────────────────────────────────────
    // Per-device-type DSP profiles
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Per-device-type DSP profile presets keyed by [RecordingDeviceType].
     * Each device type can have multiple profiles (presets) for user selection.
     * Loaded from [SharedPreferencesUtil] in [init]; updated via
     * [setAiRecorderProfiles] which persists each change immediately.
     * Currently one preset per device type, expandable for user-selectable options.
     */
    private val profileMap: MutableMap<RecordingDeviceType, List<AiRecorderProfile>> = mutableMapOf<RecordingDeviceType, List<AiRecorderProfile>>().apply {
        RecordingDeviceType.entries.forEach { deviceType ->
            val profile = getAiRecorderDefaultProfilePreset(deviceType)
            // Only add if at least one config is non-null (profile is meaningful)
            if (profile.lowPass != null || profile.notch != null || profile.rnNoise != null ||
                profile.agc != null || profile.dynamics != null) {
                put(deviceType, listOf(profile))
            }
        }
    }


    fun getAiRecorderDefaultProfilePreset(recordingDeviceType: RecordingDeviceType): AiRecorderProfile {
        return AiRecorderProfile(
            title = "Default",
            isActive = true,
            lowPass = LowPassConfig.getDefault(recordingDeviceType),
            notch = NotchConfig.getDefault(recordingDeviceType),
            rnNoise = RnNoiseConfig.getDefault(recordingDeviceType),
            agc = AGCConfig.getDefault(recordingDeviceType),
            dynamics = DynamicsConfig.getDefault(recordingDeviceType),
        )
    }

    fun getAiRecorderProfiles(recordingDeviceType: RecordingDeviceType): List<AiRecorderProfile>? = profileMap[recordingDeviceType]

    fun getAiActiveRecorderProfile(recordingDeviceType: RecordingDeviceType): AiRecorderProfile? =
        profileMap[recordingDeviceType]?.find { it.isActive }

    /**
     * Update (or add) profiles for a device-type and persist to [SharedPreferencesUtil].
     */
    fun setAiRecorderProfiles(context: Context, recordingDeviceType: RecordingDeviceType, profiles: List<AiRecorderProfile>) {
        profileMap[recordingDeviceType] = profiles
        val flatMap = profileMap.flatMap { (type, profs) ->
            profs.map { type to it }
        }.toMap()
        @Suppress("UNCHECKED_CAST")
        SharedPreferencesUtil.setAiRecorderProfiles(context, flatMap)
    }

    /**
     * Convenience function to update a single preset at a given index (or add as new).
     */
    fun setAiRecorderProfile(context: Context, recordingDeviceType: RecordingDeviceType, profile: AiRecorderProfile, presetIndex: Int = 0) {
        val profiles = profileMap[recordingDeviceType]?.toMutableList() ?: mutableListOf()
        if (presetIndex < profiles.size) {
            profiles[presetIndex] = profile
        } else {
            profiles.add(profile)
        }
        setAiRecorderProfiles(context, recordingDeviceType, profiles)
    }

    // ──────────────────────────────────────────────────────────────────────

    fun init(pttInterface: PttInterface) {
        RecorderUtils.pttInterface = pttInterface
        if (!dirToSaveFile.exists()) dirToSaveFile.mkdirs()
        // Load persisted profiles; overlay onto defaults so any type not yet
        // saved keeps the built-in sensible values.
        val saved = SharedPreferencesUtil.getAiRecorderProfiles(DataManager.context)
        // Group loaded profiles by device type to support multiple presets
        saved.entries.groupBy { it.key }.forEach { (type, entries) ->
            profileMap[type] = entries.map { it.value }
        }
    }

    fun onPTTTest(){
        wavRecorder = WavRecorder(DataManager.context, null)
        wavRecorder?.sendAudioTest(DataManager.context)
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
        wavRecorder = WavRecorder(DataManager.context, pttInterface)
        wavRecorder ?: return null
        val file: File? = if (!DataManager.getSavePTTFilesRequired(DataManager.context)) {
            FileUtils.withTempFile(
                context = DataManager.context,
                prefix = destination,
                suffix = DataManager.getSource()
            ) { tempFile ->
                wavRecorder?.startRecording(tempFile, carrier)
            }
        } else {
            createFile(DataManager.fileLocation, destination, DataManager.getSource())?.also {
                wavRecorder?.startRecording(it, carrier)
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
        PttSendManager.restart()

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
                Log.d(LOG_TAG, "Recording state changed: $recording")
                if (!recording) finishAIRecording(DataManager.context)
            }
        }

        aiRecorder?.start()

        Timber.d("AudioRecorderAI started")
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
            chatID = destination,
            applyFilters = false,
            nativeRate = AI_TARGET_SAMPLE_RATE,
            profile = null,
        )
    }

    private val lastLoggedFilterSignatureByFlow: MutableMap<String, String> = mutableMapOf()
    private val lastLoggedChunkShapeByFlow: MutableMap<String, String> = mutableMapOf()

    private fun resolveActiveProfile(deviceType: RecordingDeviceType): AiRecorderProfile? {
        return profileMap[deviceType]?.firstOrNull { it.isActive }
    }

    private fun buildFilterSignature(profile: AiRecorderProfile?): String {
        if (profile == null) return "preset=<none>; filters=[]"
        val enabledFilters = mutableListOf<String>()
        profile.lowPass?.takeIf { it.enabled }?.let {
            enabledFilters.add("lowPass(cutoff=${it.cutoffHz}, rollOff=${it.rollOffDbPerOctave})")
        }
        profile.notch?.takeIf { it.enabled }?.let {
            enabledFilters.add("notch(harmonics=${it.harmonics.size})")
        }
        profile.rnNoise?.takeIf { it.enabled }?.let {
            enabledFilters.add("rnNoise(mix=${it.mix}, maxAttenDb=${it.maxAttenuationDb})")
        }
        profile.agc?.takeIf { it.enabled }?.let {
            enabledFilters.add(
                "agc(target=${it.targetLevel}, atk=${it.attackMs}, rel=${it.releaseMs}, +${it.maxGainDb}/${it.minGainDb}, gate=${it.noiseGateLevel})"
            )
        }
        profile.dynamics?.takeIf { it.enabled }?.let {
            enabledFilters.add(
                "dynamics(in=${it.inputGainDb}, b0Gate=${it.band0.noiseGateDb}, b1Gate=${it.band1.noiseGateDb}, b2Gate=${it.band2.noiseGateDb}, lim=${it.limiter.thresholdDb})"
            )
        }
        return "preset=${profile.title}; active=${profile.isActive}; filters=[${enabledFilters.joinToString()}]"
    }

    /**
     * Shared preprocessing used by AI and CODEC2 paths.
     * Applies selected profile filters at [nativeRate], then resamples only for AI.
     */
    fun preprocessChunkForEncoding(
        pcmArray: ShortArray,
        nativeRate: Int,
        actualInputType: Int?,
        chunkIndex: Int,
        encodingType: CODE_TYPE,
        chunkDurationMs: Int? = null,
    ): ShortArray {
        val flowKey = encodingType.name
        val recordingDeviceType = inferDeviceType(actualInputType)
        val activeProfile = resolveActiveProfile(recordingDeviceType)
        val filterSignature = buildFilterSignature(activeProfile)

        val lastFilterSignature = lastLoggedFilterSignatureByFlow[flowKey]
        if (chunkIndex == 0 || lastFilterSignature != filterSignature) {
            Log.d(LOG_TAG,
                "$flowKey filter preset for chunk=$chunkIndex deviceType=$recordingDeviceType -> $filterSignature"
            )
            lastLoggedFilterSignatureByFlow[flowKey] = filterSignature
        }

        val expectedSamples = chunkDurationMs?.let { (nativeRate * it) / 1000 }
        val chunkShape = if (expectedSamples != null) {
            "rate=${nativeRate}Hz size=${pcmArray.size} expected=${expectedSamples}"
        } else {
            "rate=${nativeRate}Hz size=${pcmArray.size}"
        }
        val lastShape = lastLoggedChunkShapeByFlow[flowKey]
        val mismatch = expectedSamples != null && pcmArray.size != expectedSamples
        if (chunkIndex <= 1 || lastShape != chunkShape || mismatch) {
            val message = "$flowKey chunk shape chunk=$chunkIndex deviceType=$recordingDeviceType -> $chunkShape"
            if (mismatch) Timber.w(message) else Timber.d(message)
            lastLoggedChunkShapeByFlow[flowKey] = chunkShape
        }

        val targetRate = if (encodingType == CODE_TYPE.AI) AudioRecorderAI.RECORDER_SAMPLE_RATE else WavRecorder.RECORDER_SAMPLE_RATE
        return PttSendManager.preprocessForEncoding(
            pcmArray = pcmArray,
            nativeRate = nativeRate,
            profile = activeProfile,
            applyFilters = true,
            targetRate = targetRate,
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
        wavRecorder?.run {
            file?.let { stopRecording(retry = 0, chatID, it.absolutePath, DataManager.context, carrier) }
            Scopes.getDefaultCoroutine().launch {
                delay(50)
                wavRecorder = null
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

    private fun finishAIRecording(context: Context) {
        Scopes.getDefaultCoroutine().launch {
            delay(3000)
            PttSendManager.finish(context)
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