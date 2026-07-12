package com.commcrete.stardust.util.audio

import android.util.Log
import com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor
import com.commcrete.stardust.util.audio.filters.AGCFilter
import com.commcrete.stardust.util.audio.filters.AdaptiveNotchDetector
import com.commcrete.stardust.util.audio.filters.DynamicsProcessingFilter
import com.commcrete.stardust.util.audio.filters.HighPassFilter
import com.commcrete.stardust.util.audio.filters.LowPassFilter
import com.commcrete.stardust.util.audio.filters.NotchFilter
import com.commcrete.stardust.util.audio.filters.RecorderFiltersProfile
import com.commcrete.stardust.util.audio.filters.configs.AGCConfig
import com.commcrete.stardust.util.audio.filters.configs.DynamicsConfig
import com.commcrete.stardust.util.audio.filters.configs.HighPassConfig
import com.commcrete.stardust.util.audio.filters.configs.LowPassConfig
import com.commcrete.stardust.util.audio.filters.configs.NotchConfig
import com.commcrete.stardust.util.audio.filters.configs.RnNoiseConfig

/**
 * Single source of truth for **pre-encode** PCM processing on the PTT path.
 *
 * Owns the full DSP filter chain, the resample-to-encoder-rate step, AND
 * the per-device-type DSP profile registry so `PttSendManager` (encode
 * pipeline) and `RecorderUtils` (recording lifecycle / device routing)
 * can stay focused on their own concerns.
 *
 * Pipeline (per chunk):
 * ```
 * raw PCM @ nativeRate
 *   ─► HPF              (sub-voice rumble: HVAC, traffic, thump)
 *   ─► Notch            (mains-hum harmonics)
 *   ─► RNNoise          (ML denoise + optional wet/dry blend & floor)
 *   ─► DynamicsProcessor (multiband compression / voice focus)
 *   ─► AGC              (level normalisation)
 *   ─► AI-gain          (post-AGC make-up, optional soft saturation)
 *   ─► LPF              (band limit + anti-alias for the upcoming resample)
 *   ─► resamplePolyphase → targetRate (24 kHz for AI, 8 kHz for CODEC2)
 * ```
 *
 * Stateful: each filter holds per-stream history (biquad memory, RNNoise
 * denoise state, etc.). The chain is built lazily on the first call, and
 * **rebuilt** when [process] is invoked with a different sample rate or
 * profile. Call [reset] between sessions (e.g. from `PttSendManager.restart`)
 * to release native RNNoise resources and force a fresh build on the next
 * chunk.
 *
 * Thread safety: `ensureFiltersBuilt` is `synchronized`; `process` itself
 * is intended to run from a single audio thread per session (the
 * codec-mutex serialised session job in `PttSendManager`). Concurrent
 * processing of two chunks against the same processor instance is **not**
 * supported.
 */
object PttAudioProcessor {

    private const val TAG = "PttAudioProcessor"
    private const val TAG_LATENCY = "PttAudioProcessor_Latency"

    /** Sample rate the AI tokenizer encoder consumes. */
    const val AI_TARGET_SAMPLE_RATE: Int = 24_000

    /** Sample rate the Codec2 encoder consumes. */
    const val CODEC2_TARGET_SAMPLE_RATE: Int = 8_000

    /** Default flow key for diagnostic logging when caller doesn't tag. */
    private const val DEFAULT_FLOW_KEY = "default"

    // ── Filter chain instances ────────────────────────────────────────────

    private var hpf: HighPassFilter? = null
    private var notchFilter: NotchFilter? = null
    private var adaptiveNotchDetector: AdaptiveNotchDetector? = null
    /** Static bands resolved from the NotchConfig (kept for merging with adaptive). */
    private var staticNotchBands: List<NotchFilter.Band> = emptyList()
    private var rnNoiseProcessor: RnNoiseProcessor? = null
    private var dynamicsFilter: DynamicsProcessingFilter? = null
    private var agcFilter: AGCFilter? = null
    private var lpf: LowPassFilter? = null

    /** Carries resample history across chunk boundaries — see [StreamingPolyphaseResampler]. */
    private var resampler: StreamingPolyphaseResampler? = null
    private var resamplerSrcRate: Int = -1
    private var resamplerDstRate: Int = -1

    // ── Tuning knobs derived from the current profile ─────────────────────

    /** RNNoise per-frame RMS floor, linear (0 = disabled). */
    private var rnNoiseAttenFloor: Float = 0f

    /** Post-AGC make-up multiplier (1 = passthrough). */
    private var filterAiGain: Float = 1f
    private var filterAiGainSoftSat: Boolean = false

    // ── Cache invalidation ────────────────────────────────────────────────

    /** Sample rate the currently-built chain runs at. `-1` = no chain yet. */
    @Volatile private var currentFilterRate: Int = -1


    @Volatile private var filtersBuilt: Boolean = false


    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Run [pcmArray] through the full DSP chain  and resample to [targetRate] when [nativeRate] differs.
     *
     * **Filter-skip semantics — DSP is bypassed entirely (only the
     * resample step runs) when ANY of the following holds:**
     *  - [profile] is `null`.
     *  - [profile]`.isActive` is `false`.
     *
     * In addition, **within** an active profile each individual filter
     * stage is gated by both its presence (`profile.X != null`) AND its
     * `enabled` flag — a stage that is `null` OR `enabled = false` is
     * NOT built, NOT applied, and NOT replaced by any default. The
     * profile is the single source of truth for what runs.
     *
     * @param pcmArray        raw mono 16-bit PCM at [nativeRate].
     * @param nativeRate      sample rate of [pcmArray]; the chain is built
     *                        / rebuilt for this rate on the first chunk
     *                        and whenever it changes.
     * @param targetRate      encoder-side rate. Use [AI_TARGET_SAMPLE_RATE]
     *                        or [CODEC2_TARGET_SAMPLE_RATE].
     * @param profile         per-device DSP configuration. `null` or a
     *                        profile with `isActive = false` runs no
     *                        filters even when [applyFilters] is `true`
     *                        — see "Filter-skip semantics" above.
     * @param flowKey         identifier for diagnostic dedupe; pass
     *                        `"AI"` / `"CODEC2"` so the log lines per flow
     *                        are independent.
     * @param chunkIndex      zero-based index of this chunk in the current
     *                        emission. Chunks 0/1 always log; subsequent
     *                        chunks only log when something changes.
     * @param chunkDurationMs optional declared duration of [pcmArray] in
     *                        ms; when set, an "expected sample count"
     *                        diagnostic is included so size mismatches
     *                        surface clearly.
     * @param isFinal         `true` for the last chunk of a recording (e.g.
     *                        `AudioRecorderAI.onPartialFinalChunk`, or the
     *                        drained tail after `AudioRecorderCodec2`'s
     *                        recording loop exits). Flushes the resampler's
     *                        held-back tail into the returned array instead
     *                        of carrying it forward as history that would
     *                        never be consumed. Defaults to `false` for
     *                        regular mid-stream chunks.
     * @return the processed PCM ready for the encoder. May be the same
     *         array instance as [pcmArray] when [applyFilters] is `false`
     *         and no resample is needed; otherwise a fresh array.
     */
    fun process(
        pcmArray: ShortArray,
        nativeRate: Int,
        targetRate: Int,
        enableNoiseCancellation: Boolean,
        isFinal: Boolean = false
    ): ShortArray {
        val startMs = System.currentTimeMillis()

        val filtered: ShortArray = if (enableNoiseCancellation) {
            val mutable = pcmArray.copyOf()
            applyFilterChain(mutable, nativeRate, RecorderFiltersProfile(
                rnNoise = RnNoiseConfig(
                    enabled = true,
                    maxAttenuationDb = -20f
                )
            )
            )
            mutable
        } else {
            pcmArray
        }
        val filterChainMs = System.currentTimeMillis() - startMs

        if (nativeRate == targetRate) {
            Log.d(TAG_LATENCY, "process(): ${pcmArray.size} samples, filterChain ${filterChainMs}ms, no resample needed")
            return filtered
        }

        val resampleStartMs = System.currentTimeMillis()
        val res = ensureResampler(nativeRate, targetRate)
        val head = res.process(filtered)
        val result = if (!isFinal) head else {
            val tail = res.flush()
            if (tail.isEmpty()) head else head + tail
        }
        val resampleMs = System.currentTimeMillis() - resampleStartMs
        val totalMs = System.currentTimeMillis() - startMs
        Log.d(
            TAG_LATENCY,
            "process(): ${pcmArray.size} samples $nativeRate->$targetRate, total ${totalMs}ms " +
                "(filterChain ${filterChainMs}ms incl. RNNoise, resample ${resampleMs}ms)"
        )
        return result
    }

    /**
     * Fetch the resampler for the current (nativeRate, targetRate) pair, rebuilding it —
     * and discarding any carried history — when either rate changes. Synchronized on `this`,
     * same lock as [reset]/[ensureFiltersBuilt], so a concurrent session reset can't null this
     * out mid-chunk.
     */
    private fun ensureResampler(srcRate: Int, dstRate: Int): StreamingPolyphaseResampler = synchronized(this) {
        resampler?.takeIf { resamplerSrcRate == srcRate && resamplerDstRate == dstRate }?.let { return@synchronized it }
        resamplerSrcRate = srcRate
        resamplerDstRate = dstRate
        StreamingPolyphaseResampler(srcRate, dstRate).also { resampler = it }
    }

    /**
     * Tear down the filter chain so the next [process] call rebuilds it
     * from scratch. Releases native RNNoise resources and clears the
     * dedupe-logging cache. Safe to call multiple times. Call from
     * `PttSendManager.restart` so a new session starts with fresh
     * per-stream state (no biquad ringing across recordings, no stale
     * RNNoise frame history).
     *
     * Synchronized on `this` — same lock as [applyFilterChain] and
     * [ensureFiltersBuilt] — so a concurrent [process] call on the audio
     * thread finishes its current chunk before the native state is torn
     * down.
     */
    fun reset() = synchronized(this) {
        runCatching { rnNoiseProcessor?.release() }
            .onFailure { Log.w(TAG, "RnNoise release failed", it) }
        rnNoiseProcessor = null
        hpf = null
        notchFilter = null
        adaptiveNotchDetector = null
        staticNotchBands = emptyList()
        dynamicsFilter = null
        agcFilter = null
        lpf = null
        rnNoiseAttenFloor = 0f
        filterAiGain = 1f
        filterAiGainSoftSat = false
        currentFilterRate = -1
        filtersBuilt = false
        resampler = null
        resamplerSrcRate = -1
        resamplerDstRate = -1
    }

    // ──────────────────────────────────────────────────────────────────────
    // Filter chain
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Optional debug hook: called after each filter stage with a snapshot
     * of the chunk at that point. Set by the test feeder to save
     * per-filter WAV artifacts; `null` during live recording (zero
     * overhead — the snapshot copy is skipped entirely).
     *
     * Callback signature: `(sampleRate: Int, stepIndex: Int, filterName: String, chunk: ShortArray) -> Unit`
     */
    @Volatile
    var onFilterStepDebug: ((Int, Int, String, ShortArray) -> Unit)? = null

    /**
     * Run the full DSP chain on [chunk] in place. Synchronized on `this`
     * so [reset] (called from the restart thread) cannot destroy native
     * filter state while the audio thread is mid-chunk.
     */
    private fun applyFilterChain(chunk: ShortArray, sampleRate: Int, profile: RecorderFiltersProfile) = synchronized(this) {
        ensureFiltersBuilt(sampleRate, profile)
        val dbg = onFilterStepDebug
        var step = 0

        fun debugSnapshot(name: String) {
            dbg?.invoke(sampleRate, step++, name, chunk.copyOf())
        }

        // ── Signal path order ───────────────────────────────────────
        // Each stage is positioned so it sees the cleanest possible
        // input and doesn't interfere with downstream stages.

        // 1. HPF — remove sub-voice rumble before any other filter sees
        //    it. Prevents: notch biquad ringing on thumps, spectral sub
        //    learning rumble as noise, RNNoise wasting spectral budget
        //    on sub-voice content.
        hpf?.let {
            it.processInPlace(chunk)
            debugSnapshot("hpf")
        }
        // 2. Notch — remove tonal interference before RNNoise / AGC.
        notchFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("notch")
        }
        adaptiveNotchDetector?.let { detector ->
            val newBands = detector.detectAndTrack(chunk)
            if (newBands != null) {
                val merged = staticNotchBands + newBands
                notchFilter?.configure(bands = merged)
            }
        }
        // 3. RNNoise — ML broadband denoise.
        rnNoiseProcessor?.let { proc ->
            val needsFloor = rnNoiseAttenFloor > 0f
            val dry = if (needsFloor) chunk.copyOf() else null
            proc.process(chunk, chunk.size)
            if (dry != null) softenRnNoise(chunk, dry, rnNoiseAttenFloor)
            debugSnapshot("rnnoise")
        }
        // 6. AGC — normalize level AFTER all noise reduction so it
        //    boosts clean signal, not noise.
        agcFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("agc")
        }
        // 7. Dynamics — multiband compression in a predictable level
        //    range (after AGC normalized).
        dynamicsFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("dynamics")
        }
        // 8. AI-gain — final makeup gain / soft saturation.
        if (filterAiGain != 1f) {
            if (filterAiGainSoftSat) AudioDsp.applyAiGainSoftSatInPlace(chunk, filterAiGain)
            else AudioDsp.applyAiGainInPlace(chunk, filterAiGain)
            debugSnapshot("ai_gain")
        }
        // 9. LPF — anti-alias, last before resample.
        lpf?.let {
            it.processInPlace(chunk)
            debugSnapshot("lpf")
        }
    }

    private fun ensureFiltersBuilt(sampleRate: Int, profile: RecorderFiltersProfile) {
        if (filtersBuilt && currentFilterRate == sampleRate) return
        synchronized(this) {
            if (filtersBuilt && currentFilterRate == sampleRate) return
            if (filtersBuilt) {
                releaseFiltersInternal()
            }
            currentFilterRate = sampleRate

            val rn: RnNoiseConfig? = profile.rnNoise?.takeIf { it.enabled }
            val dp: DynamicsConfig? = profile.dynamics?.takeIf { it.enabled }
            val lp: LowPassConfig? = profile.lowPass?.takeIf { it.enabled }
            val hp: HighPassConfig? = profile.highPass?.takeIf { it.enabled }
            val notch: NotchConfig? = profile.notch?.takeIf { it.enabled }
            val agc: AGCConfig? = profile.agc?.takeIf { it.enabled }

            hpf = hp?.let {
                HighPassFilter(
                    sampleRateHz = sampleRate,
                    cutoffHz = it.cutoffHz,
                    rollOffDbPerOctave = it.rollOffDbPerOctave,
                )
            }
            val resolvedBands = notch?.resolveBands() ?: emptyList()
            staticNotchBands = resolvedBands
            notchFilter = notch?.let { NotchFilter(sampleRate, resolvedBands) }
            adaptiveNotchDetector = notch?.adaptive?.let {
                AdaptiveNotchDetector(sampleRate, it)
            }
            rnNoiseProcessor = rn?.let { RnNoiseProcessor().apply { init(sampleRate) } }
            rnNoiseAttenFloor = rn?.attenuationFloorLin ?: 0f
            dynamicsFilter = dp?.let {
                DynamicsProcessingFilter(
                    sampleRateHz = sampleRate,
                    config = it
                )
            }
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

    /**
     * Free filter instances (and native RNNoise state) without touching
     * the dedupe-logging cache. Used by the rebuild path inside
     * [ensureFiltersBuilt] where we *want* the next chunk's signature line
     * to log as a transition rather than repeat from scratch.
     */
    private fun releaseFiltersInternal() {
        runCatching { rnNoiseProcessor?.release() }
            .onFailure { Log.w(TAG, "RnNoise release failed", it) }
        rnNoiseProcessor = null
        hpf = null
        notchFilter = null
        adaptiveNotchDetector = null
        staticNotchBands = emptyList()
        dynamicsFilter = null
        agcFilter = null
        lpf = null
        rnNoiseAttenFloor = 0f
        filterAiGain = 1f
        filterAiGainSoftSat = false
        currentFilterRate = -1
        filtersBuilt = false
    }

    /**
     * Per-frame RMS floor for RNNoise output.
     *
     * Replaces the old per-sample magnitude clamp + wet/dry blend which had
     * two bugs:
     *
     *  1. **Per-sample floor** compared individual sample magnitudes between
     *     wet and dry. A sample's magnitude says nothing about voice vs.
     *     noise — the floor re-introduced noise with spectral artifacts.
     *
     *  2. **Wet/dry blend** (`mix < 1`) combined two signals with different
     *     phase spectra (RNNoise reshapes phase). The sum produced
     *     frequency-dependent cancellation = comb-filtering = echo.
     *
     * The fix: no blend (always `mix = 1`), and a **per-frame RMS floor**
     * that uniformly scales a frame up when RNNoise suppresses it too much.
     * This preserves RNNoise's spectral decisions (which frequencies to
     * keep/cut) while preventing overall energy collapse (hollowness).
     *
     * @param wet   RNNoise output (modified in place).
     * @param dry   copy of the input saved before RNNoise ran.
     * @param floor linear RMS floor ratio in `(0, 1]`. If the wet frame's
     *              RMS falls below `dry_rms * floor`, the entire frame is
     *              scaled up so `wet_rms == dry_rms * floor`. `0` disables.
     */
    private fun softenRnNoise(wet: ShortArray, dry: ShortArray, floor: Float) {
        if (floor <= 0f) return
        val n = minOf(wet.size, dry.size)
        if (n == 0) return

        // Process in 480-sample frames (RNNoise's native frame size) so the
        // floor decision aligns with the granularity RNNoise operates at.
        val frameSize = 480
        var off = 0
        while (off < n) {
            val end = minOf(off + frameSize, n)
            val len = end - off

            // Compute RMS of wet and dry for this frame.
            var wetSumSq = 0.0
            var drySumSq = 0.0
            for (i in off until end) {
                val w = wet[i].toDouble()
                val d = dry[i].toDouble()
                wetSumSq += w * w
                drySumSq += d * d
            }
            val wetRms = kotlin.math.sqrt(wetSumSq / len)
            val dryRms = kotlin.math.sqrt(drySumSq / len)

            // If wet RMS is below the floor threshold, scale up uniformly.
            val minRms = dryRms * floor
            if (wetRms > 0.0 && wetRms < minRms) {
                val gain = (minRms / wetRms).toFloat()
                for (i in off until end) {
                    val scaled = (wet[i] * gain).toInt()
                    wet[i] = scaled.coerceIn(
                        Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                    ).toShort()
                }
            }
            off = end
        }
    }

}

