package com.commcrete.stardust.audio.v2.dsp

import com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor
import com.commcrete.stardust.util.audio.AGCFilter
import com.commcrete.stardust.util.audio.AudioDsp
import com.commcrete.stardust.util.audio.DynamicsProcessingFilter
import com.commcrete.stardust.util.audio.HighPassFilter
import com.commcrete.stardust.util.audio.LowPassFilter
import com.commcrete.stardust.util.audio.NotchFilter
import timber.log.Timber
import kotlin.math.tanh

/**
 * **Per-session** DSP processor — replaces the `object` singleton
 * [com.commcrete.stardust.util.audio.PttAudioProcessor].
 *
 * # Key differences from v1
 *
 *  - **One instance per [com.commcrete.stardust.audio.v2.session.RecordingSession].**
 *    No global state, no `reset()` ceremony, no `synchronized(this)`
 *    cache-invalidation block, no `currentFilterRate` /
 *    `currentFilterKey` dedupe — those existed only because the v1
 *    singleton had to be re-tuned every time a different
 *    `(rate × profile)` pair walked through. Per-session instances
 *    see exactly one pair for their entire lifetime, so the filter
 *    chain is built **once** in `init { }` and never rebuilt.
 *  - **Add a `MakeupGainConfig` stage at the end** of the DSP chain.
 *    Replaces the v1 `filterAiGain` + `filterAiGainSoftSat` flags
 *    (which were set only for AI) with a proper sealed-class config
 *    that every codec can pick from — hard-clip (CODEC2 legacy) or
 *    soft-clip-tanh (AI legacy).
 *  - **No native pool.** Per decision (a) we accept the per-session
 *    [RnNoiseProcessor.init] cost. If profiling shows it dominates
 *    first-chunk latency, slot a pool in via `RnNoisePool.acquire`
 *    inside `init { }` without changing the public API.
 *  - **No `applyFilters` bypass switch on every call** — if the
 *    caller wants to skip DSP they construct a bypass profile via
 *    [AiRecorderProfileV2.bypass]. Avoids three boolean flags
 *    branching in the hot path.
 *
 * # DSP chain
 *
 * ```
 * raw PCM @ nativeRate
 *   ─► HPF              (sub-voice rumble)
 *   ─► Notch            (mains-hum harmonics)
 *   ─► LPF              (band limit + anti-alias for resample)
 *   ─► RNNoise          (ML denoise + optional wet/dry blend + floor)
 *   ─► DynamicsProcessor (multiband compression)
 *   ─► AGC              (level normalisation)
 *   ─► MakeupGain       (HardClip | SoftClip per profile)
 *   ─► resamplePolyphase   → targetSampleRateHz
 * ```
 *
 * # Thread safety
 *
 * The chain holds per-stream filter state (biquad memory, RNNoise
 * frame history). Designed to be driven from a single audio thread
 * per session — the encode loop inside
 * [com.commcrete.stardust.audio.v2.session.RecordingSession]. Concurrent
 * [process] calls against the same instance are NOT supported.
 *
 * # Lifecycle
 *
 *  - Construct on session start.
 *  - Call [process] per captured chunk.
 *  - Call [close] on session finalize to release native RNNoise.
 */
class PttAudioProcessorV2(
    private val nativeSampleRateHz: Int,
    private val targetSampleRateHz: Int,
    private val profile: AiRecorderProfileV2,
    /**
     * Diagnostic tag for `Timber` logs — pass `"AI"` / `"CODEC2"` so
     * a multi-session run's logs are filterable per flow.
     */
    private val flowKey: String = "default",
) : AutoCloseable {

    // ── Filter chain instances ────────────────────────────────────────
    // All built in init { } below; held as nullable refs (no chain ⇒
    // bypass that stage). RNNoise is the only one with native state.

    private var hpf: HighPassFilter? = null
    private var notchFilter: NotchFilter? = null
    private var lpf: LowPassFilter? = null
    private var rnNoiseProcessor: RnNoiseProcessor? = null
    private var dynamicsFilter: DynamicsProcessingFilter? = null
    private var agcFilter: AGCFilter? = null

    /** Cached RNNoise per-frame RMS floor — read in the hot path. */
    private val rnNoiseAttenFloor: Float

    init {
        val base = profile.base
        // Resolve each stage exactly as v1 does — null AND `enabled = false`
        // both bypass the stage. This is the same `takeIf { it.enabled }`
        // pattern as PttAudioProcessor.ensureFiltersBuilt so a v1 profile
        // wrapped in a v2 envelope behaves identically pre-makeup-gain.
        val hp = base.highPass?.takeIf { it.enabled }
        val nt = base.notch?.takeIf { it.enabled }
        val lp = base.lowPass?.takeIf { it.enabled }
        val rn = base.rnNoise?.takeIf { it.enabled }
        val dp = base.dynamics?.takeIf { it.enabled }
        val ag = base.agc?.takeIf { it.enabled }

        hpf = hp?.let {
            HighPassFilter(
                sampleRateHz = nativeSampleRateHz,
                cutoffHz = it.cutoffHz,
                rollOffDbPerOctave = it.rollOffDbPerOctave,
            )
        }
        notchFilter = nt?.let { NotchFilter(nativeSampleRateHz, it.resolveBands()) }
        lpf = lp?.let {
            LowPassFilter(
                sampleRateHz = nativeSampleRateHz,
                cutoffHz = it.cutoffHz,
                rollOffDbPerOctave = it.rollOffDbPerOctave,
            )
        }
        rnNoiseProcessor = rn?.let { RnNoiseProcessor().apply { init(nativeSampleRateHz) } }
        rnNoiseAttenFloor = rn?.attenuationFloorLin ?: 0f
        dynamicsFilter = dp?.let {
            DynamicsProcessingFilter(sampleRateHz = nativeSampleRateHz, config = it)
        }
        agcFilter = ag?.let {
            AGCFilter(
                sampleRateHz = nativeSampleRateHz,
                targetLevel = it.targetLevel,
                attackMs = it.attackMs,
                releaseMs = it.releaseMs,
                maxGainDb = it.maxGainDb,
                minGainDb = it.minGainDb,
                noiseGateLevel = it.noiseGateLevel,
            )
        }

        Timber.tag(TAG).d(
            "[$flowKey] built DSP chain rate=$nativeSampleRateHz→$targetSampleRateHz " +
                "profile='${profile.preset}' " +
                "hpf=${hpf != null} " +
                "notch=${notchFilter != null} lpf=${lpf != null} " +
                "rnnoise=${rnNoiseProcessor != null} dyn=${dynamicsFilter != null} " +
                "agc=${agcFilter != null} " +
                "makeup=${profile.makeupGain?.let { it::class.simpleName } ?: "off"}"
        )
    }

    /**
     * Run [pcm] through the DSP chain in-place, then resample to
     * [targetSampleRateHz] (no-op if rates match).
     *
     * @param chunkIndex zero-based index used for diagnostic logging
     *                   only; consumers can pass `0` if they don't
     *                   care.
     * @return processed PCM at [targetSampleRateHz]. May be the input
     *         array if no profile stage and no resample applied, but
     *         callers should treat the result as a fresh array.
     */
    fun process(pcm: ShortArray, chunkIndex: Int = 0): ShortArray {
        if (pcm.isEmpty()) return pcm
        val working = if (anyFilterActive()) {
            val copy = pcm.copyOf()
            applyFilterChain(copy)
            copy
        } else {
            pcm
        }
        return if (nativeSampleRateHz != targetSampleRateHz) {
            AudioDsp.resamplePolyphase(working, nativeSampleRateHz, targetSampleRateHz)
        } else {
            working
        }
    }

    override fun close() {
        runCatching { rnNoiseProcessor?.release() }
            .onFailure { Timber.tag(TAG).w(it, "[$flowKey] RnNoise release failed") }
        rnNoiseProcessor = null
        hpf = null
        notchFilter = null
        lpf = null
        dynamicsFilter = null
        agcFilter = null
    }

    // ── internals ────────────────────────────────────────────────────

    private fun anyFilterActive(): Boolean =
        hpf != null || notchFilter != null || lpf != null ||
            rnNoiseProcessor != null ||
            dynamicsFilter != null || agcFilter != null ||
            (profile.makeupGain?.enabled == true)

    private fun applyFilterChain(chunk: ShortArray) {
        // Order matches v1 PttAudioProcessor.applyFilterChain exactly,
        // EXCEPT the post-AGC make-up gain is now a sealed-class stage
        // that any codec can pick.
        hpf?.processInPlace(chunk)
        notchFilter?.processInPlace(chunk)
        lpf?.processInPlace(chunk)
        rnNoiseProcessor?.let { proc ->
            val needsFloor = rnNoiseAttenFloor > 0f
            val dry = if (needsFloor) chunk.copyOf() else null
            proc.process(chunk, chunk.size)
            if (dry != null) softenRnNoise(chunk, dry, rnNoiseAttenFloor)
        }
        dynamicsFilter?.processInPlace(chunk)
        agcFilter?.processInPlace(chunk)
        applyMakeupGain(chunk, profile.makeupGain)
    }

    /**
     * Per-frame RMS floor for RNNoise output. Same algorithm as
     * [com.commcrete.stardust.util.audio.PttAudioProcessor.softenRnNoise].
     * See that method's KDoc for the rationale (replaces the old per-sample
     * magnitude clamp + wet/dry blend which caused spectral artifacts and
     * comb-filtering).
     */
    private fun softenRnNoise(wet: ShortArray, dry: ShortArray, floor: Float) {
        if (floor <= 0f) return
        val n = minOf(wet.size, dry.size)
        if (n == 0) return

        val frameSize = 480
        var off = 0
        while (off < n) {
            val end = minOf(off + frameSize, n)
            val len = end - off

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

    private fun applyMakeupGain(chunk: ShortArray, config: MakeupGainConfig?) {
        if (config == null || !config.enabled) return
        when (config) {
            is MakeupGainConfig.HardClip -> applyHardClip(chunk, config.gainLinear)
            is MakeupGainConfig.SoftClip -> applySoftClip(chunk, config.gainLinear, config.knee)
        }
    }

    /** Multiply + saturate. Maps the legacy CODEC2 `targetGain` + `coerceIn` path. */
    private fun applyHardClip(chunk: ShortArray, gain: Float) {
        if (gain == 1f) return
        for (i in chunk.indices) {
            val v = (chunk[i] * gain)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .toInt()
                .toShort()
            chunk[i] = v
        }
    }

    /**
     * Multiply + tanh soft-clip. Bit-for-bit equivalent of the legacy
     * AI [com.commcrete.stardust.ai.codec.AudioRecorderAI.processSamples]
     * (which is the version with the docstring justifying the math).
     */
    private fun applySoftClip(chunk: ShortArray, gain: Float, knee: Float) {
        if (gain == 1f && knee >= 1f) return
        val fs = Short.MAX_VALUE.toFloat()
        for (i in chunk.indices) {
            val x = chunk[i] * gain
            val n = x / fs
            val absN = if (n < 0f) -n else n
            val shaped = if (absN <= knee) {
                n
            } else {
                val sign = if (n < 0f) -1f else 1f
                val over = (absN - knee) / (1f - knee)
                sign * (knee + (1f - knee) * tanh(over))
            }
            val iv = (shaped * fs).toInt()
            chunk[i] = when {
                iv > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                iv < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> iv.toShort()
            }
        }
    }

    companion object {
        private const val TAG = "PttAudioProcessorV2"
    }
}

