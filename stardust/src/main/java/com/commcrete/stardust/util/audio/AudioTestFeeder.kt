package com.commcrete.stardust.util.audio

import android.content.Context
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Test-only utility that simulates microphone recording for the AI codec pipeline.
 *
 * Reads one or more audio files captured on different devices (WAV, raw PCM,
 * or any container Android's `MediaCodec` can decode — m4a / mp3 / aac / ogg /
 * flac …) and feeds them into [PttSendManager] in 500 ms chunks — exactly the
 * way `AudioRecorderAI` feeds live frames.
 *
 * Each file is analyzed and logged in great detail (header, statistics per
 * file and per emitted chunk) so audio quality issues can be diagnosed offline.
 *
 * NOT for production use.
 *
 * **Architecture (clean-architecture split):**
 *  - This object is the public **facade** — public API + last-run state.
 *  - **Model** (one class per file, all in this package):
 *    [Source], [LowPassConfig], [NotchConfig], [RnNoiseConfig], [AGCConfig],
 *    [AudioInfo], [AudioStats], [RoundTripResult].
 *  - [AudioFeederEngine]      — orchestration of the per-source pipeline.
 *  - [AudioFileLoader]        — WAV / MediaCodec decoding to mono 16-bit PCM.
 *  - [AudioDsp]               — down-mix, resample, anti-alias, AI gain, FFT.
 *  - [AudioStatsAnalyzer]     — PCM stats + spectral fingerprint + tone alert.
 *  - [RoundTripAnalyzer]      — encode → decode → metrics (PSNR / SI-SDR / LSD).
 *  - [AudioArtifactWriter]    — WAV + token (txt/bin) writers.
 *  - [AudioTestFeederLogger]  — all human-readable diagnostics formatting.
 *
 * Usage:
 * ```
 * AudioTestFeeder.feed(
 *     context = ctx,
 *     pathToSaveFilesIn = "deviceA-run1",
 *     carrier = currentCarrier,
 *     sources = listOf(
 *         Source(File("/sdcard/test/deviceA.wav"), label = "device-A"),
 *         Source(File("/sdcard/test/deviceB.wav"), label = "device-B"),
 *     ),
 *     realtimePacing = true,
 *     // Opt in to actually transmit each encoded chunk over the live
 *     // BLE/USB pathway, exactly like a real PTT recording would:
 *     destinationId = "DEV-001",
 *     withSend = true,
 * )
 * ```
 */
object AudioTestFeeder {

    const val TAG = "AudioTestFeeder"

    /** Target format expected by the AI pipeline (see `AudioRecorderAI`). */
    const val TARGET_SAMPLE_RATE = 24_000
    const val TARGET_CHANNELS = 1
    const val TARGET_BITS_PER_SAMPLE = 16
    const val CHUNK_DURATION_MS = 500L
    const val SAMPLES_PER_CHUNK = (TARGET_SAMPLE_RATE * CHUNK_DURATION_MS / 1000L).toInt() // 12 000

    /** Frequency bands used for [AudioStats.subBandEnergyPct]. Edges in Hz at 24 kHz Fs. */
    val BAND_EDGES_HZ = doubleArrayOf(0.0, 300.0, 1_000.0, 3_400.0, 8_000.0, 12_000.0)
    val BAND_LABELS = arrayOf(
        "0–300 Hz",     // DC / rumble
        "300–1k",       // voice fundamental
        "1k–3.4k",      // telephony band (≤ this = BLE SCO / CVSD)
        "3.4k–8k",      // adds intelligibility ("wideband")
        "8k–12k",       // air / sibilance (only true fullband mics)
    )

    private val feederScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /** Per-source stats from the most recent [feed] call, in feed order. */
    private val lastRunStats = LinkedHashMap<String, AudioStats>()
    fun lastRunStats(): Map<String, AudioStats> = lastRunStats.toMap()

    /** Per-source WavTokenizer round-trip metrics from the most recent [feed] call. */
    private val lastRunRoundTrips = LinkedHashMap<String, RoundTripResult>()
    fun lastRunRoundTrips(): Map<String, RoundTripResult> = lastRunRoundTrips.toMap()

    // ---------------- Public model types ----------------
    //
    // The public model is now spread across one-class-per-file modules in this
    // package (clean-architecture model layer):
    //
    //   • [Source]            — AudioFeederSource.kt
    //   • [LowPassConfig]     — AudioLowPassConfig.kt
    //   • [NotchConfig]       — AudioNotchConfig.kt
    //   • [RnNoiseConfig]     — AudioRnNoiseConfig.kt
    //   • [AGCConfig]         — AudioAgcConfig.kt
    //   • [AudioInfo]         — AudioInfo.kt
    //   • [AudioStats]        — AudioStats.kt
    //   • [RoundTripResult]   — AudioRoundTripResult.kt
    //
    // They keep their original short names so callers continue to use them as
    // `Source(...)`, `LowPassConfig(...)`, etc. (no qualifier needed inside
    // this package; otherwise import them from
    // `com.commcrete.stardust.util.audio`).

    // ---------------- Public API ----------------

    /**
     * Feed the given audio [sources] into the AI pipeline as if they were just
     * recorded. Cancels the previous job (if any) first.
     *
     * @param realtimePacing if true, paces chunks like AudioRecord does;
     *                       if false, pushes them back-to-back.
     * @param roundTrip      if true, also runs each source through the
     *                       WavTokenizer encoder + decoder and logs metrics.
     * @param outputDir      directory for round-trip / filtered artifacts.
     * @param onDone         optional completion callback (IO dispatcher).
     */
    /**
     * Feed the given audio [sources] into the AI pipeline as if they were just
     * recorded. Cancels the previous job (if any) first.
     *
     * @param pathToSaveFilesIn folder name (under the SDK file root) used for
     *                          per-run artifact persistence — independent of
     *                          whether anything is actually transmitted.
     * @param destinationId     real BLE/USB destination ID. Combined with
     *                          [withSend], gates whether each encoded chunk is
     *                          handed off to `DataManager.sendDataToBle(...)`
     *                          via the same path the live PTT recorder uses.
     * @param withSend          when `true` AND [destinationId] is non-blank,
     *                          encoded chunks are transmitted to the device
     *                          identified by [destinationId] using the exact
     *                          same pathway as a live PTT recording (AI path:
     *                          `PttSendManager.sendData` → `SEND_PTT_AI`;
     *                          CODEC2 path: `Codec2ChunkSink.sendPacket` →
     *                          `SEND_PTT`). When `false` (default), the feeder
     *                          only generates artifacts and never touches the
     *                          radio, regardless of [destinationId].
     * @param realtimePacing if true, paces chunks like AudioRecord does;
     *                       if false, pushes them back-to-back.
     * @param roundTrip      if true, also runs each source through the
     *                       WavTokenizer encoder + decoder and logs metrics.
     * @param outputDir      directory for round-trip / filtered artifacts.
     * @param onDone         optional completion callback (IO dispatcher).
     */
    fun feed(
        context: Context,
        carrier: Carrier?,
        codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
        sources: List<Source>,
        realtimePacing: Boolean = true,
        roundTrip: Boolean = false,
        outputDir: File? = null,
        lowPass: LowPassConfig? = null,
        notch: NotchConfig? = null,
        rnNoise: RnNoiseConfig? = null,
        agc: AGCConfig? = null,
        dynamics: DynamicsConfig? = null,
        declick: DeclickConfig? = null,
        /**
         * `true` → AI gain uses tanh soft saturation (no hard clipping at
         * any drive level; loud peaks roll off smoothly past ~50 % FS).
         * `false` (default) → linear gain + int16 hard clip, matches
         * production `AudioRecorderAI.processSamples`. Set to `true` when
         * you want hot AI-encoder input (slider 300–500) without the
         * smeared / square-wave clipping that linear mode produces.
         */
        aiGainSoftSat: Boolean = false,
        destinationId: String? = null,
        withSend: Boolean = false,
        onDone: (() -> Unit)? = null,
    ): Job {
        stop()
        DataManager.requireContext(context)
        val sendTo: String? = destinationId?.takeIf { withSend && it.isNotBlank() }
        val aiSession: com.commcrete.stardust.ai.codec.PttSession? =
            if (codeType == RecorderUtils.CODE_TYPE.AI) {
                val pttInterface: PttInterface? = sendTo?.let { FeederPttInterface(it) }
                PttSendManager.init(context.applicationContext, pttInterface)
                PttSendManager.restart()
            } else {
                null
            }
        val job = feederScope.launch {
            lastRunStats.clear()
            lastRunRoundTrips.clear()
            val effectiveOutputDir = outputDir ?: AudioArtifactWriter.defaultArtifactDir(context, sendTo ?: "artifact-only")
            if (roundTrip || (lowPass?.enabled == true) || (notch?.enabled == true) ||
                (rnNoise?.enabled == true) || (agc?.enabled == true) ||
                (dynamics?.enabled == true) || (declick?.enabled == true)) {
                effectiveOutputDir.mkdirs()
                Timber.tag(TAG).i("Artifacts will be written to: %s", effectiveOutputDir.absolutePath)
            }
            Timber.tag(TAG).i(
                "▶ Starting feeder: %d source(s), codeType=%s, destinationId=%s, withSend=%b (sendTo=%s), carrier=%s, realtime=%b, roundTrip=%b, declick=%s, lowPass=%s, notch=%s, rnNoise=%s, agc=%s, dp=%s",
                sources.size, codeType.name, destinationId, withSend, sendTo,
                carrier?.toString(), realtimePacing, roundTrip,
                declick?.takeIf { it.enabled }?.describe() ?: "off",
                lowPass?.takeIf { it.enabled }?.describe() ?: "off",
                notch?.takeIf { it.enabled }?.describe() ?: "off",
                rnNoise?.takeIf { it.enabled }?.describe() ?: "off",
                agc?.takeIf { it.enabled }?.describe() ?: "off",
                dynamics?.takeIf { it.enabled }?.describe() ?: "off",
            )

            val codec2Sink = if (codeType == RecorderUtils.CODE_TYPE.CODEC2) {
                // `sendTo` is null for artifact-only runs — the sink will
                // still encode/pack but skip `DataManager.sendDataToBle`.
                AudioFeederEngine.createCodec2ChunkSink(context, sendTo, carrier)
            } else {
                null
            }


            try {
                sources.forEachIndexed { idx, src ->
                    if (!isActive) return@forEachIndexed
                    Timber.tag(TAG).i("── [%d/%d] Source: %s (%s)", idx + 1, sources.size, src.label, src.file.absolutePath)
                    AudioFeederEngine.feedSingle(
                        context = context,
                        sendTo = sendTo,
                        carrier = carrier,
                        source = src,
                        codeType = codeType,
                        codec2Sink = codec2Sink,
                        realtimePacing = realtimePacing,
                        roundTrip = roundTrip,
                        artifactDir = effectiveOutputDir,
                        lowPass = lowPass,
                        notch = notch,
                        rnNoise = rnNoise,
                        agc = agc,
                        dynamics = dynamics,
                        declick = declick,
                        aiGainSoftSat = aiGainSoftSat,
                        onStats = { lastRunStats[src.label] = it },
                        onRoundTrip = { lastRunRoundTrips[src.label] = it },
                    )
                }
                AudioTestFeederLogger.logCrossSourceSummary(lastRunStats)
                if (roundTrip) AudioTestFeederLogger.logCrossSourceRoundTrip(lastRunRoundTrips)
                if (codeType == RecorderUtils.CODE_TYPE.AI) {
                    Timber.tag(TAG).i("✔ All sources fed. Calling PttSendManager.finish() in 3s")
                    delay(3_000)
                    // Use the session-aware overload so a newer recording
                    // started in the meantime is unaffected.
                    aiSession?.let { PttSendManager.finish(it) }
                        ?: PttSendManager.finish(context)
                } else {
                    Timber.tag(TAG).i("✔ All sources fed. Flushing CODEC2 sink")
                    codec2Sink?.finish()
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Feeder failed")
            } finally {
                // Detach our feeder-local PttInterface so a subsequent
                // production `RecorderUtils.startAIRecording` call cleanly
                // re-registers DataManager without our stale adapter
                // racing it. Cheap, idempotent.
                if (codeType == RecorderUtils.CODE_TYPE.AI) {
                    if (aiSession != null) {
                        runCatching { PttSendManager.finish(aiSession) }
                        runCatching { PttSendManager.awaitFinalized(aiSession) }
                    }
                    runCatching { PttSendManager.init(context.applicationContext, null) }
                }
                onDone?.invoke()
            }
        }
        currentJob = job
        return job
    }


    /**
     * Minimal [PttInterface] used while [feed] is running with `withSend`
     * enabled. `getDestenation()` returns the id passed to [feed]; everything
     * else delegates to `DataManager` so we go through exactly the same
     * BLE/USB write path as a live PTT recording.
     */
    private class FeederPttInterface(private val dest: String) : PttInterface {
        override fun getSource(): String = DataManager.getSource()
        override fun getDestenation(): String = dest
        override fun sendDataToBle(bittelPackage: StardustPackage) {
            DataManager.sendDataToBle(bittelPackage)
        }
    }


    /** Stop the currently running feeder, if any. */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
    }

    /** Inspect an audio file (load + analyze + log) without feeding it. */
    fun inspect(source: Source): Pair<AudioInfo, AudioStats> {
        val loaded: Pair<AudioInfo, ShortArray> = AudioFileLoader.loadAndNormalize(source)
        val info: AudioInfo = loaded.first
        val pcm: ShortArray = loaded.second
        AudioTestFeederLogger.logAudioInfo(info)
        val stats: AudioStats = AudioStatsAnalyzer.computeStats(pcm, source)
        AudioTestFeederLogger.logAudioStats(source.label, stats)
        return info to stats
    }

    /** Convenience: feed a list of file paths without building [Source] manually. */
    fun feedFiles(
        context: Context,
        destination: String,
        carrier: Carrier?,
        codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
        files: List<File>,
        realtimePacing: Boolean = true,
        roundTrip: Boolean = false,
        outputDir: File? = null,
        destinationId: String? = null,
        withSend: Boolean = false,
    ): Job = feed(
        context = context,
        carrier = carrier,
        codeType = codeType,
        sources = files.map { Source(it) },
        realtimePacing = realtimePacing,
        roundTrip = roundTrip,
        outputDir = outputDir,
        destinationId = destinationId,
        withSend = withSend,
    )
}

