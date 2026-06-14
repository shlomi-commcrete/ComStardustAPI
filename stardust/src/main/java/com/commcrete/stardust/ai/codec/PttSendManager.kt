package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.media.WavHelper
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor
import com.commcrete.stardust.util.audio.AGCConfig
import com.commcrete.stardust.util.audio.AGCFilter
import com.commcrete.stardust.util.audio.AiRecorderProfile
import com.commcrete.stardust.util.audio.AudioDsp
import com.commcrete.stardust.util.audio.DeclickConfig
import com.commcrete.stardust.util.audio.DeclickFilter
import com.commcrete.stardust.util.audio.DynamicsConfig
import com.commcrete.stardust.util.audio.DynamicsProcessingFilter
import com.commcrete.stardust.util.audio.LowPassConfig
import com.commcrete.stardust.util.audio.LowPassFilter
import com.commcrete.stardust.util.audio.NotchConfig
import com.commcrete.stardust.util.audio.NotchFilter
import com.commcrete.stardust.util.audio.RnNoiseConfig

object PttSendManager {

    /**
     * Optional per-chunk hook for capturing the decoded (post-WavTokenizer)
     * PCM as it is produced — i.e. the AI **output** that corresponds 1:1
     * to each [addNewFrame] input chunk.
     *
     * Set this to a non-null callback (e.g. from `AudioFeederEngine` during
     * a test-feed run) to receive every decoded chunk in encode order, and
     * clear (set back to null) when capture is finished. Call
     * [resetLiveDecodeState] between sessions to drop residual state.
     *
     * Implementation note: when this hook is registered, [handleTokenizerChunk]
     * runs **one** decode per encoded chunk and shares the resulting PCM
     * with [saveTofile]. Doing it in two independent decode passes corrupts
     * the shared `WavTokenizerDecoder` instance state (`cutTokens`, `index`),
     * because each pass reads what the OTHER pass wrote on the previous
     * chunk — producing garbled output even though each stream's own
     * continuity (`lastTokens` / `lastPCM`) looks fine.
     */
    @Volatile
    var onDecodedChunk: ((ShortArray) -> Unit)? = null

    /**
     * Drop the per-chunk decoder continuity state ([lastTokens] / [lastPCM])
     * AND any buffered [frameBuffer]. Call between sessions / per source so
     * the next stream starts with a clean previousTokens=null path through
     * `WavTokenizerDecoder.decode` (no stale head-cut from a different stream).
     *
     * Also resets the shared [wavTokenizerDecoder]'s **internal** per-stream
     * state ([WavTokenizerDecoder.reset] — `index`, `cutTokens`, `loop`,
     * `listEnergy`). Without this, `cutTokens` from the last chunk of the
     * previous stream leaks into [WavTokenizerDecoder.handleSmart] for the
     * next stream's first chunk, slicing the wrong amount off the head and
     * producing progressively less intelligible output across feed runs.
     */
    fun resetLiveDecodeState() {
        lastTokens = null
        lastPCM = null
        frameBuffer.clear()
        wavTokenizerDecoder.reset()
    }

    private val TAG = "PttManager"
    private val TAG_DECODE = "PttManager_Decode"
    private val TAG_ENCODE = "PttManager_Encode"
    private var coroutineScope = CoroutineScope(Dispatchers.Default) // Scope for decoding and frame dropping
    private var encodingJob: Job? = null
    private var toEncodeQueue = Channel<ShortArray>(Channel.UNLIMITED) // Equivalent to m_packet_queue using a Channel
    private var wavTokenizerEncoder: WavTokenizerEncoder= AIModuleInitializer.wavTokenizerEncoder
    @SuppressLint("StaticFieldLeak")
    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder
    private lateinit var cacheDir: File
    private var fileToSave: File? = null
    var carrier : Carrier? = null
    var chatID: String? = null
    private var viewModel : PttInterface? = null
    var aiEnabled = false

    fun init(context: Context, viewModel : PttInterface? = null) {
        cacheDir = context.cacheDir
        this.viewModel = viewModel
        startEncodingJob(context)
        aiEnabled = true
//        AudioDebugTest(context, wavTokenizerEncoder, wavTokenizerDecoder).runTest()
    }

    /**
     * Queue [pcmArray] for AI encoding.
     *
     * - [nativeRate]: sample rate of the incoming PCM. Resampled to the
     *   encoder's 24 kHz target when it differs.
     * - [profile]: optional DSP config for this session's device type.
     *   `null` → use no-arg defaults (RNNoise + DP + LPF on, others off).
     * - [applyFilters]: set `false` when the caller already ran its own
     *   DSP chain (e.g. the test feeder), to prevent double-filtering.
     */
    fun addNewFrame(
        pcmArray: ShortArray,
        file: File,
        carrier: Carrier? = null,
        chatID: String? = null,
        applyFilters: Boolean = true,
        nativeRate: Int = TARGET_SAMPLE_RATE,
        profile: AiRecorderProfile? = null,
    ) {
        val toQueue = preprocessForEncoding(
            pcmArray = pcmArray,
            nativeRate = nativeRate,
            profile = profile,
            applyFilters = applyFilters,
            targetRate = TARGET_SAMPLE_RATE,
        )
        toEncodeQueue.trySend(toQueue)
        fileToSave = file
        this.carrier = carrier
        this.chatID = chatID
    }

    /**
     * Shared PCM preprocessing for both AI and CODEC2 paths.
     * Applies profile-driven DSP at [nativeRate], then optionally resamples to [targetRate].
     */
    fun preprocessForEncoding(
        pcmArray: ShortArray,
        nativeRate: Int,
        profile: AiRecorderProfile? = null,
        applyFilters: Boolean = true,
        targetRate: Int = TARGET_SAMPLE_RATE,
    ): ShortArray {
        val filtered: ShortArray = if (applyFilters) {
            val mutable = pcmArray.copyOf()
            applyFilterChain(mutable, nativeRate, profile)
            mutable
        } else {
            pcmArray
        }
        return if (nativeRate != targetRate) {
            AudioDsp.resampleLinear(filtered, nativeRate, targetRate)
        } else {
            filtered
        }
    }

    fun finish(context: Context) {
        saveTofile(context, byteArrayOf(), finish = true) // Need to delete
    }

    private fun startEncodingJob(context: Context) {
        if (!aiEnabled) return  // hard guard everywhere you might touch PyTorch
        encodingJob = coroutineScope.launch {
            while (isActive) { // Keep the decoding loop active
                try {
                    // Offer the packet to the channel without suspending if the channel is not full
                    // If the channel was limited, offer might return false or suspend
                    val pcmArray = toEncodeQueue.tryReceive().getOrNull() // Attempt to receive without suspending

                    if (pcmArray != null) {
                        handleTokenizerChunk(context, pcmArray)
                        Log.d(TAG, "Codec decoding loop iteration completed.")
                    }

                } catch (e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec exception during decoding: ${e.diagnosticInfo}", e)
                    break // Exit the decoding loop on error
                } catch (e: Exception) {
                    Log.e(TAG, "Error in decoding loop: ${e.message}", e)
                    break // Exit the decoding loop on other errors
                }

                // Small delay to prevent a tight loop from consuming too much CPU if no buffers are available
                delay(10) // Adjust delay as needed
            }
            Log.d(TAG, "Decoding job finished.")
        }
    }

    private fun handleTokenizerChunk(context: Context, pcmArray: ShortArray) {
        Log.d(TAG_ENCODE, "Encoding PCM pcmArray of size ${pcmArray.size}")
        val chunkCodes = wavTokenizerEncoder.encode(pcmArray)
        Log.d(TAG_ENCODE, "Tokenizer encoded chunk size")
        Log.d(TAG, "Encoded chunk size ${chunkCodes.size}")

        val packedData = BitPacking12.pack12(chunkCodes.toList())

        sendData(context, packedData)

        // ── Per-chunk decode (single source of truth) ────────────────────
        // Both consumers (onDecodedChunk debug hook AND saveTofile's 45 s
        // buffered WAV) need the decoded PCM for THIS encoded chunk. We
        // MUST run that decode at most once per chunk, because
        // WavTokenizerDecoder is a shared singleton that mutates private
        // instance state (`cutTokens`, `index`) on every call. Two
        // independent decode streams against the same instance silently
        // clobber each other's `cutTokens` between chunks → wrong head-cut
        // in `handleSmart` → garbled output, even though each stream's
        // own `previousTokens` / `previousSamples` continuity looks fine.
        // See `WavTokenizerDecoder.handleSmart` for the math.
        val decodedSink = onDecodedChunk
        val savingToFile = needToRun && DataManager.getSavePTTFilesRequired(context)
        var decodedPcm: ShortArray? = null
        if (decodedSink != null || savingToFile) {
            try {
                val unpack = BitPacking12.unpack12(packedData)
                @Suppress("DEPRECATION")
                val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)
                val pcm = wavTokenizerDecoder.decode(unpack, lastTokens, lastPCM, modelTypeSelected)
                lastTokens = unpack
                lastPCM = pcm
                decodedPcm = pcm
                decodedSink?.invoke(pcm)
            } catch (t: Throwable) {
                Log.w(TAG_DECODE, "per-chunk decode failed", t)
            }
        }

        saveTofile(context, packedData, decodedPcm = decodedPcm) // Need to delete
    }

    // Equivalent to private void SendData(byte[] data)
    private fun sendData(context: Context, data: ByteArray) {
        Log.d(TAG, "Send msg: ${data.size} data: ${data.toHexString()}")
        val radio = CarriersUtils.getRadioToSend(carrier, functionalityType = FunctionalityType.PTT) ?: return

        Scopes.getDefaultCoroutine().launch {
            val bittelPackage = viewModel?.let {
                val fullData = ByteArray(data.size + 1)
                fullData[0] = 0x00
                System.arraycopy(data, 0, fullData, 1, data.size)
                val audioIntArray = StardustPackageUtils.byteArrayToIntArray(fullData)
                StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = it.getSource(),
                    destenation = it.getDestenation() ?: "" ,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_PTT_AI,
                    data = audioIntArray)
            }
            val isLast = data.size != 30


            bittelPackage?.let { bittelPackage ->
                bittelPackage.stardustControlByte.stardustPartType = if( isLast) StardustControlByte.StardustPartType.LAST else StardustControlByte.StardustPartType.MESSAGE
                bittelPackage.stardustControlByte.stardustDeliveryType = radio.second
                bittelPackage.checkXor = StardustPackageUtils.getCheckXor(bittelPackage.getStardustPackageToCheckXor())
                DataManager.sendDataToBle(bittelPackage)
            }

        }

    }

    private fun savePtt(chatID : String, path : String, context: Context){
        Scopes.getDefaultCoroutine().launch {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let {
                val chatsRepo = DataManager.getChatsRepo(context)
                val chatItem = chatsRepo.getChatByBittelID(chatID)
                chatItem?.message = Message(
                    senderID = it,
                    text = "PTT Sent",
                    seen = true
                )
                chatItem?.let { chatsRepo.addChat(it) }
                DataManager.getMessagesRepo(DataManager.context).saveMessage(
                    context = context,
                    isPTT = true,
                    messageItem = MessageItem(senderID = it,
                        epochTimeMs = RecorderUtils.ts, senderName = "" ,
                        chatId = chatID, text = "", fileLocation = path,
                        isAudio = true, seen = SeenStatus.SENT, audioType = RecorderUtils.CODE_TYPE.AI.id)
                )
            }
            RecorderUtils.ts = 0
        }
    }

    fun restart () {
        isFirst = true
        startRecording = 0L
        needToRun = true
        // resetLiveDecodeState() clears frameBuffer, lastPCM and lastTokens —
        // keeping a single source of truth for the decode-side reset.
        resetLiveDecodeState()
        // Tear down the pre-encode filter chain too — the next session
        // re-builds with fresh biquad / envelope / RNNoise state so a
        // previous session's residual tail doesn't leak in.
        releaseFilters()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }

    private var isFirst = true;
    private var startRecording = 0L
    private var needToRun = true;
    private val frameBuffer = mutableListOf<ShortArray>()
    private var lastPCM  : ShortArray? = null
    private var lastTokens  : List<Long>? = null
    private fun saveTofile(
        context: Context,
        packData: ByteArray,
        finish: Boolean = false,
        decodedPcm: ShortArray? = null,
    ) {
        Log.d(TAG, "saveTofile called with data size: ${packData.size}")
        if (!needToRun || !DataManager.getSavePTTFilesRequired(context)) return

        if (isFirst) {
            isFirst = false
            startRecording = System.currentTimeMillis()
        }
        Log.d(TAG_DECODE, "Processing packData of size: ${packData.size}")

        val finalPcmData: ShortArray = if (decodedPcm != null) {
            decodedPcm
        } else {
            val unpack = BitPacking12.unpack12(packData)
            @Suppress("DEPRECATION")
            val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)
            val pcm = wavTokenizerDecoder.decode(unpack, lastTokens, lastPCM, modelTypeSelected)
            lastTokens = unpack
            lastPCM = pcm
            pcm
        }
        Log.d(TAG_DECODE, "Decoded PCM data size ${finalPcmData.size}")
        frameBuffer.add(finalPcmData)

        if ((System.currentTimeMillis() - startRecording > 45000) || finish) {
            Log.d(TAG, "3 seconds elapsed, saving to file.")
            needToRun = false
            val file = fileToSave
            try {
                file?.let {
                    val sampleArray = frameBuffer.flatMap { it.asIterable() }.toShortArray()
                    WavHelper.createWavFile(sampleArray, 24000, file)



                    Log.d(TAG, "WAV file created: ${file.absolutePath}")
                    runCatching {
                        val artifactDir = RecorderUtils.dirToSaveFile
                        val srcParent = file.parentFile?.canonicalFile
                        val dstParent = artifactDir.canonicalFile
                        if (srcParent != dstParent) {
                            if (!artifactDir.exists()) artifactDir.mkdirs()
                            // Force a `.wav` extension on the mirror — the
                            // production sinkFile uses `.pcm` (createSinkFile
                            // in AudioFeederEngine) even though the bytes are
                            // a real RIFF/WAVE container, which confuses
                            // Audacity / ffmpeg auto-detect.
                            val mirrorName = "${file.nameWithoutExtension}-ptt_finish.wav"
                            val mirror = File(artifactDir, mirrorName)
                            file.copyTo(mirror, overwrite = true)
                            Log.d(TAG, "WAV mirror copied: ${mirror.absolutePath}")
                        }
                    }.onFailure { Log.w(TAG, "WAV mirror copy failed", it) }

                    savePtt(context = DataManager.context, chatID = chatID ?:"", path = fileToSave?.absolutePath?:"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating WAV file", e)
            }
        }
    }


    // ──────────────────────────────────────────────────────────────────────
    // Pre-encode DSP filter chain
    // ──────────────────────────────────────────────────────────────────────
    //
    // Mirrors the live per-chunk chain assembled in
    // `AudioFeederEngine.LiveFilterChain.processChunkInPlace`:
    //
    //     Declick → Notch → RNNoise → DP → AGC → AI-gain → LPF
    //
    // Built lazily on the first filtered chunk and torn down by [restart].
    // Stage parameters come from the no-arg constructors of the public
    // config classes — those are the user-tuned voice-focus defaults
    // (DP voice-focus DP-only, LPF voice-band, RNNoise default). AGC,
    // declick and notch are intentionally NOT included by default so the
    // chain stays consistent with the DP-only voice-focus preset; if you
    // need them, adjust the relevant `*Config` defaults or extend the
    // builder below.

    /** Sample rate the AI encoder requires (`addNewFrame` resamples to this). */
    private const val TARGET_SAMPLE_RATE = 24_000

    private var declickFilter: DeclickFilter? = null
    private var notchFilter: NotchFilter? = null
    private var rnNoiseProcessor: RnNoiseProcessor? = null
    private var dynamicsFilter: DynamicsProcessingFilter? = null
    private var agcFilter: AGCFilter? = null
    private var lpf: LowPassFilter? = null

    /** RNNoise wet/dry mix in `[0,1]` (1 = full clean, 0 = bypass). */
    private var rnNoiseMix: Float = 1f
    /** RNNoise max-attenuation linear floor (0 = disabled). */
    private var rnNoiseAttenFloor: Float = 0f
    /** Post-AGC make-up multiplier (1 = passthrough). Reserved hook. */
    private var filterAiGain: Float = 1f
    private var filterAiGainSoftSat: Boolean = false

    /**
     * Sample rate the currently-built filter chain runs at. Set on first
     * build; if a later [addNewFrame] call arrives with a different
     * [sampleRate], the chain is torn down and rebuilt so biquad / one-pole
     * coefficients (and RNNoise's internal resampler) are correct for the
     * new rate. `-1` means no chain built yet.
     */
    @Volatile private var currentFilterRate: Int = -1
    /** Key that captures which profile the chain was built for. */
    @Volatile private var currentFilterKey: AiRecorderProfile? = null
    @Volatile private var filtersBuilt: Boolean = false

    private fun ensureFiltersBuilt(sampleRate: Int, profile: AiRecorderProfile?) {
        if (filtersBuilt && currentFilterRate == sampleRate && currentFilterKey == profile) return
        synchronized(this) {
            if (filtersBuilt && currentFilterRate == sampleRate && currentFilterKey == profile) return
            if (filtersBuilt) {
                Log.i(
                    TAG,
                    "Filter chain changed (rate/profile): ${currentFilterRate}Hz → ${sampleRate}Hz, rebuilding",
                )
                releaseFilters()
            }
            currentFilterRate = sampleRate
            currentFilterKey = profile

            // Profile provides the DSP configs; null fields fall back to
            // no-arg defaults so the chain always has the three core stages.
            val rn: RnNoiseConfig? = (profile?.rnNoise ?: RnNoiseConfig()).takeIf { it.enabled }
            val dp: DynamicsConfig? = (profile?.dynamics ?: DynamicsConfig()).takeIf { it.enabled }
            val lp: LowPassConfig? = (profile?.lowPass ?: LowPassConfig()).takeIf { it.enabled }
            // Opt-in stages: only enabled when the profile explicitly provides them.
            val notch: NotchConfig?    = profile?.notch?.takeIf { it.enabled }
            val agc: AGCConfig?        = profile?.agc?.takeIf { it.enabled }

            declickFilter   = null
            notchFilter     = notch?.let { NotchFilter(sampleRate, it.resolveBands()) }
            rnNoiseProcessor = rn?.let { RnNoiseProcessor().apply { init(sampleRate) } }
            rnNoiseMix        = rn?.mixClamped ?: 1f
            rnNoiseAttenFloor = rn?.attenuationFloorLin ?: 0f
            dynamicsFilter   = dp?.let { DynamicsProcessingFilter(sampleRateHz = sampleRate, config = it) }
            agcFilter = agc?.let {
                AGCFilter(
                    sampleRateHz = sampleRate,
                    targetLevel = it.targetLevel,
                    attackMs = it.attackMs,
                    releaseMs = it.releaseMs,
                    maxGainDb = it.maxGainDb,
                    minGainDb = it.minGainDb,
                    noiseGateLevel = it.noiseGateLevel,
                )
            }
            lpf = lp?.let {
                LowPassFilter(
                    sampleRateHz = sampleRate,
                    cutoffHz = it.cutoffHz,
                    rollOffDbPerOctave = it.rollOffDbPerOctave,
                )
            }
            filtersBuilt = true
        }
    }

    private fun applyFilterChain(chunk: ShortArray, sampleRate: Int, profile: AiRecorderProfile?) {
        ensureFiltersBuilt(sampleRate, profile)
        declickFilter?.processInPlace(chunk)
        notchFilter?.processInPlace(chunk)
        rnNoiseProcessor?.let { proc ->
            // Optional wet/dry blend + magnitude floor (same math as
            // LiveFilterChain.softenRnNoise so output is bit-comparable).
            val needsBlend = rnNoiseMix < 1f || rnNoiseAttenFloor > 0f
            val dry = if (needsBlend) chunk.copyOf() else null
            proc.process(chunk, chunk.size)
            if (dry != null) softenRnNoise(chunk, dry, rnNoiseMix, rnNoiseAttenFloor)
        }
        dynamicsFilter?.processInPlace(chunk)
        agcFilter?.processInPlace(chunk)
        if (filterAiGain != 1f) {
            if (filterAiGainSoftSat) AudioDsp.applyAiGainSoftSatInPlace(chunk, filterAiGain)
            else AudioDsp.applyAiGainInPlace(chunk, filterAiGain)
        }
        lpf?.processInPlace(chunk)
    }

    /**
     * Identical to `AudioFeederEngine.LiveFilterChain.softenRnNoise`:
     * clamp each cleaned sample's magnitude to be at least [floor] of the
     * dry sample's magnitude, then linearly blend cleaned vs. dry by [mix].
     */
    private fun softenRnNoise(wet: ShortArray, dry: ShortArray, mix: Float, floor: Float) {
        val n = minOf(wet.size, dry.size)
        for (i in 0 until n) {
            val d = dry[i].toInt()
            var w = wet[i].toInt()
            if (floor > 0f && d != 0) {
                val absDry = if (d < 0) -d else d
                val minAbs = (absDry * floor).toInt()
                val absWet = if (w < 0) -w else w
                if (absWet < minAbs) {
                    val sign = when {
                        w > 0 -> 1
                        w < 0 -> -1
                        d >= 0 -> 1
                        else -> -1
                    }
                    w = sign * minAbs
                }
            }
            val blended = if (mix >= 1f) w
            else (w * mix + d * (1f - mix)).toInt()
            wet[i] = blended.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Release native resources held by the chain (RNNoise's denoise state in
     * particular) and clear the per-stage filter instances so the next
     * session rebuilds with fresh state. Idempotent.
     */
    private fun releaseFilters() {
        runCatching { rnNoiseProcessor?.release() }
            .onFailure { Log.w(TAG, "RnNoise release failed", it) }
        rnNoiseProcessor = null
        declickFilter = null
        notchFilter = null
        dynamicsFilter = null
        agcFilter = null
        lpf = null
        rnNoiseMix = 1f
        rnNoiseAttenFloor = 0f
        filterAiGain = 1f
        filterAiGainSoftSat = false
        currentFilterRate = -1
        currentFilterKey = null
        filtersBuilt = false
    }
}