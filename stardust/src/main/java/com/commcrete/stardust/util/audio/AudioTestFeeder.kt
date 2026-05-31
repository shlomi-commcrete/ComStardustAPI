package com.commcrete.stardust.util.audio

import android.content.Context
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Test-only utility that simulates microphone recording for the AI codec pipeline.
 *
 * Reads one or more audio files captured on different devices (WAV or raw PCM)
 * and feeds them into [PttSendManager] in 500 ms chunks – exactly the way
 * [com.commcrete.stardust.ai.codec.AudioRecorderAI] feeds live frames.
 *
 * Each file is analyzed and logged in great detail (header, statistics per file
 * and per emitted chunk) so audio quality issues can be diagnosed offline.
 *
 * NOT for production use.
 *
 * Usage:
 * ```
 * AudioTestFeeder.feed(
 *     context = ctx,
 *     destination = "DEV-001",
 *     carrier = currentCarrier,
 *     sources = listOf(
 *         AudioTestFeeder.Source(File("/sdcard/test/deviceA.wav"), label = "device-A"),
 *         AudioTestFeeder.Source(File("/sdcard/test/deviceB.wav"), label = "device-B"),
 *     ),
 *     realtimePacing = true,
 * )
 * ```
 */
object AudioTestFeeder {

    private const val TAG = "AudioTestFeeder"

    /** Target format expected by the AI pipeline (see [com.commcrete.stardust.ai.codec.AudioRecorderAI]). */
    const val TARGET_SAMPLE_RATE = 24_000
    const val TARGET_CHANNELS = 1
    const val TARGET_BITS_PER_SAMPLE = 16
    const val CHUNK_DURATION_MS = 500L
    const val SAMPLES_PER_CHUNK = (TARGET_SAMPLE_RATE * CHUNK_DURATION_MS / 1000L).toInt() // 12 000

    private val feederScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /** Per-source stats from the most recent [feed] call, in feed order. */
    private val lastRunStats = LinkedHashMap<String, AudioStats>()
    fun lastRunStats(): Map<String, AudioStats> = lastRunStats.toMap()

    /** Per-source WavTokenizer round-trip metrics from the most recent [feed] call (when enabled). */
    private val lastRunRoundTrips = LinkedHashMap<String, RoundTripResult>()
    fun lastRunRoundTrips(): Map<String, RoundTripResult> = lastRunRoundTrips.toMap()

    /**
     * Describes a single audio file to inject into the pipeline.
     *
     * @param file        WAV (RIFF/WAVE PCM 16-bit) or raw PCM file.
     * @param label       Free-text label used in logs (e.g. "Pixel 6", "Samsung S22").
     * @param rawPcm      If true, [file] is treated as raw signed-16-bit-LE PCM (no header).
     * @param rawSampleRate / [rawChannels]: only used when [rawPcm] is true.
     */
    data class Source(
        val file: File,
        val label: String = file.nameWithoutExtension,
        val rawPcm: Boolean = false,
        val rawSampleRate: Int = TARGET_SAMPLE_RATE,
        val rawChannels: Int = TARGET_CHANNELS,
    )

    /**
     * Optional [LowPassFilter] stage applied to every emitted chunk **after**
     * the [NotchConfig], [RnNoiseConfig] and [AGCConfig] stages and
     * **immediately before** the chunk is handed to the AI encoder via
     * [PttSendManager.addNewFrame]. It is the LAST DSP stage in the chain,
     * so it band-limits whatever the AGC produced (including any
     * high-frequency artefacts it may have boosted) before encoding.
     *
     * When [enabled] is true the feeder will, per source:
     *  - create one [LowPassFilter] instance (state carried across chunks),
     *  - filter each chunk in place,
     *  - accumulate the filtered samples and write a `<label>-lowpass.wav`
     *    file next to the other round-trip artifacts so the post-filter
     *    signal can be inspected offline.
     *
     * Cutoff is the -3 dB corner frequency in Hz; roll-off is the desired
     * stop-band slope in dB / octave (6 dB → 1st-order, 12 → 2nd, etc.).
     */
    data class LowPassConfig(
        val enabled: Boolean = true,
        val cutoffHz: Float = 2_000f,
        val rollOffDbPerOctave: Float = 12f,
    )

    /**
     * Optional [NotchFilter] stage applied to every emitted chunk **first**
     * in the DSP chain (before [RnNoiseConfig], [AGCConfig], [LowPassConfig]
     * and the AI encoder). Useful for removing tonal contaminants such as
     * the jbox "piiii" whine, mains hum (50/60 Hz) and their harmonics
     * ("symphonies") before they reach RNNoise / AGC and bias their
     * estimates.
     *
     * The caller chooses **exactly one** of two configuration modes:
     *
     *  1. **Uniform harmonics** – set [fundamentalHz], [q] and [numHarmonics]
     *     (and leave [harmonics] = null). The feeder will notch
     *     `fundamentalHz · h` for `h ∈ 1..numHarmonics`, all sharing the same Q.
     *
     *  2. **Per-harmonic control** – set [harmonics] to a non-empty list of
     *     [Harmonic] specs (and leave [numHarmonics] = null). Each entry has
     *     its own frequency and its own Q, so you can e.g. use a very narrow
     *     notch on the fundamental and wider notches on the higher harmonics.
     *
     * Trying to set both, or neither, throws [IllegalArgumentException] when
     * the feeder is started.
     *
     * @param enabled        master toggle for the stage.
     * @param fundamentalHz  used only in uniform-harmonics mode (mode 1).
     * @param q              shared Q for mode 1.
     * @param numHarmonics   number of integer harmonics to remove in mode 1
     *                       (must be `null` in mode 2).
     * @param harmonics      explicit per-band specs for mode 2
     *                       (must be `null` in mode 1).
     */
    data class NotchConfig(
        val enabled: Boolean = true,
        val fundamentalHz: Float = 1_000f,
        val q: Float = 30f,
        val numHarmonics: Int? = 1,
        val harmonics: List<Harmonic>? = null,
    ) {
        /** One explicit notch band: a target frequency and its Q. */
        data class Harmonic(val frequencyHz: Float, val q: Float)

        /**
         * Validate the mutually-exclusive configuration and return the
         * resolved band list that should actually be fed to [NotchFilter].
         */
        internal fun resolveBands(): List<NotchFilter.Band> {
            val hasUniform = numHarmonics != null
            val hasExplicit = harmonics != null
            require(hasUniform xor hasExplicit) {
                "NotchConfig: set exactly ONE of `numHarmonics` (uniform mode) " +
                    "or `harmonics` (per-harmonic mode); got numHarmonics=$numHarmonics, " +
                    "harmonics=${harmonics?.size ?: "null"}."
            }
            return if (hasExplicit) {
                require(harmonics!!.isNotEmpty()) {
                    "NotchConfig.harmonics must be non-empty in per-harmonic mode."
                }
                harmonics.map { NotchFilter.Band(it.frequencyHz, it.q) }
            } else {
                NotchFilter.harmonicsToBands(fundamentalHz, q, numHarmonics!!)
            }
        }

        /** Short human-readable summary for logs. */
        internal fun describe(): String = if (harmonics != null) {
            "explicit[${harmonics.joinToString(",") { "${it.frequencyHz.toInt()}Hz@Q${it.q.toInt()}" }}]"
        } else {
            "${fundamentalHz.toInt()}Hz/Q=${q.toInt()}/x${numHarmonics}"
        }
    }

    /**
     * Optional RNNoise-based denoiser stage applied to every emitted chunk
     * **after** the [NotchConfig] stage and **before** the [AGCConfig] /
     * [LowPassConfig] / AI encoder. Uses
     * [com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor], which
     * resamples to 48 kHz internally, runs RNNoise's 480-sample frames,
     * and writes the cleaned audio back in place at [TARGET_SAMPLE_RATE].
     *
     * Placement rationale: removing wideband background noise before the
     * AGC stops the AGC from boosting that noise during quiet sections.
     *
     * If the native `librnnoise_jni.so` is missing (RNNoise sources not
     * fetched / CMake didn't build), the processor falls back to
     * pass-through and the feeder logs a warning — the stage stays
     * "enabled" but does nothing.
     *
     * The feeder will, per source:
     *  - create one [com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor]
     *    (state carried across chunks; tail samples held internally),
     *  - process each chunk in place,
     *  - accumulate the cleaned samples and write a `<label>-rnnoise.wav`
     *    file next to the other artifacts so the denoised signal can be
     *    inspected offline,
     *  - call `release()` after the last chunk.
     */
    data class RnNoiseConfig(
        val enabled: Boolean = true,
    ) {
        /** Short human-readable summary for logs. */
        internal fun describe(): String = "rnnoise"
    }

    /**
     * Optional [AGCFilter] (Automatic Gain Control) stage applied to every
     * emitted chunk **after** the [NotchConfig] and [RnNoiseConfig] stages
     * and **before** the [LowPassConfig] / AI encoder. Continuously adjusts
     * gain so the output stays near [targetLevel] (RMS, normalised to
     * full-scale).
     *
     *  - [targetLevel]    – desired output RMS, 0..1 full-scale (e.g. 0.2 ≈ -14 dBFS).
     *  - [attackMs]       – time constant when reducing gain (signal got louder).
     *  - [releaseMs]      – time constant when raising gain (signal got quieter).
     *  - [maxGainDb]      – maximum boost the AGC may apply.
     *  - [minGainDb]      – maximum cut the AGC may apply (negative dB).
     *  - [noiseGateLevel] – RMS below which gain is frozen (0 = disabled).
     *
     * The feeder will, per source:
     *  - create one [AGCFilter] instance (state carried across chunks),
     *  - process each chunk in place,
     *  - accumulate the processed samples and write a `<label>-agc-*.wav`
     *    file next to the other artifacts so the post-AGC signal (exactly
     *    what the AI encoder receives) can be inspected offline.
     */
    data class AGCConfig(
        val enabled: Boolean = true,
        val targetLevel: Float = 0.2f,
        val attackMs: Float = 5f,
        val releaseMs: Float = 250f,
        val maxGainDb: Float = 24f,
        val minGainDb: Float = -12f,
        val noiseGateLevel: Float = 0f,
    ) {
        /** Short human-readable summary for logs. */
        internal fun describe(): String =
            "tgt=%.2f/atk=%.0fms/rel=%.0fms/+%.0fdB/%.0fdB/gate=%.3f".format(
                targetLevel, attackMs, releaseMs, maxGainDb, minGainDb, noiseGateLevel
            )
    }

    /** Detailed metadata extracted from an input file. */
    data class AudioInfo(
        val source: Source,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val audioFormat: Int,      // WAVE format code (1 = PCM)
        val totalSamples: Int,     // per-channel sample count (after stripping header)
        val durationMs: Long,
        val fileSizeBytes: Long,
        val byteRate: Int,
        /** Container label for logs: "WAV/RIFF", "RAW PCM", or e.g. "Compressed (audio/mp4a-latm)". */
        val containerLabel: String = "WAV/RIFF",
    )

    /** Per-file PCM statistics computed after normalization to the target format. */
    data class AudioStats(
        val sampleCount: Int,
        val peak: Int,
        val peakDbFs: Double,
        val rms: Double,
        val rmsDbFs: Double,
        val dcOffset: Double,
        val zeroCrossingRate: Double,
        val silenceRatio: Double,             // share of samples whose |x| < silence threshold
        val clippedSampleRatio: Double,       // share of samples at +/-32767 (or very close)
        val effectiveBitsUsed: Int,           // 1..16 — how many LSBs ever change (detects truncated/padded depth)
        val longestZeroRunMs: Int,            // long zero-runs → dropouts / transport stalls
        val longestRepeatRunMs: Int,          // long identical-sample runs → BLE PLC / USB stall / muted ADC
        val highBandEnergyRatio: Double,      // proxy for bandwidth: energy of 1st-order diff vs total (low = narrowband / SCO / mSBC)
        val bandwidthHint: String,            // "narrowband (≤~4 kHz)", "wideband", etc.
        val possibleRawByteIssue: String?,    // non-null if raw-PCM file looks like wrong endian / unsigned
        /**
         * Energy distribution across [BAND_LABELS] as percentages summing to 100.
         * Computed via FFT on Hann-windowed 2048-sample frames (50% overlap)
         * on up to [FFT_MAX_SAMPLES] of audio.
         */
        val subBandEnergyPct: DoubleArray,
        /**
         * Spectral flatness (Wiener entropy): geometric mean / arithmetic mean of FFT magnitudes.
         * 0 ≈ pure tone, 1 ≈ white noise. Speech is typically 0.05–0.30.
         * Values below ~0.02 indicate strong tonal content (whine, buzz, "piiii").
         */
        val spectralFlatness: Double,
        /** Frequency in Hz of the dominant FFT bin (peak). */
        val dominantFreqHz: Double,
        /** Energy of the dominant bin as a percentage of total spectral energy. */
        val dominantBinEnergyPct: Double,
        /** Peak-to-median magnitude ratio in dB. >25 dB ⇒ very narrow / tonal. */
        val peakToMedianDb: Double,
        /** Non-null when a sustained narrow-band tone ("piiii") is detected; describes it. */
        val toneAlert: String?,
    )

    /** Frequency bands used for [AudioStats.subBandEnergyPct]. Edges in Hz at 24 kHz Fs. */
    val BAND_EDGES_HZ = doubleArrayOf(0.0, 300.0, 1_000.0, 3_400.0, 8_000.0, 12_000.0)
    val BAND_LABELS = arrayOf(
        "0–300 Hz",     // DC / rumble
        "300–1k",       // voice fundamental
        "1k–3.4k",      // telephony band (≤ this = BLE SCO / CVSD)
        "3.4k–8k",      // adds intelligibility ("wideband")
        "8k–12k",       // air / sibilance (only true fullband mics)
    )

    /**
     * Feed the given audio [sources] into the AI pipeline as if they were just recorded.
     *
     * The previous job (if any) is cancelled first.
     *
     * @param realtimePacing if true, waits 500 ms between chunks so the AI sees the
     *                       same cadence it would see live. If false, chunks are
     *                       pushed back-to-back (useful for fast offline batch tests).
     * @param roundTrip      if true, also runs each source through the WavTokenizer
     *                       encoder + decoder and logs PSNR / SI-SDR / token diversity /
     *                       per-band spectral distortion. Requires
     *                       [com.commcrete.stardust.ai.codec.AIModuleInitializer]
     *                       to have completed PyTorch model loading. Costs real ML
     *                       compute time on the IO thread.
     * @param outputDir      directory where round-trip artifacts (encoded tokens +
     *                       decoded WAV per source) are written. Defaults to
     *                       `<DataManager.fileLocation>/test_feeder/<destination>/`.
     *                       Ignored when [roundTrip] = false.
     * @param onDone         optional completion callback (called on IO dispatcher).
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
            val effectiveOutputDir = outputDir ?: defaultArtifactDir(context, destination)
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
            try {
                sources.forEachIndexed { idx, src ->
                    if (!isActive) return@forEachIndexed
                    Timber.tag(TAG).i("── [%d/%d] Source: %s (%s)", idx + 1, sources.size, src.label, src.file.absolutePath)
                    feedSingle(context, destination, carrier, src, realtimePacing, roundTrip, effectiveOutputDir, lowPass, notch, rnNoise, agc)
                }
                logCrossSourceSummary()
                if (roundTrip) logCrossSourceRoundTrip()
                // mimic AudioRecorderAI tail handling
                Timber.tag(TAG).i("✔ All sources fed. Calling PttSendManager.finish() in 3s")
                delay(3_000)
                PttSendManager.finish(context)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Feeder failed")
            } finally {
                onDone?.invoke()
            }
        }
        currentJob = job
        return job
    }

    /** Stop the currently running feeder, if any. */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private suspend fun feedSingle(
        context: Context,
        destination: String,
        carrier: Carrier?,
        source: Source,
        realtimePacing: Boolean,
        roundTrip: Boolean,
        artifactDir: File,
        lowPass: LowPassConfig?,
        notch: NotchConfig?,
        rnNoise: RnNoiseConfig?,
        agc: AGCConfig?,
    ) = withContext(Dispatchers.IO) {
        if (!source.file.exists() || !source.file.canRead()) {
            Timber.tag(TAG).w("Skipping unreadable file: %s", source.file.absolutePath)
            return@withContext
        }

        val (info, pcm) = loadAndNormalize(source)
        logAudioInfo(info)
        val stats = computeStats(pcm, source)
        logAudioStats(source.label, stats)
        lastRunStats[source.label] = stats

        if (roundTrip) {
            val rt = runRoundTrip(source, pcm, artifactDir)
            if (rt != null) {
                lastRunRoundTrips[source.label] = rt
                logRoundTrip(rt)
            }
        }

        val fullChunks = pcm.size / SAMPLES_PER_CHUNK
        val tailSamples = pcm.size % SAMPLES_PER_CHUNK
        val tailMs = (tailSamples * 1000L) / TARGET_SAMPLE_RATE
        Timber.tag(TAG).i(
            "  → Emitting %d full chunk(s) of %d samples (%d ms each)%s — chunked exactly like AudioRecorderAI (10 ms reads → %d-sample accumulator)",
            fullChunks, SAMPLES_PER_CHUNK, CHUNK_DURATION_MS,
            if (tailSamples > 0) " + 1 partial/final chunk of $tailSamples samples (${tailMs} ms)" else "",
            SAMPLES_PER_CHUNK
        )

        // Optional notch stage: runs FIRST in the chain (before AGC / LPF /
        // AI encoder). State (biquad memories) is preserved across chunks.
        val notchFilter: NotchFilter? = notch?.takeIf { it.enabled }?.let {
            val resolvedBands = it.resolveBands()
            Timber.tag(TAG).i(
                "  ⌁ Notch ENABLED: %s → %d band(s): %s (applied per chunk before AGC/LPF/AI)",
                it.describe(),
                resolvedBands.size,
                resolvedBands.joinToString(",") { b -> "${b.frequencyHz.toInt()}Hz@Q${b.q.toInt()}" }
            )
            NotchFilter(sampleRateHz = TARGET_SAMPLE_RATE, bands = resolvedBands)
        }
        val notchAccumulator: ArrayList<Short>? =
            if (notchFilter != null) ArrayList(pcm.size) else null

        // Optional RNNoise stage: runs AFTER the notch and BEFORE the AGC.
        // The processor maintains internal state (residual 48 kHz tail) across
        // chunks, so a single instance is used per source and released at end.
        val rnNoiseProcessor: com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor? =
            rnNoise?.takeIf { it.enabled }?.let {
                Timber.tag(TAG).i(
                    "  ⌁ RnNoise ENABLED: %s (applied per chunk after notch, before AGC/LPF/AI)",
                    it.describe()
                )
                val proc = com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor().apply {
                    init(TARGET_SAMPLE_RATE)
                }
                if (proc.isActive()) {
                    Timber.tag(TAG).i(
                        "  ✓ RnNoise native backend active: %s",
                        proc.activeClassName()
                    )
                } else {
                    Timber.tag(TAG).w(
                        "  ⚠ RnNoise is enabled but running in PASS-THROUGH mode " +
                            "(native librnnoise_jni.so not loaded / wrapper class not on classpath). " +
                            "The `*-rnnoise.wav` artifact will equal the post-notch audio. " +
                            "Fix: run ./stardust/scripts/setup_rnnoise.sh then rebuild :stardust."
                    )
                }
                proc
            }
        val rnNoiseAccumulator: ArrayList<Short>? =
            if (rnNoiseProcessor != null) ArrayList(pcm.size) else null

        // Optional AGC stage: runs AFTER the notch and BEFORE the low-pass /
        // AI encoder. State (envelope, smoothed gain) is preserved across chunks.
        val agcFilter: AGCFilter? = agc?.takeIf { it.enabled }?.let {
            Timber.tag(TAG).i(
                "  ⌁ AGC ENABLED: %s (applied per chunk after notch, before LPF/AI)",
                it.describe()
            )
            AGCFilter(
                sampleRateHz = TARGET_SAMPLE_RATE,
                targetLevel = it.targetLevel,
                attackMs = it.attackMs,
                releaseMs = it.releaseMs,
                maxGainDb = it.maxGainDb,
                minGainDb = it.minGainDb,
                noiseGateLevel = it.noiseGateLevel,
            )
        }
        val agcAccumulator: ArrayList<Short>? =
            if (agcFilter != null) ArrayList(pcm.size) else null

        // Optional low-pass stage: runs LAST in the chain, after AGC, right
        // before the AI encoder. Created once per source so the cascaded
        // one-pole memories are preserved across consecutive chunks.
        val lpf: LowPassFilter? = lowPass?.takeIf { it.enabled }?.let {
            Timber.tag(TAG).i(
                "  ⌁ Low-pass ENABLED: cutoff=%.1f Hz, roll-off=%.1f dB/oct (applied per chunk after AGC, before AI encode)",
                it.cutoffHz, it.rollOffDbPerOctave
            )
            LowPassFilter(
                sampleRateHz = TARGET_SAMPLE_RATE,
                cutoffHz = it.cutoffHz,
                rollOffDbPerOctave = it.rollOffDbPerOctave,
            )
        }
        val lpfAccumulator: ArrayList<Short>? =
            if (lpf != null) ArrayList(pcm.size) else null

        var chunkIndex = 0
        val startTs = System.currentTimeMillis()
        // Use the same persistent file convention as live recording; PttSendManager only
        // uses it as a sink for debug/persistent storage of the encoded result.
        val sinkFile = createSinkFile(context, destination, source.label)

        // ── Mirror AudioRecorderAI.recordLoop chunking exactly ────────────
        // Live PTT recording does NOT slice the stream every CHUNK_DURATION_MS;
        // it pulls 10 ms read-buffers from AudioRecord, copies them into a
        // fixed-size `chunkSamples` accumulator, emits the chunk only when the
        // accumulator is full (via System.arraycopy), and ships any leftover
        // bytes as a single PARTIAL/FINAL chunk on stop. We replicate that
        // here so file-fed runs produce the exact same chunking behaviour as
        // a real microphone session.
        val gain = SharedPreferencesUtil.getAIGain(context) / 100f
        val readBufferSize = TARGET_SAMPLE_RATE / 100 // 10 ms = 240 samples @ 24 kHz
        val shortBuffer = ShortArray(readBufferSize)
        val chunkSamples = ShortArray(SAMPLES_PER_CHUNK)
        var chunkSampleIndex = 0
        var totalRead = 0
        // Approximate AudioRecord's blocking pacing — it returns ~every 10 ms.
        val msPerReadBuffer = (readBufferSize * 1000L) / TARGET_SAMPLE_RATE

        // Process / log / filter / emit one assembled chunk. Used for both
        // full chunks (length = SAMPLES_PER_CHUNK) and the final partial one.
        fun emitChunk(buffer: ShortArray, length: Int, isPartial: Boolean) {
            // Fresh allocation = same shape as AudioRecorderAI.processSamples().
            val chunk = applyAiGain(buffer, length, gain)

            val cStats = quickChunkStats(chunk)
            Timber.tag(TAG).d(
                "    chunk #%03d len=%d peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f%s",
                chunkIndex, chunk.size, cStats.peak, cStats.peakDbFs,
                cStats.rms, cStats.rmsDbFs, cStats.zeroCrossingRate,
                if (isPartial) "  [PARTIAL/FINAL]" else ""
            )

            // ── Filter chain: runs AFTER chunking, BEFORE AI encoding ──
            // Order: raw → Notch → RnNoise → AGC → LPF → AI
            if (notchFilter != null) {
                notchFilter.processInPlace(chunk)
                val nStats = quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-notch     peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f",
                    nStats.peak, nStats.peakDbFs, nStats.rms, nStats.rmsDbFs,
                    nStats.zeroCrossingRate
                )
                notchAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            if (rnNoiseProcessor != null) {
                rnNoiseProcessor.process(chunk, chunk.size)
                val rStats = quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-rnnoise   peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f",
                    rStats.peak, rStats.peakDbFs, rStats.rms, rStats.rmsDbFs,
                    rStats.zeroCrossingRate
                )
                rnNoiseAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            if (agcFilter != null) {
                agcFilter.processInPlace(chunk)
                val aStats = quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-AGC       peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f gain=%.2fx env=%.4f",
                    aStats.peak, aStats.peakDbFs, aStats.rms, aStats.rmsDbFs,
                    aStats.zeroCrossingRate,
                    agcFilter.currentGainLinear(), agcFilter.currentEnvelope()
                )
                agcAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            if (lpf != null) {
                lpf.processInPlace(chunk)
                val fStats = quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-LPF       peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f",
                    fStats.peak, fStats.peakDbFs, fStats.rms, fStats.rmsDbFs,
                    fStats.zeroCrossingRate
                )
                lpfAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            PttSendManager.addNewFrame(chunk, sinkFile, carrier, destination)
        }

        while (totalRead < pcm.size && isActive) {
            // Mimic `AudioRecord.read(shortBuffer, 0, shortBuffer.size)`.
            val read = minOf(readBufferSize, pcm.size - totalRead)
            System.arraycopy(pcm, totalRead, shortBuffer, 0, read)
            totalRead += read

            // Drain the read buffer into the chunk accumulator, emitting whenever it fills.
            var consumed = 0
            while (consumed < read && isActive) {
                val remaining = SAMPLES_PER_CHUNK - chunkSampleIndex
                val toCopy = minOf(remaining, read - consumed)
                System.arraycopy(shortBuffer, consumed, chunkSamples, chunkSampleIndex, toCopy)
                chunkSampleIndex += toCopy
                consumed += toCopy

                if (chunkSampleIndex == SAMPLES_PER_CHUNK) {
                    emitChunk(chunkSamples, SAMPLES_PER_CHUNK, isPartial = false)
                    chunkIndex++
                    chunkSampleIndex = 0
                }
            }

            // Approximate AudioRecord's blocking pacing (~10 ms per read).
            if (realtimePacing && totalRead < pcm.size) {
                delay(msPerReadBuffer)
            }
        }

        // Flush remainder as a single PARTIAL/FINAL chunk, just like
        // AudioRecorderAI's onPartialFinalChunk path.
        if (chunkSampleIndex > 0 && isActive) {
            emitChunk(chunkSamples, chunkSampleIndex, isPartial = true)
            chunkIndex++
        }

        val elapsed = System.currentTimeMillis() - startTs
        Timber.tag(TAG).i(
            "  ✓ Finished %s: %d chunks, %d samples (%d ms audio) in %d ms wall-clock",
            source.label, chunkIndex, totalRead,
            (totalRead * 1000L) / TARGET_SAMPLE_RATE, elapsed
        )

        // Persist the post-LPF audio so the filtered signal can be inspected
        // exactly as it was handed to the next filter stage.
        if (lpf != null && lpfAccumulator != null && lowPass != null && lpfAccumulator.isNotEmpty()) {
            try {
                artifactDir.mkdirs()
                val out = File(
                    artifactDir,
                    "${System.currentTimeMillis()}-${source.label}-lowpass-" +
                        "${lowPass.cutoffHz.toInt()}Hz-" +
                        "${lowPass.rollOffDbPerOctave.toInt()}dboct.wav"
                )
                val arr = ShortArray(lpfAccumulator.size) { lpfAccumulator[it] }
                writePcm16Wav(out, arr, TARGET_SAMPLE_RATE)
                Timber.tag(TAG).i(
                    "  💾 Wrote low-pass-filtered WAV: %s (%d samples, %d ms)",
                    out.absolutePath, arr.size, (arr.size * 1000L) / TARGET_SAMPLE_RATE
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to write low-pass artifact WAV for %s", source.label)
            }
        }

        // Persist the post-notch audio (this is what the AI encoder actually sees
        // when the notch stage is enabled).
        if (notchFilter != null && notchAccumulator != null && notch != null && notchAccumulator.isNotEmpty()) {
            try {
                artifactDir.mkdirs()
                val tag = if (notch.harmonics != null) {
                    notch.harmonics.joinToString(separator = "_") {
                        "${it.frequencyHz.toInt()}HzQ${it.q.toInt()}"
                    }
                } else {
                    "${notch.fundamentalHz.toInt()}Hz-Q${notch.q.toInt()}-x${notch.numHarmonics}"
                }
                val out = File(
                    artifactDir,
                    "${System.currentTimeMillis()}-${source.label}-notch-$tag.wav"
                )
                val arr = ShortArray(notchAccumulator.size) { notchAccumulator[it] }
                writePcm16Wav(out, arr, TARGET_SAMPLE_RATE)
                Timber.tag(TAG).i(
                    "  💾 Wrote notch-filtered WAV: %s (%d samples, %d ms)",
                    out.absolutePath, arr.size, (arr.size * 1000L) / TARGET_SAMPLE_RATE
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to write notch artifact WAV for %s", source.label)
            }
        }

        // Persist the post-RNNoise audio (what AGC actually saw when the
        // stage is enabled), then release the native denoiser.
        if (rnNoiseProcessor != null && rnNoiseAccumulator != null && rnNoiseAccumulator.isNotEmpty()) {
            try {
                artifactDir.mkdirs()
                val out = File(
                    artifactDir,
                    "${System.currentTimeMillis()}-${source.label}-rnnoise.wav"
                )
                val arr = ShortArray(rnNoiseAccumulator.size) { rnNoiseAccumulator[it] }
                writePcm16Wav(out, arr, TARGET_SAMPLE_RATE)
                Timber.tag(TAG).i(
                    "  💾 Wrote RNNoise-cleaned WAV: %s (%d samples, %d ms)",
                    out.absolutePath, arr.size, (arr.size * 1000L) / TARGET_SAMPLE_RATE
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to write RNNoise artifact WAV for %s", source.label)
            }
        }
        rnNoiseProcessor?.let {
            try { it.release() } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "RnNoise release failed for %s", source.label)
            }
        }

        // Persist the post-AGC audio (this is what the AI encoder actually sees
        // when the AGC stage is enabled).
        if (agcFilter != null && agcAccumulator != null && agc != null && agcAccumulator.isNotEmpty()) {
            try {
                artifactDir.mkdirs()
                val tag = "tgt%.2f-atk%.0fms-rel%.0fms-max%.0fdB"
                    .format(agc.targetLevel, agc.attackMs, agc.releaseMs, agc.maxGainDb)
                    .replace(',', '.')
                val out = File(
                    artifactDir,
                    "${System.currentTimeMillis()}-${source.label}-agc-$tag.wav"
                )
                val arr = ShortArray(agcAccumulator.size) { agcAccumulator[it] }
                writePcm16Wav(out, arr, TARGET_SAMPLE_RATE)
                Timber.tag(TAG).i(
                    "  💾 Wrote AGC-processed WAV: %s (%d samples, %d ms)",
                    out.absolutePath, arr.size, (arr.size * 1000L) / TARGET_SAMPLE_RATE
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to write AGC artifact WAV for %s", source.label)
            }
        }
    }

    private fun createSinkFile(context: Context, destination: String, label: String): File {
        val baseDir = try {
            File(DataManager.fileLocation)
        } catch (t: Throwable) {
            context.cacheDir
        }
        val dir = File(baseDir, "test_feeder/$destination").apply { if (!exists()) mkdirs() }
        return File(dir, "${System.currentTimeMillis()}-$label.pcm").apply {
            if (!exists()) createNewFile()
        }
    }

    /**
     * Apply the same AI gain that [com.commcrete.stardust.ai.codec.AudioRecorderAI]
     * applies in `processSamples()` — i.e. multiply each sample by the
     * user-configured gain (`SharedPreferencesUtil.getAIGain` / 100) and
     * clamp to int16 range. Returns a fresh ShortArray of [length] samples
     * so callers can safely reuse the source buffer.
     */
    private fun applyAiGain(source: ShortArray, length: Int, gain: Float): ShortArray {
        val out = ShortArray(length)
        if (gain == 1f) {
            System.arraycopy(source, 0, out, 0, length)
            return out
        }
        for (i in 0 until length) {
            val v = (source[i] * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = v.toShort()
        }
        return out
    }

    /**
     * Loads the file, parses header (or treats as raw PCM), down-mixes to mono,
     * resamples (linear) to [TARGET_SAMPLE_RATE] and returns 16-bit samples.
     */
    private fun loadAndNormalize(source: Source): Pair<AudioInfo, ShortArray> {
        val fileSize = source.file.length()
        // Three paths:
        //   1. rawPcm flag → bytes are raw signed 16-bit LE.
        //   2. WAV (RIFF/WAVE) → parsed by our own header reader.
        //   3. Anything else → decoded via MediaExtractor + MediaCodec
        //      (m4a / mp4 / aac / mp3 / 3gp / ogg / flac …).
        return when {
            source.rawPcm -> {
                val raw = source.file.readBytes()
                val pcm = bytesToShortsLe(raw)
                val mono = downmix(pcm, source.rawChannels)
                val resampled = resampleLinear(mono, source.rawSampleRate, TARGET_SAMPLE_RATE)
                val info = AudioInfo(
                    source = source,
                    sampleRate = source.rawSampleRate,
                    channels = source.rawChannels,
                    bitsPerSample = 16,
                    audioFormat = 1,
                    totalSamples = mono.size,
                    durationMs = mono.size * 1000L / source.rawSampleRate,
                    fileSizeBytes = fileSize,
                    byteRate = source.rawSampleRate * source.rawChannels * 2,
                    containerLabel = "RAW PCM",
                )
                info to resampled
            }
            isWavFile(source.file) -> {
                val parsed = parseWav(source.file)
                val mono = downmix(parsed.samples, parsed.channels)
                val resampled = resampleLinear(mono, parsed.sampleRate, TARGET_SAMPLE_RATE)
                val info = AudioInfo(
                    source = source,
                    sampleRate = parsed.sampleRate,
                    channels = parsed.channels,
                    bitsPerSample = parsed.bitsPerSample,
                    audioFormat = parsed.audioFormat,
                    totalSamples = parsed.samples.size / parsed.channels,
                    durationMs = (parsed.samples.size / parsed.channels) * 1000L / parsed.sampleRate,
                    fileSizeBytes = fileSize,
                    byteRate = parsed.byteRate,
                    containerLabel = "WAV/RIFF",
                )
                info to resampled
            }
            else -> {
                val parsed = decodeCompressedAudio(source.file)
                val mono = downmix(parsed.samples, parsed.channels)
                val resampled = resampleLinear(mono, parsed.sampleRate, TARGET_SAMPLE_RATE)
                val info = AudioInfo(
                    source = source,
                    sampleRate = parsed.sampleRate,
                    channels = parsed.channels,
                    bitsPerSample = parsed.bitsPerSample,
                    audioFormat = parsed.audioFormat,
                    totalSamples = parsed.samples.size / parsed.channels,
                    durationMs = if (parsed.sampleRate > 0)
                        (parsed.samples.size / parsed.channels) * 1000L / parsed.sampleRate else 0L,
                    fileSizeBytes = fileSize,
                    byteRate = parsed.byteRate,
                    containerLabel = "Compressed (${parsed.mimeLabel ?: "MediaCodec"})",
                )
                info to resampled
            }
        }
    }

    /**
     * Quick check whether [file] is a RIFF/WAVE container. Done by reading the
     * first 12 bytes so we don't depend on the extension (`.wav` is sometimes
     * a misnamed compressed file).
     */
    private fun isWavFile(file: File): Boolean {
        return try {
            val head = ByteArray(12)
            java.io.FileInputStream(file).use { it.read(head) }
            String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE"
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------- WAV parsing ----------------

    private data class ParsedWav(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val audioFormat: Int,
        val byteRate: Int,
        val samples: ShortArray, // interleaved
        /** Non-null for files decoded through MediaCodec; identifies the actual container. */
        val mimeLabel: String? = null,
    )

    private fun parseWav(file: File): ParsedWav {
        DataInputStream(FileInputStream(file)).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            require(String(header, 0, 4) == "RIFF") { "Not a RIFF file: ${file.name}" }
            require(String(header, 8, 4) == "WAVE") { "Not a WAVE file: ${file.name}" }

            var fmtFound = false
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var audioFormat = 0       // 1=PCM, 3=IEEE float, 0xFFFE=EXTENSIBLE
            var subFormatCode = -1    // resolved sub-format for EXTENSIBLE
            var byteRate = 0
            var samples: ShortArray = ShortArray(0)

            while (true) {
                val chunkHeader = ByteArray(8)
                val read = input.read(chunkHeader)
                if (read < 8) break
                val id = String(chunkHeader, 0, 4)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size)
                        input.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        byteRate = bb.int
                        /* blockAlign */ bb.short
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        if (audioFormat == 0xFFFE && size >= 40) {
                            // skip cbSize(2) + validBitsPerSample(2) + channelMask(4) = 8 bytes, then SubFormat GUID
                            bb.position(bb.position() + 8)
                            subFormatCode = bb.short.toInt() and 0xFFFF
                        }
                        fmtFound = true
                    }
                    "data" -> {
                        require(fmtFound) { "WAV 'data' chunk before 'fmt ' in ${file.name}" }
                        val effectiveFmt = if (audioFormat == 0xFFFE) subFormatCode else audioFormat
                        val data = ByteArray(size)
                        input.readFully(data)
                        samples = when {
                            effectiveFmt == 1 && bitsPerSample == 8 -> bytes8uToShorts(data)
                            effectiveFmt == 1 && bitsPerSample == 16 -> bytesToShortsLe(data)
                            effectiveFmt == 1 && bitsPerSample == 24 -> bytes24LeToShorts(data)
                            effectiveFmt == 1 && bitsPerSample == 32 -> bytes32IntLeToShorts(data)
                            effectiveFmt == 3 && bitsPerSample == 32 -> bytes32FloatLeToShorts(data)
                            else -> error("Unsupported WAV format=$effectiveFmt bits=$bitsPerSample in ${file.name}")
                        }
                        return ParsedWav(sampleRate, channels, bitsPerSample, effectiveFmt, byteRate, samples)
                    }
                    else -> {
                        var toSkip = size.toLong()
                        while (toSkip > 0) {
                            val s = input.skip(toSkip)
                            if (s <= 0) break
                            toSkip -= s
                        }
                    }
                }
            }
            error("No 'data' chunk found in ${file.name}")
        }
    }

    // ---------------- Compressed-audio decoding via MediaExtractor + MediaCodec ----------------

    /**
     * Decodes any container Android's `MediaExtractor` understands (`.m4a`,
     * `.mp4`, `.aac`, `.mp3`, `.3gp`, `.ogg`, `.flac`, etc.) into a
     * [ParsedWav]-shaped result so downstream code is identical to the WAV
     * path.
     *
     * Behavior:
     *  - Selects the first audio track.
     *  - Requests 16-bit PCM output via `KEY_PCM_ENCODING`. If the codec
     *    actually delivers float / 8-bit PCM, we convert.
     *  - Concatenates all decoded PCM frames in order, blocking the calling
     *    thread (expected to be `Dispatchers.IO`).
     *
     * Throws `IllegalStateException` / `IllegalArgumentException` on
     * unsupported / unreadable files; callers in [loadAndNormalize] are
     * already on a coroutine that surfaces the exception via Timber.
     */
    private fun decodeCompressedAudio(file: File): ParsedWav {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (t: Throwable) {
            extractor.release()
            throw IllegalArgumentException(
                "Cannot open '${file.name}' as a media container: ${t.message}", t
            )
        }

        var audioTrack = -1
        var inputFormat: android.media.MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrack = i
                inputFormat = f
                break
            }
        }
        if (audioTrack < 0 || inputFormat == null) {
            extractor.release()
            error("No audio track found in '${file.name}'")
        }
        extractor.selectTrack(audioTrack)

        val mime = inputFormat.getString(android.media.MediaFormat.KEY_MIME)!!
        val sampleRate = inputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        val channels = inputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)

        // Ask the decoder for 16-bit PCM. Most decoders honor this; we still
        // handle the format-changed callback below in case a device defaults
        // to float on newer Android versions.
        inputFormat.setInteger(
            android.media.MediaFormat.KEY_PCM_ENCODING,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )

        val codec = try {
            android.media.MediaCodec.createDecoderByType(mime)
        } catch (t: Throwable) {
            extractor.release()
            throw IllegalStateException("No decoder for $mime (file ${file.name}): ${t.message}", t)
        }

        val pcmOut = java.io.ByteArrayOutputStream()
        val info = android.media.MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var outputPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        val timeoutUs = 10_000L

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                            ?: error("Null input buffer at index $inIdx")
                        inBuf.clear()
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                                ?: error("Null output buffer at index $outIdx")
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                            outputPcmEncoding = newFormat
                                .getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    // INFO_TRY_AGAIN_LATER and INFO_OUTPUT_BUFFERS_CHANGED: just loop.
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        val pcmBytes = pcmOut.toByteArray()
        val samples: ShortArray = when (outputPcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_16BIT -> bytesToShortsLe(pcmBytes)
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> bytes32FloatLeToShorts(pcmBytes)
            android.media.AudioFormat.ENCODING_PCM_8BIT  -> bytes8uToShorts(pcmBytes)
            else -> bytesToShortsLe(pcmBytes)   // best-effort
        }

        Timber.tag(TAG).d(
            "Decoded '%s' via MediaCodec (%s): %d Hz, %d ch, %d samples (%d ms)",
            file.name, mime, sampleRate, channels, samples.size / max(channels, 1),
            if (sampleRate > 0) (samples.size / max(channels, 1)) * 1000L / sampleRate else 0L
        )

        return ParsedWav(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = 16,
            audioFormat = 1,
            byteRate = sampleRate * channels * 2,
            samples = samples,
            mimeLabel = mime,
        )
    }

    private fun bytesToShortsLe(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in out.indices) out[i] = bb.short
        return out
    }

    /** 8-bit WAV is unsigned (0..255, 128 = silence). Convert to signed 16-bit. */
    private fun bytes8uToShorts(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size)
        for (i in bytes.indices) {
            val u = bytes[i].toInt() and 0xFF
            out[i] = (((u - 128) shl 8)).toShort()
        }
        return out
    }

    /** 24-bit signed little-endian PCM → 16-bit (drop low byte). */
    private fun bytes24LeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 3
        val out = ShortArray(n)
        for (i in 0 until n) {
            val b0 = bytes[i * 3].toInt() and 0xFF
            val b1 = bytes[i * 3 + 1].toInt() and 0xFF
            val b2 = bytes[i * 3 + 2].toInt()
            val s24 = (b2 shl 16) or (b1 shl 8) or b0
            out[i] = (s24 shr 8).toShort()
        }
        return out
    }

    /** 32-bit signed little-endian PCM → 16-bit. */
    private fun bytes32IntLeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 4
        val out = ShortArray(n)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = (bb.int shr 16).toShort()
        return out
    }

    /** 32-bit IEEE float (-1.0..+1.0) → 16-bit. */
    private fun bytes32FloatLeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 4
        val out = ShortArray(n)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val f = bb.float.coerceIn(-1f, 1f)
            out[i] = (f * 32_767f).roundToInt().toShort()
        }
        return out
    }

    // ---------------- DSP helpers ----------------

    private fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c].toInt()
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    // ---------------- Spectral fingerprint (FFT sub-band energy) ----------------

    private const val FFT_SIZE = 2048
    /** Hard cap on samples processed by FFT analysis (~30 s at 24 kHz). Keeps cost bounded. */
    private const val FFT_MAX_SAMPLES = 30 * TARGET_SAMPLE_RATE

    private val hannWindow: DoubleArray by lazy {
        DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)) }
    }

    /** Bundle returned by [computeSpectralAnalysis] — bands + tone diagnostics. */
    private data class SpectralAnalysis(
        val bands: DoubleArray,
        val flatness: Double,
        val dominantFreqHz: Double,
        val dominantBinEnergyPct: Double,
        val peakToMedianDb: Double,
        val toneAlert: String?,
    )

    /**
     * Single FFT pass that computes:
     *  - per-band energy percentages (as before),
     *  - long-term averaged magnitude spectrum,
     *  - spectral flatness (Wiener entropy) — small ⇒ tonal,
     *  - dominant peak frequency / energy share,
     *  - peak-to-median dB ratio (sharpness of the peak),
     *  - a textual tone alert when a sustained narrow-band whine is detected.
     *
     * Tonal detection is keyed at the LONG-TERM averaged spectrum so transient
     * speech harmonics don't trigger it; only sustained tones (jbox "piiii",
     * switching-supply whine, ground-loop hum harmonics) light it up.
     */
    private fun computeSpectralAnalysis(pcm: ShortArray, sampleRate: Int): SpectralAnalysis {
        val emptyBands = DoubleArray(BAND_LABELS.size)
        if (pcm.size < FFT_SIZE) {
            return SpectralAnalysis(emptyBands, 1.0, 0.0, 0.0, 0.0, null)
        }

        val nyquistBin = FFT_SIZE / 2
        val n = minOf(pcm.size, FFT_MAX_SAMPLES)
        val hop = FFT_SIZE / 2
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val binHz = sampleRate.toDouble() / FFT_SIZE

        val binBand = IntArray(nyquistBin + 1) { bin ->
            if (bin == 0) -1 else {
                val hz = bin * binHz
                var b = -1
                for (k in 0 until BAND_LABELS.size) {
                    if (hz >= BAND_EDGES_HZ[k] && hz < BAND_EDGES_HZ[k + 1]) { b = k; break }
                }
                b
            }
        }

        val bandEnergy = DoubleArray(BAND_LABELS.size)
        val avgPower = DoubleArray(nyquistBin + 1)   // long-term per-bin energy
        var offset = 0
        var frames = 0
        while (offset + FFT_SIZE <= n) {
            for (i in 0 until FFT_SIZE) {
                re[i] = pcm[offset + i].toDouble() * hannWindow[i]
                im[i] = 0.0
            }
            fftInPlace(re, im)
            for (bin in 1..nyquistBin) {
                val mag2 = re[bin] * re[bin] + im[bin] * im[bin]
                avgPower[bin] += mag2
                val band = binBand[bin]
                if (band >= 0) bandEnergy[band] += mag2
            }
            offset += hop
            frames++
        }

        if (frames == 0) return SpectralAnalysis(emptyBands, 1.0, 0.0, 0.0, 0.0, null)

        val totalBandEnergy = bandEnergy.sum()
        val bandsPct = if (totalBandEnergy > 0.0)
            DoubleArray(bandEnergy.size) { bandEnergy[it] * 100.0 / totalBandEnergy }
        else emptyBands

        // --- Tonal / peakiness analysis on long-term averaged spectrum ---

        // Use bins from ~50 Hz upwards; below that is DC / hum that distorts flatness.
        val lowBin = max(2, (50.0 / binHz).toInt())
        val highBin = nyquistBin
        if (highBin <= lowBin) {
            return SpectralAnalysis(bandsPct, 1.0, 0.0, 0.0, 0.0, null)
        }

        var sumMag = 0.0
        var sumLogMag = 0.0
        var totalEnergy = 0.0
        var peakBin = lowBin
        var peakVal = 0.0
        val mags = DoubleArray(highBin - lowBin + 1)
        for ((idx, bin) in (lowBin..highBin).withIndex()) {
            val mag = sqrt(avgPower[bin])
            mags[idx] = mag
            sumMag += mag
            sumLogMag += if (mag > 0) ln(mag) else -50.0   // floor for log
            totalEnergy += avgPower[bin]
            if (avgPower[bin] > peakVal) { peakVal = avgPower[bin]; peakBin = bin }
        }
        val nMags = mags.size.toDouble()
        val arithMean = sumMag / nMags
        val geoMean = kotlin.math.exp(sumLogMag / nMags)
        val flatness = if (arithMean > 0.0) (geoMean / arithMean).coerceIn(0.0, 1.0) else 1.0
        val dominantFreq = peakBin * binHz
        val dominantPct = if (totalEnergy > 0.0) peakVal * 100.0 / totalEnergy else 0.0

        // Median magnitude for peak-to-median dB
        val sorted = mags.copyOf().also { it.sort() }
        val median = sorted[sorted.size / 2].coerceAtLeast(1e-9)
        val peakMag = sqrt(peakVal)
        val peakToMedianDb = 20.0 * log10(peakMag / median)

        // --- Tone alert heuristic ---
        // We treat the source as tone-contaminated when ALL of:
        //   • spectral flatness is very low (tonal energy distribution),
        //   • the single dominant bin holds an unusually large share of energy,
        //   • peak is clearly above the surrounding noise floor.
        // Thresholds chosen from typical speech vs whine/buzzer measurements.
        val tonal = flatness < 0.05 && dominantPct > 8.0 && peakToMedianDb > 25.0

        val alert: String? = if (tonal) {
            val freqLabel = when {
                dominantFreq < 120.0 -> "${dominantFreq.toInt()} Hz (mains hum range)"
                dominantFreq < 500.0 -> "${dominantFreq.toInt()} Hz (low buzz)"
                dominantFreq < 4_000.0 -> "${dominantFreq.toInt()} Hz (whine — masks speech intelligibility)"
                dominantFreq < 8_000.0 -> "${dominantFreq.toInt()} Hz (high whine — switching-supply territory)"
                else                   -> "${dominantFreq.toInt()} Hz (very high tone)"
            }
            "sustained tone at $freqLabel — flatness=%.3f, dominant bin=%.1f%%, peak/median=+%.1f dB".format(
                java.util.Locale.US, flatness, dominantPct, peakToMedianDb
            )
        } else null

        return SpectralAnalysis(bandsPct, flatness, dominantFreq, dominantPct, peakToMedianDb, alert)
    }

    /**
     * Returns per-band energy as percentages of total spectral energy.
     * Bands defined by [BAND_EDGES_HZ] / [BAND_LABELS]. Sum ≈ 100 (≤100 if
     * there is content above the last edge, which is then discarded).
     *
     * Thin wrapper kept for round-trip code paths that only need band energy.
     */
    private fun computeSubBandEnergyPct(pcm: ShortArray, sampleRate: Int): DoubleArray {
        return computeSpectralAnalysis(pcm, sampleRate).bands
    }

    /** Iterative radix-2 Cooley-Tukey FFT, in-place. [re].size must be a power of 2. */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = curRe * re[i + k + half] - curIm * im[i + k + half]
                    val tIm = curRe * im[i + k + half] + curIm * re[i + k + half]
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + half] = uRe - tRe
                    im[i + k + half] = uIm - tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun formatBandBar(pct: Double, width: Int = 20): String {
        val filled = ((pct / 100.0) * width).roundToInt().coerceIn(0, width)
        return "█".repeat(filled) + "·".repeat(width - filled)
    }

    /**
     * Resampler used to normalize any input rate to [TARGET_SAMPLE_RATE] before
     * tokenization.
     *
     * Two regimes:
     *  - **Upsampling** (`srcRate < dstRate`, e.g. 16 → 24 kHz native phone mic):
     *    plain linear interpolation. No aliasing risk going up, and the linear
     *    interpolator's roll-off near Nyquist is well below the codec's input
     *    band of interest.
     *  - **Downsampling** (`srcRate > dstRate`, e.g. 48 → 24 kHz file):
     *    a Hann-windowed-sinc FIR low-pass at ~95 % of the new Nyquist is run
     *    BEFORE linear interpolation. Without this pre-filter, any energy
     *    between the new and old Nyquist (12–24 kHz when 48 → 24) folds back
     *    into the 0–12 kHz range as phantom frequencies — exactly the band
     *    WavTokenizer cares about. The filter has 63 taps which gives ≈ −60 dB
     *    rejection at the new Nyquist while staying cheap (<1 ms on Pixel-class
     *    devices for typical test-file lengths).
     */
    private fun resampleLinear(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return input
        if (input.isEmpty()) return input
        // Pre-filter only when going down. Linear interp is fine going up.
        val filtered = if (srcRate > dstRate) antiAliasLowPass(input, srcRate, dstRate) else input
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLen = (filtered.size * ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt()
            val i1 = (i0 + 1).coerceAtMost(filtered.size - 1)
            val frac = srcPos - i0
            val s = filtered[i0] * (1 - frac) + filtered[i1] * frac
            out[i] = s.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * Anti-aliasing low-pass filter applied prior to downsampling. Hann-windowed
     * sinc, fixed 63 taps, cutoff at 95 % of `dstRate / 2`. Centered (zero-phase
     * equivalent: the same delay is applied across the whole signal so it does
     * not affect spectral / token analysis — group delay is constant).
     *
     * Tap count and cutoff chosen as a pragmatic balance: tight enough to
     * suppress the fold-back band by ≈ 60 dB, loose enough that the transition
     * doesn't bite into speech intelligibility above ~8 kHz.
     */
    private fun antiAliasLowPass(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (input.size < 64) return input
        val taps = 63
        val half = taps / 2
        val cutoffHz = (dstRate.toDouble() / 2.0) * 0.95
        val normCutoff = cutoffHz / srcRate.toDouble()  // 0..0.5
        // Build the windowed-sinc kernel (Hann window).
        val kernel = DoubleArray(taps)
        var ksum = 0.0
        for (n in 0 until taps) {
            val k = n - half
            val sinc = if (k == 0) {
                2.0 * normCutoff
            } else {
                val x = 2.0 * Math.PI * normCutoff * k
                Math.sin(x) / (Math.PI * k.toDouble())
            }
            val hann = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / (taps - 1))
            val v = sinc * hann
            kernel[n] = v
            ksum += v
        }
        // Normalize to unity DC gain.
        if (ksum > 0.0) for (n in 0 until taps) kernel[n] = kernel[n] / ksum

        // Convolve. Edges are handled by clamping the input index — fine for the
        // small transient at the very start/end of a multi-second test file.
        val out = ShortArray(input.size)
        val n = input.size
        for (i in 0 until n) {
            var acc = 0.0
            for (k in 0 until taps) {
                val j = (i + k - half).coerceIn(0, n - 1)
                acc += kernel[k] * input[j].toDouble()
            }
            out[i] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    // ---------------- Stats ----------------

    private fun computeStats(pcm: ShortArray, source: Source): AudioStats {
        if (pcm.isEmpty()) {
            return AudioStats(
                0, 0, Double.NEGATIVE_INFINITY, 0.0, Double.NEGATIVE_INFINITY,
                0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0.0, "n/a", null,
                DoubleArray(BAND_LABELS.size),
                spectralFlatness = 1.0,
                dominantFreqHz = 0.0,
                dominantBinEnergyPct = 0.0,
                peakToMedianDb = 0.0,
                toneAlert = null,
            )
        }
        var peak = 0
        var sumSq = 0.0
        var diffSq = 0.0
        var sum = 0.0
        var zc = 0
        var silent = 0
        var clipped = 0
        var lsbMask = 0
        var longestZeroRun = 0
        var longestRepeatRun = 0
        var curZeroRun = 0
        var curRepeatRun = 1
        val silenceThreshold = 200
        val clipThreshold = 32_700
        var prev = pcm[0]
        for ((i, s) in pcm.withIndex()) {
            val v = s.toInt()
            val a = abs(v)
            if (a > peak) peak = a
            sumSq += (v * v).toDouble()
            sum += v
            lsbMask = lsbMask or (v and 0xFFFF)
            if (a < silenceThreshold) silent++
            if (a >= clipThreshold) clipped++
            if (i > 0) {
                val d = v - prev.toInt()
                diffSq += (d * d).toDouble()
                if ((prev.toInt() xor v) < 0) zc++
                // Repeat-run tracks PLC / stuck-ADC artifacts: identical NON-ZERO samples.
                // Zero-runs (silence) are tracked separately as longestZeroRun below; counting
                // them here would falsely flag every natural speech pause as PLC.
                if (s == prev && v != 0) curRepeatRun++ else {
                    if (curRepeatRun > longestRepeatRun) longestRepeatRun = curRepeatRun
                    curRepeatRun = 1
                }
            }
            if (v == 0) {
                curZeroRun++
                if (curZeroRun > longestZeroRun) longestZeroRun = curZeroRun
            } else curZeroRun = 0
            prev = s
        }
        if (curRepeatRun > longestRepeatRun) longestRepeatRun = curRepeatRun

        val rms = sqrt(sumSq / pcm.size)
        val dc = sum / pcm.size
        val effectiveBits = if (lsbMask == 0) 1 else {
            // index of lowest set bit; bits actually used = 16 - that index
            var lowest = 0
            while (lowest < 16 && (lsbMask shr lowest) and 1 == 0) lowest++
            (16 - lowest).coerceIn(1, 16)
        }
        val highBandRatio = if (sumSq > 0) (diffSq / (sumSq * 4.0)).coerceAtMost(1.0) else 0.0
        // diff energy ≈ 4·sin²(πf/Fs)·E(f); ratio < ~0.05 ⇒ very narrowband content
        val bandwidthHint = when {
            highBandRatio < 0.02 -> "narrowband (≤~3.4 kHz, BLE SCO / CVSD-like)"
            highBandRatio < 0.08 -> "mid-band (≤~7 kHz, mSBC / phone-call grade)"
            highBandRatio < 0.25 -> "wideband"
            else -> "fullband / noisy"
        }
        val longestZeroRunMs = (longestZeroRun * 1000L / TARGET_SAMPLE_RATE).toInt()
        val longestRepeatRunMs = (longestRepeatRun * 1000L / TARGET_SAMPLE_RATE).toInt()

        // Sanity hint for raw PCM byte interpretation
        val rawIssue: String? = if (source.rawPcm) sniffRawByteIssue(source.file) else null

        // Spectral fingerprint + tone analysis via FFT (bounded cost)
        val spectral = computeSpectralAnalysis(pcm, TARGET_SAMPLE_RATE)

        return AudioStats(
            sampleCount = pcm.size,
            peak = peak,
            peakDbFs = toDbFs(peak.toDouble()),
            rms = rms,
            rmsDbFs = toDbFs(rms),
            dcOffset = dc,
            zeroCrossingRate = zc.toDouble() / pcm.size,
            silenceRatio = silent.toDouble() / pcm.size,
            clippedSampleRatio = clipped.toDouble() / pcm.size,
            effectiveBitsUsed = effectiveBits,
            longestZeroRunMs = longestZeroRunMs,
            longestRepeatRunMs = longestRepeatRunMs,
            highBandEnergyRatio = highBandRatio,
            bandwidthHint = bandwidthHint,
            possibleRawByteIssue = rawIssue,
            subBandEnergyPct = spectral.bands,
            spectralFlatness = spectral.flatness,
            dominantFreqHz = spectral.dominantFreqHz,
            dominantBinEnergyPct = spectral.dominantBinEnergyPct,
            peakToMedianDb = spectral.peakToMedianDb,
            toneAlert = spectral.toneAlert,
        )
    }

    /**
     * Quick sanity check for raw PCM files: tries to detect wrong endian /
     * unsigned interpretation by comparing peak amplitudes under different
     * byte interpretations.
     */
    private fun sniffRawByteIssue(file: File): String? {
        return try {
            val sample = file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var off = 0
                while (off < buf.size) {
                    val r = input.read(buf, off, buf.size - off)
                    if (r <= 0) break
                    off += r
                }
                if (off == buf.size) buf else buf.copyOf(off)
            }
            if (sample.size < 1024) return null
            val asLe = bytesToShortsLe(sample)
            val asBe = ShortArray(sample.size / 2)
            val bb = ByteBuffer.wrap(sample).order(ByteOrder.BIG_ENDIAN)
            for (i in asBe.indices) asBe[i] = bb.short
            val peakLe = asLe.maxOf { abs(it.toInt()) }
            val peakBe = asBe.maxOf { abs(it.toInt()) }
            val meanLe = asLe.map { it.toInt() }.average()
            val issues = mutableListOf<String>()
            // If big-endian peak is dramatically smaller, file is probably BE
            if (peakBe < peakLe / 4 && peakBe > 0) issues += "looks BIG-ENDIAN, not little-endian"
            // Strong constant DC bias near +/-128*256 suggests unsigned 8-bit fed in as raw 16-bit
            if (abs(meanLe) > 2000) issues += "large DC bias (${meanLe.toInt()}) — could be unsigned PCM or wrong bit depth"
            issues.joinToString("; ").ifEmpty { null }
        } catch (t: Throwable) { null }
    }

    private data class ChunkStats(
        val peak: Int,
        val peakDbFs: Double,
        val rms: Double,
        val rmsDbFs: Double,
        val zeroCrossingRate: Double,
    )

    private fun quickChunkStats(chunk: ShortArray): ChunkStats {
        if (chunk.isEmpty()) return ChunkStats(0, Double.NEGATIVE_INFINITY, 0.0, Double.NEGATIVE_INFINITY, 0.0)
        var peak = 0
        var sumSq = 0.0
        var zc = 0
        var prev = chunk[0]
        for (s in chunk) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
            sumSq += (s.toInt() * s.toInt()).toDouble()
            if ((prev.toInt() xor s.toInt()) < 0) zc++
            prev = s
        }
        val rms = sqrt(sumSq / chunk.size)
        return ChunkStats(peak, toDbFs(peak.toDouble()), rms, toDbFs(rms), zc.toDouble() / chunk.size)
    }

    private fun toDbFs(value: Double): Double {
        if (value <= 0.0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(value / 32_768.0)
    }

    // ---------------- Logging ----------------

    private fun logAudioInfo(info: AudioInfo) {
        Timber.tag(TAG).i(
            """
            ╭─ Audio file info ──────────────────────────────
            │ label            : %s
            │ path             : %s
            │ size on disk     : %d bytes
            │ container        : %s
            │ wave format code : %d (1 = PCM)
            │ sample rate      : %d Hz
            │ channels         : %d
            │ bits per sample  : %d
            │ byte rate        : %d B/s
            │ samples (per ch) : %d
            │ duration         : %d ms (%.2f s)
            │ → will be resampled to %d Hz mono 16-bit
            ╰────────────────────────────────────────────────
            """.trimIndent(),
            info.source.label,
            info.source.file.absolutePath,
            info.fileSizeBytes,
            if (info.source.rawPcm) "RAW PCM" else info.containerLabel,
            info.audioFormat,
            info.sampleRate,
            info.channels,
            info.bitsPerSample,
            info.byteRate,
            info.totalSamples,
            info.durationMs,
            info.durationMs / 1000.0,
            TARGET_SAMPLE_RATE,
        )
    }

    private fun logAudioStats(label: String, s: AudioStats) {
        Timber.tag(TAG).i(
            """
            ╭─ PCM statistics [%s] (post-normalize) ────────
            │ samples            : %d
            │ peak amplitude     : %d  (%.2f dBFS)
            │ RMS                : %.2f  (%.2f dBFS)
            │ DC offset          : %.2f%s
            │ zero-crossing      : %.4f  (per sample)
            │ silence ratio      : %.2f %% (|x| < 200)
            │ clipped ratio      : %.2f %% (|x| ≥ 32700)
            │ effective bits used: %d / 16%s
            │ longest zero-run   : %d ms%s
            │ longest repeat-run : %d ms%s
            │ high-band energy   : %.4f  → %s
            │ spectral flatness  : %.3f  (0=tone, 1=noise; speech ≈ 0.05–0.30)%s
            │ dominant peak      : %.0f Hz  (%.1f%% of energy, peak/median = +%.1f dB)
            %s%s╰────────────────────────────────────────────────
            """.trimIndent(),
            label,
            s.sampleCount,
            s.peak, s.peakDbFs,
            s.rms, s.rmsDbFs,
            s.dcOffset, if (abs(s.dcOffset) > 50) "  ⚠ noticeable DC bias" else "",
            s.zeroCrossingRate,
            s.silenceRatio * 100.0,
            s.clippedSampleRatio * 100.0,
            s.effectiveBitsUsed,
            if (s.effectiveBitsUsed < 12 && s.sampleCount > TARGET_SAMPLE_RATE) "  ⚠ low — looks like ${s.effectiveBitsUsed}-bit source padded to 16" else "",
            s.longestZeroRunMs, if (s.longestZeroRunMs > 200) "  ⚠ possible transport dropout" else "",
            s.longestRepeatRunMs, if (s.longestRepeatRunMs > 100) "  ⚠ possible PLC / stall / muted ADC" else "",
            s.highBandEnergyRatio, s.bandwidthHint,
            s.spectralFlatness, if (s.spectralFlatness < 0.05) "  ⚠ very tonal" else "",
            s.dominantFreqHz, s.dominantBinEnergyPct, s.peakToMedianDb,
            if (s.toneAlert != null) "│ tone alert        : ⚠ ${s.toneAlert}\n" else "",
            if (s.possibleRawByteIssue != null) "│ raw-PCM warning   : ⚠ ${s.possibleRawByteIssue}\n" else "",
        )
        logSpectralHistogram(label, s)
    }

    private fun logSpectralHistogram(label: String, s: AudioStats) {
        if (s.subBandEnergyPct.isEmpty() || s.subBandEnergyPct.sum() <= 0.0) return
        val sb = StringBuilder()
        sb.append("╭─ Spectral fingerprint [").append(label).append("] ──────────\n")
        for (i in BAND_LABELS.indices) {
            val pct = s.subBandEnergyPct[i]
            sb.append(String.format(
                java.util.Locale.US,
                "│ %-10s %5.1f %% │%s│\n",
                BAND_LABELS[i], pct, formatBandBar(pct)
            ))
        }
        // Quick interpretation
        val above3k4 = s.subBandEnergyPct.drop(3).sum()      // 3.4k–12k
        val above8k  = s.subBandEnergyPct.last()             // 8k–12k
        val hint = when {
            above3k4 < 1.0 -> "→ NARROWBAND only (BLE SCO / CVSD / telephony)"
            above8k  < 1.0 -> "→ wideband-ish (no true fullband content, possibly mSBC / 16 kHz mic)"
            else           -> "→ fullband content present"
        }
        sb.append("│ ").append(hint).append('\n')
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())

        logWavTokenizerSuitability(label, s)
    }

    // ---------------- WavTokenizer-specific suitability ----------------

    /**
     * Heuristic "happy zone" thresholds derived from WavTokenizer training data
     * (LibriTTS / CommonVoice / LJSpeech / AudioSet @ 24 kHz mono 16-bit).
     *
     * WavTokenizer is single-VQ (4096-entry codebook, 12-bit token, 40/75 Hz token rate).
     * Unlike EnCodec/DAC which use RVQ and can absorb small input deviations, a single-VQ
     * codec snaps every frame to one codebook entry — small spectral/loudness drift in the
     * input causes large token swings and therefore audible decoder artifacts.
     */
    private object WtThresholds {
        const val RMS_MIN_DBFS = -32.0      // below this → many silence-token frames, info loss
        const val RMS_MAX_DBFS = -6.0       // above this → encoder saturation territory
        const val RMS_IDEAL_LOW = -24.0
        const val RMS_IDEAL_HIGH = -12.0
        const val PEAK_MAX_DBFS = -0.5      // any clipping = encoder breaks
        const val DC_MAX = 80.0             // absolute DC bias in 16-bit counts
        const val MIN_HIGHBAND_PCT = 5.0    // % energy above 3.4 kHz — below = narrowband / SCO
        const val MIN_BITS_USED = 12        // <12 effective bits ⇒ padded / truncated source
        const val MAX_REPEAT_RUN_MS = 60    // longer ⇒ PLC / stall — codec will hallucinate
        const val MAX_ZERO_RUN_MS = 120     // longer ⇒ transport dropout
        const val MAX_SILENCE_RATIO = 0.80  // mostly silence ⇒ codec just outputs silence tokens
        const val MAX_CLIP_RATIO = 0.001    // any meaningful clipping is bad
    }

    private fun logWavTokenizerSuitability(label: String, s: AudioStats) {
        val issues = mutableListOf<String>()
        val warns = mutableListOf<String>()

        // Loudness
        when {
            s.rmsDbFs.isInfinite() -> issues += "RMS = −∞ (silence) → all frames map to silence token"
            s.rmsDbFs < WtThresholds.RMS_MIN_DBFS ->
                issues += "RMS %.1f dBFS too quiet (need > %.0f) → info loss in silence tokens"
                    .format(java.util.Locale.US, s.rmsDbFs, WtThresholds.RMS_MIN_DBFS)
            s.rmsDbFs > WtThresholds.RMS_MAX_DBFS ->
                issues += "RMS %.1f dBFS too hot → encoder saturation".format(java.util.Locale.US, s.rmsDbFs)
            s.rmsDbFs < WtThresholds.RMS_IDEAL_LOW || s.rmsDbFs > WtThresholds.RMS_IDEAL_HIGH ->
                warns += "RMS %.1f dBFS outside ideal %.0f…%.0f dBFS — quality degraded but usable"
                    .format(java.util.Locale.US, s.rmsDbFs, WtThresholds.RMS_IDEAL_LOW, WtThresholds.RMS_IDEAL_HIGH)
        }

        // Clipping
        if (s.peakDbFs > WtThresholds.PEAK_MAX_DBFS)
            warns += "peak %.2f dBFS — near clip line".format(java.util.Locale.US, s.peakDbFs)
        if (s.clippedSampleRatio > WtThresholds.MAX_CLIP_RATIO)
            issues += "clipped %.2f%% of samples → token noise".format(java.util.Locale.US, s.clippedSampleRatio * 100)

        // DC bias
        if (abs(s.dcOffset) > WtThresholds.DC_MAX)
            warns += "DC offset %.0f → shifts encoder activations → consistent token drift".format(java.util.Locale.US, s.dcOffset)

        // Bandwidth (training data is wideband).
        //
        // Three cases to distinguish:
        //   • 3.4–8 k > 1 %  AND  8–12 k near zero  ⇒ natively 16 kHz Fs mic (mSBC, phone
        //     VOICE_RECOGNITION) — well within WavTokenizer training distribution, only a
        //     mild informational warning.
        //   • Both 3.4–8 k AND 8–12 k near zero     ⇒ true SCO/CVSD telephony bandwidth —
        //     real out-of-distribution input; codec will hallucinate highs.
        //   • Otherwise — fullband content present, no flag.
        val band3k4_8k = s.subBandEnergyPct.getOrNull(3) ?: Double.NaN
        val band8k_12k = s.subBandEnergyPct.getOrNull(4) ?: Double.NaN
        if (band3k4_8k.isFinite() && band8k_12k.isFinite()) {
            when {
                band3k4_8k < 1.0 && band8k_12k < 0.5 ->
                    issues += "narrowband (≤~3.4 kHz, 3.4–8k=%.1f%% 8–12k=%.2f%%) → true SCO/CVSD input → decoder will hallucinate highs"
                        .format(java.util.Locale.US, band3k4_8k, band8k_12k)
                band8k_12k < 0.5 ->
                    warns += "natively ≤8 kHz source (3.4–8k=%.1f%% 8–12k=%.2f%%) — looks like a 16 kHz mic (mSBC / VOICE_RECOGNITION). Fine for WavTokenizer; no true fullband content available."
                        .format(java.util.Locale.US, band3k4_8k, band8k_12k)
                // else: both bands have meaningful content → fullband, no flag.
            }
        }

        // Bit depth
        if (s.effectiveBitsUsed in 1 until WtThresholds.MIN_BITS_USED)
            warns += "only %d effective bits → looks padded/truncated; unnatural quantization noise floor"
                .format(java.util.Locale.US, s.effectiveBitsUsed)

        // Transport / PLC artifacts
        if (s.longestRepeatRunMs > WtThresholds.MAX_REPEAT_RUN_MS)
            issues += "%d ms repeat-run → BLE/USB PLC or stall → codec will output coherent but WRONG audio"
                .format(java.util.Locale.US, s.longestRepeatRunMs)
        if (s.longestZeroRunMs > WtThresholds.MAX_ZERO_RUN_MS)
            warns += "%d ms zero-run → transport dropout".format(java.util.Locale.US, s.longestZeroRunMs)

        // Silence
        if (s.silenceRatio > WtThresholds.MAX_SILENCE_RATIO)
            warns += "%.0f%% silent → most tokens will be silence".format(java.util.Locale.US, s.silenceRatio * 100)

        // Tonal contamination ("piiii" whine, mains hum, switching-supply leakage).
        // This is a *blocker* for WavTokenizer because:
        //   - sustained tone sits in a tiny corner of feature-space the codebook
        //     barely covers → token diversity collapses,
        //   - it dominates the encoder's input normalization → speech mixed with
        //     the tone is replaced by tone-tokens after decode,
        //   - the iSTFT decoder's overlap-add produces phase warbling on tones.
        if (s.toneAlert != null)
            issues += "tonal contamination: ${s.toneAlert} → codebook collapse + speech masking"

        val verdict = when {
            issues.isNotEmpty() -> "✗ POOR — expect audible artifacts / wrong content"
            warns.isNotEmpty()  -> "△ OK — quality degraded but recognizable"
            else                -> "✓ GOOD — input matches WavTokenizer training distribution"
        }

        val sb = StringBuilder()
        sb.append("╭─ WavTokenizer suitability [").append(label).append("] ─────\n")
        sb.append("│ verdict: ").append(verdict).append('\n')
        if (issues.isNotEmpty()) {
            sb.append("│ blockers:\n")
            issues.forEach { sb.append("│   ✗ ").append(it).append('\n') }
        }
        if (warns.isNotEmpty()) {
            sb.append("│ warnings:\n")
            warns.forEach { sb.append("│   △ ").append(it).append('\n') }
        }
        if (issues.isEmpty() && warns.isEmpty()) {
            sb.append("│   (within training-distribution loudness, bandwidth, bit-depth and PLC bounds)\n")
        }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    // ---------------- WavTokenizer round-trip (encode → decode → compare) ----------------

    /** Default destination for round-trip artifacts. */
    private fun defaultArtifactDir(context: Context, destination: String): File {
        val baseDir = try {
            File(DataManager.fileLocation)
        } catch (t: Throwable) {
            context.getExternalFilesDir(null) ?: context.cacheDir
        }
        return File(baseDir, "test_feeder/${sanitizeFileStem(destination)}/roundtrip")
    }

    /** Strip path separators / weird chars so labels can safely become filenames. */
    private fun sanitizeFileStem(s: String): String {
        val cleaned = s.trim().replace(Regex("[^A-Za-z0-9._\\-]+"), "_")
        return if (cleaned.isEmpty()) "src" else cleaned.take(64)
    }

    /**
     * Produces visually-distinct display labels for cross-source tables.
     *
     * Plain `label.take(maxLen)` collapses labels that share a common prefix —
     * e.g. `picked_1779615873278` and `picked_1779613670672` both become
     * `picked_177`, making the comparison table unreadable. This helper:
     *
     *  1. strips the longest common prefix shared by ALL labels (if it's long
     *     enough to be worth stripping),
     *  2. strips the longest common suffix the same way,
     *  3. if the remaining label is still longer than `maxLen`, ellipsizes
     *     in the middle to keep both the start and the end visible
     *     (e.g. `1779615873278_phone` → `17796…phone`).
     *
     * Trivial cases (single label, all labels identical, short labels) fall
     * through unchanged.
     */
    private fun disambiguateLabels(labels: List<String>, maxLen: Int): List<String> {
        if (labels.isEmpty()) return labels
        if (labels.size == 1) return listOf(labels[0].take(maxLen))

        // 1. Longest common prefix.
        var commonPrefix = labels[0]
        for (s in labels.drop(1)) {
            val n = minOf(commonPrefix.length, s.length)
            var k = 0
            while (k < n && commonPrefix[k] == s[k]) k++
            commonPrefix = commonPrefix.substring(0, k)
            if (commonPrefix.isEmpty()) break
        }
        // Only strip the prefix if it leaves at least 3 chars of distinct content.
        val minRemainder = 3
        val stripPrefix = commonPrefix.isNotEmpty() &&
            labels.all { it.length - commonPrefix.length >= minRemainder }

        val afterPrefix = if (stripPrefix) labels.map { it.removePrefix(commonPrefix) } else labels

        // 2. Longest common suffix on what's left.
        var commonSuffix = afterPrefix[0]
        for (s in afterPrefix.drop(1)) {
            val a = commonSuffix; val b = s
            val n = minOf(a.length, b.length)
            var k = 0
            while (k < n && a[a.length - 1 - k] == b[b.length - 1 - k]) k++
            commonSuffix = a.substring(a.length - k)
            if (commonSuffix.isEmpty()) break
        }
        val stripSuffix = commonSuffix.isNotEmpty() &&
            afterPrefix.all { it.length - commonSuffix.length >= minRemainder }

        val core = if (stripSuffix) afterPrefix.map { it.removeSuffix(commonSuffix) } else afterPrefix

        // 3. Middle-ellipsize anything still too long.
        return core.map { ellipsizeMiddle(it, maxLen) }
    }

    /** Truncate [s] to [maxLen] chars, keeping head and tail and inserting `…` in the middle. */
    private fun ellipsizeMiddle(s: String, maxLen: Int): String {
        if (s.length <= maxLen) return s
        if (maxLen <= 1) return s.take(maxLen)
        // Reserve one char for the ellipsis.
        val keep = maxLen - 1
        val head = (keep + 1) / 2
        val tail = keep - head
        return s.substring(0, head) + "…" + s.substring(s.length - tail)
    }

    /**
     * Writes a 16-bit PCM little-endian mono WAV file. Standard 44-byte RIFF header.
     */
    private fun writePcm16Wav(file: File, pcm: ShortArray, sampleRate: Int) {
        val byteRate = sampleRate * 2  // mono, 16-bit
        val dataSize = pcm.size * 2
        val totalSize = 36 + dataSize
        java.io.BufferedOutputStream(java.io.FileOutputStream(file)).use { out ->
            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intToLeBytes(totalSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            // fmt chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intToLeBytes(16))                 // chunk size
            out.write(shortToLeBytes(1))                // PCM
            out.write(shortToLeBytes(1))                // mono
            out.write(intToLeBytes(sampleRate))
            out.write(intToLeBytes(byteRate))
            out.write(shortToLeBytes(2))                // block align
            out.write(shortToLeBytes(16))               // bits per sample
            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intToLeBytes(dataSize))
            val buf = ByteArray(dataSize)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bb.putShort(s)
            out.write(buf)
        }
    }

    private fun intToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )
    private fun shortToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
    )

    /**
     * Writes encoded tokens as a human-readable text file, one token per line,
     * with a small header that ties the file back to the original audio source
     * and to WavTokenizer's framing parameters (40 tokens / sec, 12-bit codebook).
     */
    private fun writeTokensTxt(file: File, tokens: LongArray, source: Source, chunks: Int) {
        file.bufferedWriter().use { w ->
            w.write("# WavTokenizer encoded tokens\n")
            w.write("# source.label    : ${source.label}\n")
            w.write("# source.file     : ${source.file.absolutePath}\n")
            w.write("# source.size     : ${source.file.length()} bytes\n")
            w.write("# sample_rate     : $TARGET_SAMPLE_RATE\n")
            w.write("# chunk_samples   : $SAMPLES_PER_CHUNK   (${CHUNK_DURATION_MS} ms)\n")
            w.write("# tokens_per_sec  : 40\n")
            w.write("# codebook_size   : 4096 (12-bit)\n")
            w.write("# total_chunks    : $chunks\n")
            w.write("# total_tokens    : ${tokens.size}\n")
            w.write("# format          : one token (uint12 as decimal) per line\n")
            for (t in tokens) {
                w.write(t.toString())
                w.write("\n")
            }
        }
    }

    /**
     * Writes encoded tokens as a compact little-endian binary file:
     *   magic(4) "WTOK" | version(uint16=1) | tokenCount(uint32) | tokens[uint16] × N
     *
     * Uses 16-bit little-endian per token because the codebook is 12-bit (≤ 4096),
     * keeping the file 1/4 the size of a Long-per-token dump while staying easy to
     * read from any host tool.
     */
    private fun writeTokensBin(file: File, tokens: LongArray) {
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file))).use { out ->
            out.write("WTOK".toByteArray(Charsets.US_ASCII))
            out.write(shortToLeBytes(1))                       // version
            out.write(intToLeBytes(tokens.size))
            val buf = ByteArray(tokens.size * 2)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (t in tokens) {
                val clipped = (t.toInt() and 0xFFFF).toShort()
                bb.putShort(clipped)
            }
            out.write(buf)
        }
    }

    /**
     * Result of running an audio source through `WavTokenizerEncoder.encode()`
     * followed by `WavTokenizerDecoder.decode()` and comparing the reconstructed
     * PCM to the input.
     *
     * Metrics:
     *  - [psnrDb]                — Peak SNR. Higher is better. >25 dB ≈ transparent for speech codecs;
     *                              15–25 dB is typical for neural codecs at ~1 kbps; <10 dB is bad.
     *  - [siSdrDb]               — Scale-Invariant SDR (Le Roux 2019). Better than PSNR for codec eval
     *                              because it ignores overall gain. >10 dB is recognizable speech.
     *  - [tokenDiversity]        — unique tokens / 4096 (codebook size). Healthy speech uses ~5–20 %.
     *                              <1 % ⇒ codec is collapsing to a few "silence-ish" entries
     *                              (input is OOD, e.g. SCO/narrowband).
     *  - [perBandSpectralDistortionDb] — dB difference per [BAND_LABELS] band between input and
     *                              reconstructed spectra. Positive ⇒ codec added energy there
     *                              (often phantom highs); negative ⇒ codec lost energy there.
     *  - [tokensPerSecond]       — should be ≈ 40 for WavTokenizer-large.
     *  - [alignmentLagSamples]   — lag (in samples) found by cross-correlation between input and
     *                              reconstructed; quantifies the codec's algorithmic delay.
     */
    data class RoundTripResult(
        val label: String,
        val inputSamples: Int,
        val reconstructedSamples: Int,
        val chunks: Int,
        val tokens: Int,
        val uniqueTokens: Int,
        val tokenDiversity: Double,
        val tokensPerSecond: Double,
        val avgEncodeMs: Double,
        val avgDecodeMs: Double,
        val realTimeFactor: Double,         // (encode+decode) / audio_duration. <1 ⇒ faster than realtime
        /**
         * Log-Spectral Distance in dB — average frame-wise RMS difference of log-magnitude
         * spectra between input and reconstruction. **Primary quality metric for
         * WavTokenizer / any iSTFT-based neural codec** because it is phase-invariant.
         * Rough guide: <3 dB transparent · 3–6 dB good · 6–10 dB noticeable · >10 dB bad.
         */
        val logSpectralDistanceDb: Double,
        /**
         * Peak-SNR in the sample domain. **Phase-sensitive, unreliable for iSTFT codecs.**
         * High [psnrDb] together with very negative [siSdrDb] is the fingerprint of correct
         * spectrum but different phase — perfectly normal for WavTokenizer. Use
         * [logSpectralDistanceDb] for verdict; treat PSNR / SI-SDR as informational only.
         */
        val psnrDb: Double,
        /** Scale-Invariant SDR. Phase-sensitive; see [psnrDb] caveat. */
        val siSdrDb: Double,
        val alignmentLagSamples: Int,
        val perBandSpectralDistortionDb: DoubleArray,
        val error: String?,                 // non-null if the round-trip failed mid-way
        /** Saved artifacts (null if writing failed or feature was disabled). */
        val tokensTxtFile: File?,
        val tokensBinFile: File?,
        val decodedWavFile: File?,
        val originalNormalizedWavFile: File?,
    )

    private fun runRoundTrip(source: Source, pcm: ShortArray, artifactDir: File): RoundTripResult? {
        val label = source.label
        // Resolve encoder/decoder lazily and tolerate uninitialized state.
        // NOTE: AIModuleInitializer exposes them as `lateinit var`, so we can't use
        // ::isInitialized from outside the object — catch the access exception instead.
        val ai = com.commcrete.stardust.ai.codec.AIModuleInitializer
        if (!ai.aiEnabled) {
            Timber.tag(TAG).w("Round-trip skipped: AIModuleInitializer.aiEnabled == false")
            return null
        }
        val encoder = try {
            ai.wavTokenizerEncoder
        } catch (e: UninitializedPropertyAccessException) {
            Timber.tag(TAG).w("Round-trip skipped: wavTokenizerEncoder not initialized yet")
            return null
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Round-trip skipped: encoder unavailable")
            return null
        }
        val decoder = try {
            ai.wavTokenizerDecoder
        } catch (e: UninitializedPropertyAccessException) {
            Timber.tag(TAG).w("Round-trip skipped: wavTokenizerDecoder not initialized yet")
            return null
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Round-trip skipped: decoder unavailable")
            return null
        }

        if (pcm.size < SAMPLES_PER_CHUNK) {
            Timber.tag(TAG).w("Round-trip skipped: input shorter than one 0.5 s chunk")
            return null
        }

        val allTokens = ArrayList<Long>(pcm.size / 600 + 16)
        val reconstructedList = ArrayList<Short>(pcm.size + 4096)
        var encTotalNs = 0L
        var decTotalNs = 0L
        var chunks = 0
        var err: String? = null

        // Decoder context — mirrors what PttSendManager passes in production.
        // WavTokenizerDecoder.decode() needs the PREVIOUS chunk's tokens and
        // decoded samples to:
        //   1. concatenate previous + current tokens before running the model
        //      (`combinedData = previousTokens + data`), giving the model
        //      coherent past context instead of a cold start every 500 ms,
        //   2. find a clean splice point in Smart-mode trimming
        //      (`findLowestEnergyTokenBoundary`),
        //   3. crossfade the first 400 samples of the new chunk against the
        //      tail of the previous one (`fixAudioAlignment2`) to remove the
        //      iSTFT phase-reset "tick" between chunks.
        // Passing null/null here was the previous bug: it generated artifacts
        // at every chunk boundary that don't exist in real-time playback.
        var prevTokensCtx: List<Long>? = null
        var prevSamplesCtx: ShortArray? = null

        var i = 0
        while (i + SAMPLES_PER_CHUNK <= pcm.size) {
            val chunk = pcm.copyOfRange(i, i + SAMPLES_PER_CHUNK)
            try {
                val t0 = System.nanoTime()
                val tokens = encoder.encode(chunk)
                val t1 = System.nanoTime()
                val tokenList = tokens.toList()
                val rec = decoder.decode(
                    tokenList,
                    prevTokensCtx,
                    prevSamplesCtx,
                    com.commcrete.aiaudio.codecs.WavTokenizerDecoder.ModelType.General
                )
                val t2 = System.nanoTime()
                encTotalNs += (t1 - t0)
                decTotalNs += (t2 - t1)
                for (t in tokens) allTokens.add(t)
                for (s in rec) reconstructedList.add(s)
                // Carry context forward for the next chunk — same pattern
                // PttSendManager uses with its `lastTokens` / `lastPCM` fields.
                prevTokensCtx = tokenList
                prevSamplesCtx = rec
                chunks++
            } catch (t: Throwable) {
                err = "chunk $chunks failed: ${t.message}"
                Timber.tag(TAG).e(t, "Round-trip chunk %d failed for %s", chunks, label)
                break
            }
            i += SAMPLES_PER_CHUNK
        }

        if (chunks == 0) {
            return RoundTripResult(
                label, pcm.size, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0,
                Double.NaN,                                     // logSpectralDistanceDb
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0,
                DoubleArray(BAND_LABELS.size), err ?: "no chunks processed",
                null, null, null, null
            )
        }

        val reconstructed = ShortArray(reconstructedList.size) { reconstructedList[it] }
        val tokensArr = LongArray(allTokens.size) { allTokens[it] }

        // Persist artifacts BEFORE alignment so files reflect raw codec output.
        val sourceStem = sanitizeFileStem(source.file.nameWithoutExtension.ifBlank { source.label })
        val timeStamp = System.currentTimeMillis()
        val baseName = "${timeStamp}_${sourceStem}"
        val tokensTxt = File(artifactDir, "$baseName.tokens.txt")
        val tokensBin = File(artifactDir, "$baseName.tokens.bin")
        val decodedWav = File(artifactDir, "$baseName.decoded.wav")
        val originalWav = File(artifactDir, "$baseName.original_24k_mono.wav")

        val written = mutableListOf<String>()
        runCatching { writeTokensTxt(tokensTxt, tokensArr, source, chunks) }
            .onSuccess { written += "tokens.txt" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", tokensTxt.name) }
        runCatching { writeTokensBin(tokensBin, tokensArr) }
            .onSuccess { written += "tokens.bin" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", tokensBin.name) }
        runCatching { writePcm16Wav(decodedWav, reconstructed, TARGET_SAMPLE_RATE) }
            .onSuccess { written += "decoded.wav" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", decodedWav.name) }
        runCatching { writePcm16Wav(originalWav, pcm, TARGET_SAMPLE_RATE) }
            .onSuccess { written += "original.wav" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", originalWav.name) }

        if (written.isNotEmpty()) {
            Timber.tag(TAG).i(
                "  ↳ wrote artifacts for [%s] in %s: %s",
                label, artifactDir.absolutePath, written.joinToString(", ")
            )
        }

        // Align reconstructed to input via cross-correlation in ±100 ms window.
        val maxLag = 2400 // ±100 ms @ 24 kHz
        val lag = bestLagXcorr(pcm, reconstructed, maxLag)
        val (refAligned, recAligned) = alignByLag(pcm, reconstructed, lag)
        val n = minOf(refAligned.size, recAligned.size)
        val ref = refAligned.copyOfRange(0, n)
        val rec = recAligned.copyOfRange(0, n)

        val psnr = computePsnr(ref, rec)
        val siSdr = computeSiSdr(ref, rec)
        val lsd = computeLogSpectralDistanceDb(ref, rec)

        val origBands = computeSubBandEnergyPct(ref, TARGET_SAMPLE_RATE)
        val recBands  = computeSubBandEnergyPct(rec, TARGET_SAMPLE_RATE)
        val bandDist = DoubleArray(origBands.size) { idx ->
            val o = max(origBands[idx], 0.05)   // floor at 0.05 % to avoid −inf
            val r = max(recBands[idx],  0.05)
            10.0 * log10(r / o)
        }

        val uniq = tokensArr.toHashSet().size
        val durationSec = (chunks * SAMPLES_PER_CHUNK).toDouble() / TARGET_SAMPLE_RATE
        val tps = tokensArr.size / durationSec
        val rtf = (encTotalNs + decTotalNs) / 1e9 / durationSec

        return RoundTripResult(
            label = label,
            inputSamples = pcm.size,
            reconstructedSamples = reconstructed.size,
            chunks = chunks,
            tokens = tokensArr.size,
            uniqueTokens = uniq,
            tokenDiversity = uniq.toDouble() / 4096.0,
            tokensPerSecond = tps,
            avgEncodeMs = encTotalNs / 1e6 / chunks,
            avgDecodeMs = decTotalNs / 1e6 / chunks,
            realTimeFactor = rtf,
            logSpectralDistanceDb = lsd,
            psnrDb = psnr,
            siSdrDb = siSdr,
            alignmentLagSamples = lag,
            perBandSpectralDistortionDb = bandDist,
            error = err,
            tokensTxtFile = tokensTxt.takeIf { it.exists() && it.length() > 0 },
            tokensBinFile = tokensBin.takeIf { it.exists() && it.length() > 0 },
            decodedWavFile = decodedWav.takeIf { it.exists() && it.length() > 0 },
            originalNormalizedWavFile = originalWav.takeIf { it.exists() && it.length() > 0 },
        )
    }

    /** Brute-force time-domain cross-correlation peak in [-maxLag, +maxLag]. */
    private fun bestLagXcorr(ref: ShortArray, rec: ShortArray, maxLag: Int): Int {
        // Use the first ~2 s of overlap to keep it cheap.
        val window = minOf(ref.size, rec.size, 2 * TARGET_SAMPLE_RATE)
        if (window <= maxLag * 2) return 0
        var bestLag = 0
        var bestScore = Long.MIN_VALUE
        for (lag in -maxLag..maxLag step 8) {        // coarse step 8 first
            var acc = 0L
            var k = 0
            while (k < window) {
                val ri = k
                val ci = k + lag
                if (ci in 0 until rec.size && ri < ref.size) {
                    acc += ref[ri].toInt().toLong() * rec[ci].toInt().toLong()
                }
                k += 4 // subsample for speed
            }
            if (acc > bestScore) { bestScore = acc; bestLag = lag }
        }
        // refine ±8 around coarse maximum
        var bestRefined = bestLag
        var bestRefinedScore = Long.MIN_VALUE
        for (lag in (bestLag - 8)..(bestLag + 8)) {
            var acc = 0L
            var k = 0
            while (k < window) {
                val ci = k + lag
                if (ci in 0 until rec.size && k < ref.size) {
                    acc += ref[k].toInt().toLong() * rec[ci].toInt().toLong()
                }
                k++
            }
            if (acc > bestRefinedScore) { bestRefinedScore = acc; bestRefined = lag }
        }
        return bestRefined
    }

    private fun alignByLag(ref: ShortArray, rec: ShortArray, lag: Int): Pair<ShortArray, ShortArray> {
        return if (lag >= 0) {
            val recCut = if (lag >= rec.size) ShortArray(0) else rec.copyOfRange(lag, rec.size)
            ref to recCut
        } else {
            val refCut = if (-lag >= ref.size) ShortArray(0) else ref.copyOfRange(-lag, ref.size)
            refCut to rec
        }
    }

    private fun computePsnr(ref: ShortArray, rec: ShortArray): Double {
        if (ref.isEmpty() || rec.isEmpty()) return Double.NEGATIVE_INFINITY
        val n = minOf(ref.size, rec.size)
        var mse = 0.0
        for (i in 0 until n) {
            val d = ref[i].toInt() - rec[i].toInt()
            mse += d.toDouble() * d.toDouble()
        }
        mse /= n
        if (mse <= 0.0) return Double.POSITIVE_INFINITY
        return 20.0 * log10(32_767.0 / sqrt(mse))
    }

    /** Scale-Invariant Source-to-Distortion Ratio (Le Roux et al., 2019). */
    private fun computeSiSdr(ref: ShortArray, rec: ShortArray): Double {
        val n = minOf(ref.size, rec.size)
        if (n == 0) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        var refEnergy = 0.0
        for (i in 0 until n) {
            val r = ref[i].toDouble()
            val e = rec[i].toDouble()
            dot += r * e
            refEnergy += r * r
        }
        if (refEnergy <= 0.0) return Double.NEGATIVE_INFINITY
        val alpha = dot / refEnergy
        var sigE = 0.0
        var noiseE = 0.0
        for (i in 0 until n) {
            val target = alpha * ref[i].toDouble()
            val noise = rec[i].toDouble() - target
            sigE += target * target
            noiseE += noise * noise
        }
        if (noiseE <= 0.0) return Double.POSITIVE_INFINITY
        return 10.0 * log10(sigE / noiseE)
    }

    /**
     * Log-Spectral Distance (LSD), in dB, averaged over Hann-windowed STFT frames.
     *
     * For each frame f:
     *     LSD_f = sqrt(  mean_k ( 20·log10|S_ref(f,k)+ε| − 20·log10|S_rec(f,k)+ε| )²  )
     *
     * The metric is **phase-invariant** — it compares magnitude spectra only — which is
     * exactly what's needed for iSTFT-based generative codecs (WavTokenizer, HiFi-GAN,
     * Vocos, etc.) where sample-domain metrics like PSNR/SI-SDR look catastrophic even
     * when the perceived audio is essentially unchanged.
     *
     * Returns `Double.NaN` if either signal is shorter than one FFT window.
     */
    private fun computeLogSpectralDistanceDb(ref: ShortArray, rec: ShortArray): Double {
        val n = minOf(ref.size, rec.size)
        if (n < FFT_SIZE) return Double.NaN
        val hop = FFT_SIZE / 2
        val reR = DoubleArray(FFT_SIZE); val imR = DoubleArray(FFT_SIZE)
        val reC = DoubleArray(FFT_SIZE); val imC = DoubleArray(FFT_SIZE)
        val magR = DoubleArray(FFT_SIZE / 2 + 1)
        val magC = DoubleArray(FFT_SIZE / 2 + 1)
        val nyquistBin = FFT_SIZE / 2
        // Floor log-magnitudes at this many dB below the frame's peak. Anything quieter
        // is treated as "silent" and clamped to the same value in both signals, so it
        // contributes 0 distortion. Without this floor, near-zero bins (extremely common
        // in narrowband / low-pass-filtered audio) would dominate the metric whenever the
        // codec leaves the tiniest non-zero energy in those bins (e.g. mag ≈ 1e-3 × peak
        // becomes a 60+ dB "error" against log(eps) ≈ −180 dB). This matches the
        // dynamic-range floor convention used in standard neural-codec evaluation.
        val floorBelowPeakDb = 60.0
        val floorRatio = 10.0.pow(-floorBelowPeakDb / 20.0)
        var totalLsd = 0.0
        var frames = 0
        var offset = 0
        // Bound the work: ≤ 30 s of audio is plenty for a stable LSD estimate.
        val cap = minOf(n, FFT_MAX_SAMPLES)
        while (offset + FFT_SIZE <= cap) {
            for (i in 0 until FFT_SIZE) {
                reR[i] = ref[offset + i].toDouble() * hannWindow[i]; imR[i] = 0.0
                reC[i] = rec[offset + i].toDouble() * hannWindow[i]; imC[i] = 0.0
            }
            fftInPlace(reR, imR)
            fftInPlace(reC, imC)
            // First pass: per-frame magnitudes + peak.
            var peakMag = 0.0
            for (bin in 1..nyquistBin) {
                val mr = sqrt(reR[bin] * reR[bin] + imR[bin] * imR[bin])
                val mc = sqrt(reC[bin] * reC[bin] + imC[bin] * imC[bin])
                magR[bin] = mr; magC[bin] = mc
                if (mr > peakMag) peakMag = mr
                if (mc > peakMag) peakMag = mc
            }
            if (peakMag <= 0.0) { offset += hop; continue }
            val floor = peakMag * floorRatio
            var sqSum = 0.0
            for (bin in 1..nyquistBin) {
                val lr = 20.0 * log10(max(magR[bin], floor))
                val lc = 20.0 * log10(max(magC[bin], floor))
                val d = lr - lc
                sqSum += d * d
            }
            totalLsd += sqrt(sqSum / nyquistBin)
            offset += hop
            frames++
        }
        return if (frames > 0) totalLsd / frames else Double.NaN
    }

    private fun logRoundTrip(r: RoundTripResult) {
        val sb = StringBuilder()
        sb.append("╭─ WavTokenizer round-trip [").append(r.label).append("] ───\n")
        if (r.error != null) sb.append("│ ⚠ partial result: ").append(r.error).append('\n')
        sb.append(String.format(java.util.Locale.US,
            "│ chunks=%d  tokens=%d  uniqueTokens=%d (%.2f%% of 4096)\n",
            r.chunks, r.tokens, r.uniqueTokens, r.tokenDiversity * 100))
        sb.append(String.format(java.util.Locale.US,
            "│ token rate=%.1f Hz (expected ≈ 40)\n", r.tokensPerSecond))
        sb.append(String.format(java.util.Locale.US,
            "│ encode avg=%.1f ms/chunk  decode avg=%.1f ms/chunk  RTF=%.3f%s\n",
            r.avgEncodeMs, r.avgDecodeMs, r.realTimeFactor,
            if (r.realTimeFactor > 1.0) "  ⚠ slower than realtime" else ""))
        sb.append(String.format(java.util.Locale.US,
            "│ alignment lag=%+d samples (%+.1f ms — codec algorithmic delay)\n",
            r.alignmentLagSamples, r.alignmentLagSamples * 1000.0 / TARGET_SAMPLE_RATE))

        // LSD — the metric that actually matters for an iSTFT codec.
        sb.append(String.format(java.util.Locale.US,
            "│ Log-Spectral Distance = %s dB %s   ← primary metric (phase-invariant)\n",
            if (r.logSpectralDistanceDb.isNaN()) "n/a"
            else "%.2f".format(java.util.Locale.US, r.logSpectralDistanceDb),
            qualityLabelLsd(r.logSpectralDistanceDb)))

        // PSNR / SI-SDR — kept for reference but explicitly flagged as unreliable
        // for this codec class. WavTokenizer decodes via iSTFT, so phase is not
        // preserved and sample-domain metrics can look catastrophic even when
        // the audio sounds essentially identical.
        sb.append("│ Sample-domain metrics (phase-sensitive — INFORMATIONAL ONLY for iSTFT codecs):\n")
        sb.append(String.format(java.util.Locale.US,
            "│   PSNR   = %.2f dB %s\n", r.psnrDb, qualityLabelPsnr(r.psnrDb)))
        sb.append(String.format(java.util.Locale.US,
            "│   SI-SDR = %.2f dB %s\n", r.siSdrDb, qualityLabelSiSdr(r.siSdrDb)))
        if (r.psnrDb > 10.0 && r.siSdrDb < 0.0) {
            sb.append("│   (high PSNR + very negative SI-SDR is the typical fingerprint of a\n")
            sb.append("│    spectrally-correct but phase-shifted reconstruction — expected here.)\n")
        }

        sb.append("│ per-band spectral distortion (rec − orig, dB):\n")
        for (i in BAND_LABELS.indices) {
            val d = r.perBandSpectralDistortionDb.getOrNull(i) ?: 0.0
            val flag = when {
                d >  6.0 -> "  ⚠ hallucinated energy"
                d < -6.0 -> "  ⚠ lost energy"
                else     -> ""
            }
            sb.append(String.format(java.util.Locale.US,
                "│   %-10s %+6.2f dB%s\n", BAND_LABELS[i], d, flag))
        }
        // Aggregate verdict — LSD-first, with token-diversity as the codebook-collapse check.
        val verdict = when {
            r.error != null                          -> "✗ FAILED — ${r.error}"
            r.logSpectralDistanceDb.isNaN()          -> "?? — insufficient audio for spectral verdict"
            r.tokenDiversity < 0.005                  -> "✗ POOR — codebook collapse (uniqueTokens < 0.5%)"
            r.logSpectralDistanceDb > 10.0           -> "✗ POOR — reconstruction spectrally diverges from input"
            r.logSpectralDistanceDb >  6.0           -> "△ OK — recognizable but lossy"
            else                                     -> "✓ GOOD — spectrum well-preserved (typical for neural codec)"
        }
        sb.append("│ verdict: ").append(verdict).append('\n')
        // Saved artifacts (paths are absolute so they can be `adb pull`-ed directly).
        r.originalNormalizedWavFile?.let { sb.append("│ original (24k mono) : ").append(it.absolutePath).append('\n') }
        r.tokensTxtFile?.let           { sb.append("│ tokens (txt)        : ").append(it.absolutePath).append('\n') }
        r.tokensBinFile?.let           { sb.append("│ tokens (bin)        : ").append(it.absolutePath).append('\n') }
        r.decodedWavFile?.let          { sb.append("│ decoded             : ").append(it.absolutePath).append('\n') }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    private fun qualityLabelPsnr(p: Double): String = when {
        p.isInfinite() && p > 0 -> "(identical)"
        p >= 25.0 -> "(transparent)"
        p >= 18.0 -> "(good)"
        p >= 12.0 -> "(noticeable artifacts)"
        p >=  6.0 -> "(degraded)"
        else      -> "(broken)"
    }
    private fun qualityLabelSiSdr(s: Double): String = when {
        s.isInfinite() && s > 0 -> "(identical)"
        s >= 15.0 -> "(transparent)"
        s >= 10.0 -> "(good)"
        s >=  5.0 -> "(recognizable)"
        s >=  0.0 -> "(degraded)"
        else      -> "(broken)"
    }
    /** Lower LSD is better. Thresholds chosen from typical neural-codec evaluation literature. */
    private fun qualityLabelLsd(d: Double): String = when {
        d.isNaN()         -> "(n/a)"
        d <  3.0          -> "(transparent)"
        d <  6.0          -> "(good — typical for neural codecs)"
        d < 10.0          -> "(noticeable artifacts)"
        else              -> "(spectral mismatch)"
    }

    private fun logCrossSourceRoundTrip() {
        if (lastRunRoundTrips.size < 2) return
        val labels = lastRunRoundTrips.keys.toList()
        val display = disambiguateLabels(labels, maxLen = 18)
        val sb = StringBuilder()
        sb.append("╭─ Cross-source WavTokenizer round-trip ────────\n")
        sb.append(String.format(java.util.Locale.US,
            "│ %-18s %-8s %-8s %-9s %-8s %-7s\n",
            "label", "LSD dB", "PSNR", "SI-SDR", "tok/sec", "uniq%"))
        sb.append("│ (LSD is the phase-invariant metric — primary; PSNR/SI-SDR informational)\n")
        sb.append("├─────────────────────────────────────────────────\n")
        for ((idx, entry) in lastRunRoundTrips.entries.withIndex()) {
            val r = entry.value
            val lsdStr = if (r.logSpectralDistanceDb.isNaN()) "n/a"
                         else "%.2f".format(java.util.Locale.US, r.logSpectralDistanceDb)
            sb.append(String.format(java.util.Locale.US,
                "│ %-18s %-8s %-8.1f %-9.1f %-8.1f %-7.2f\n",
                display[idx], lsdStr, r.psnrDb, r.siSdrDb, r.tokensPerSecond, r.tokenDiversity * 100))
        }
        // Outlier flags — LSD-first.
        val lsds = lastRunRoundTrips.values
            .map { it.logSpectralDistanceDb }
            .filter { !it.isNaN() && it.isFinite() }
        if (lsds.size >= 2) {
            val spread = lsds.max() - lsds.min()
            if (spread > 3.0) sb.append(String.format(java.util.Locale.US,
                "│ ⚠ LSD spread %.1f dB — codec output quality varies by source\n", spread))
        }
        val divs = lastRunRoundTrips.values.map { it.tokenDiversity }
        if (divs.size >= 2 && (divs.max() - divs.min()) > 0.05) {
            sb.append("│ ⚠ Token-diversity spread > 5 pp — some source is collapsing the codebook (likely OOD/narrowband)\n")
        }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    private fun logCrossSourceSummary() {
        if (lastRunStats.size < 2) return
        val labels = lastRunStats.keys.toList()
        val display = disambiguateLabels(labels, maxLen = 18)
        val sb = StringBuilder()
        sb.append("╭─ Cross-source comparison ─────────────────────\n")
        sb.append(String.format("│ %-18s %-10s %-10s %-6s %-6s %-22s\n",
            "label", "peak dBFS", "rms dBFS", "bits", "DC", "bandwidth"))
        sb.append("├─────────────────────────────────────────────────\n")
        for ((idx, entry) in lastRunStats.entries.withIndex()) {
            val s = entry.value
            sb.append(String.format("│ %-18s %-10.1f %-10.1f %-6d %-6.0f %-22s\n",
                display[idx], s.peakDbFs, s.rmsDbFs, s.effectiveBitsUsed, s.dcOffset, s.bandwidthHint.take(22)))
        }
        // Highlight outliers
        val rmsValues = lastRunStats.values.map { it.rmsDbFs }.filter { it.isFinite() }
        if (rmsValues.size >= 2) {
            val spread = (rmsValues.max() - rmsValues.min())
            if (spread > 6.0) sb.append(String.format("│ ⚠ RMS spread across sources: %.1f dB — gain mismatch\n", spread))
        }
        val bws = lastRunStats.values.map { it.bandwidthHint }.toSet()
        if (bws.size > 1) sb.append("│ ⚠ Mixed bandwidth across sources — AI will see inconsistent spectrum\n")
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())

        logCrossSourceSpectrum()
    }

    private fun logCrossSourceSpectrum() {
        if (lastRunStats.size < 2) return
        val labels = lastRunStats.keys.toList()
        val display = disambiguateLabels(labels, maxLen = 10)
        val sb = StringBuilder()
        sb.append("╭─ Cross-source spectral energy (% per band) ───\n")
        sb.append(String.format(java.util.Locale.US, "│ %-10s", "band"))
        for (d in display) sb.append(String.format(java.util.Locale.US, " %10s", d))
        sb.append('\n')
        sb.append("├─────────────────────────────────────────────────\n")
        for (i in BAND_LABELS.indices) {
            sb.append(String.format(java.util.Locale.US, "│ %-10s", BAND_LABELS[i]))
            for (l in labels) {
                val v = lastRunStats[l]?.subBandEnergyPct?.getOrNull(i) ?: 0.0
                sb.append(String.format(java.util.Locale.US, " %9.1f%%", v))
            }
            sb.append('\n')
        }
        // Flag big per-band divergence (max - min > 15 pp)
        val warnings = mutableListOf<String>()
        for (i in BAND_LABELS.indices) {
            val values = labels.mapNotNull { lastRunStats[it]?.subBandEnergyPct?.getOrNull(i) }
            if (values.size >= 2) {
                val spread = values.max() - values.min()
                if (spread > 15.0) warnings += "${BAND_LABELS[i]} differs by ${"%.1f".format(spread)} pp"
            }
        }
        if (warnings.isNotEmpty()) {
            sb.append("│ ⚠ Spectral divergence: ").append(warnings.joinToString("; ")).append('\n')
        }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    // Allow callers to inspect an audio file without feeding it.
    fun inspect(source: Source): Pair<AudioInfo, AudioStats> {
        val (info, pcm) = loadAndNormalize(source)
        logAudioInfo(info)
        val stats = computeStats(pcm, source)
        logAudioStats(source.label, stats)
        return info to stats
    }

    // Convenience: feed a list of file paths without building [Source] manually.
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

    @Suppress("unused")
    private fun unusedMaxKeepImportsAlive() {
        // keep `max` / `Scopes` imports stable in case future variants need them
        max(0, 0); Scopes
    }
}

