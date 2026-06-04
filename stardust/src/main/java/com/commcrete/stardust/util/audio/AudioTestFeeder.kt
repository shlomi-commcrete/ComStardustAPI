package com.commcrete.stardust.util.audio

import android.content.Context
import android.util.Log
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.UsersUtils.mRegisterUser
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
 *     destination = "DEV-001",
 *     carrier = currentCarrier,
 *     sources = listOf(
 *         Source(File("/sdcard/test/deviceA.wav"), label = "device-A"),
 *         Source(File("/sdcard/test/deviceB.wav"), label = "device-B"),
 *     ),
 *     realtimePacing = true,
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
    fun feed(
        context: Context,
        destination: String,
        carrier: Carrier?,
        sources: List<Source>,
        realtimePacing: Boolean = true,
        roundTrip: Boolean = false,
        outputDir: File? = null,
        lowPass: LowPassConfig? = null,
        notch: NotchConfig? = null,
        rnNoise: RnNoiseConfig? = null,
        agc: AGCConfig? = null,
        onDone: (() -> Unit)? = null,
    ): Job {
        stop()
        DataManager.requireContext(context)
        PttSendManager.init(context.applicationContext)
        PttSendManager.restart()
        val job = feederScope.launch {
            lastRunStats.clear()
            lastRunRoundTrips.clear()
            val effectiveOutputDir = outputDir ?: AudioArtifactWriter.defaultArtifactDir(context, destination)
            if (roundTrip || (lowPass?.enabled == true) || (notch?.enabled == true) ||
                (rnNoise?.enabled == true) || (agc?.enabled == true)) {
                effectiveOutputDir.mkdirs()
                Timber.tag(TAG).i("Artifacts will be written to: %s", effectiveOutputDir.absolutePath)
            }
            Timber.tag(TAG).i(
                "▶ Starting feeder: %d source(s), destination=%s, carrier=%s, realtime=%b, roundTrip=%b, lowPass=%s, notch=%s, rnNoise=%s, agc=%s",
                sources.size, destination, carrier?.toString(), realtimePacing, roundTrip,
                lowPass?.takeIf { it.enabled }?.let { "${it.cutoffHz}Hz/${it.rollOffDbPerOctave}dBoct" } ?: "off",
                notch?.takeIf { it.enabled }?.describe() ?: "off",
                rnNoise?.takeIf { it.enabled }?.describe() ?: "off",
                agc?.takeIf { it.enabled }?.describe() ?: "off"
            )

            //val checkTicker = heartBeatTest(context, destination, carrier)

            try {
                sources.forEachIndexed { idx, src ->
                    if (!isActive) return@forEachIndexed
                    Timber.tag(TAG).i("── [%d/%d] Source: %s (%s)", idx + 1, sources.size, src.label, src.file.absolutePath)
                    AudioFeederEngine.feedSingle(
                        context, destination, carrier, src,
                        realtimePacing, roundTrip, effectiveOutputDir,
                        lowPass, notch, rnNoise, agc,
                        onStats = { lastRunStats[src.label] = it },
                        onRoundTrip = { lastRunRoundTrips[src.label] = it },
                    )
                }
                AudioTestFeederLogger.logCrossSourceSummary(lastRunStats)
                if (roundTrip) AudioTestFeederLogger.logCrossSourceRoundTrip(lastRunRoundTrips)
                Timber.tag(TAG).i("✔ All sources fed. Calling PttSendManager.finish() in 3s")
                delay(3_000)
                PttSendManager.finish(context)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Feeder failed")
            } finally {
                // Hard-stop the heartbeat. The structured-concurrency cancel
                // would happen on its own when this `launch` block returns,
                // but doing it explicitly makes the lifetime obvious and lets
                // us log it.
//                if (checkTicker.isActive) {
//                    checkTicker.cancel()
//                    Timber.tag(TAG).i("⏱ Stopped 'check' heartbeat")
//                }
                onDone?.invoke()
            }
        }
        currentJob = job
        return job
    }

    private fun CoroutineScope.heartBeatTest(context: Context, destination: String, carrier: Carrier?): Job {

        // ── "check" heartbeat ─────────────────────────────────────────────
        // Spam a "check" text message every 200 ms while the feeder is
        // running so we can see in the device logs / on the receiver side
        // whether the messaging path stays alive concurrently with the AI
        // PTT pipeline. The ticker is a CHILD coroutine of the feed job,
        // so it is automatically cancelled when the feed finishes (or is
        // cancelled via [stop]) — see the `cancel()` in `finally` below
        // for the explicit hard-stop.
        return launch {
            val checkSource = mRegisterUser?.appId.orEmpty()
            val checkPackage = StardustAPIPackage(
                source = checkSource,
                destination = "00000002",
                requireAck = false,
                carrier = carrier,
            )
            Log.d("PTT DEBUG", "Starting 'check' heartbeat every 50 ms → %s")
            while (isActive) {
                try {
                    DataManager.sendMessage(context, checkPackage, "check")
                } catch (t: Throwable) {
                    Log.d("PTT DEBUG", "'check' heartbeat sendMessage failed")
                }
                delay(50)
            }
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
        files: List<File>,
        realtimePacing: Boolean = true,
        roundTrip: Boolean = false,
        outputDir: File? = null,
    ): Job = feed(
        context = context,
        destination = destination,
        carrier = carrier,
        sources = files.map { Source(it) },
        realtimePacing = realtimePacing,
        roundTrip = roundTrip,
        outputDir = outputDir,
    )
}

