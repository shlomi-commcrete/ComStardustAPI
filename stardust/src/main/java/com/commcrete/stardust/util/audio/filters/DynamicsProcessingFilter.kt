package com.commcrete.stardust.util.audio.filters

import com.commcrete.stardust.util.audio.filters.configs.DynamicsConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Software multiband compressor + limiter mirroring the HAL
 * `android.media.audiofx.DynamicsProcessing` chain that
 * `AudioRecorderAI.tryAttachDynamicsProcessing` attaches in production.
 *
 * Pipeline per sample:
 *
 * ```
 *  x ─[ × inputGain ]─┬─► LPF(b0) ────────────► band0 = LPF(b0)
 *                     │
 *                     ├─► LPF(b1) ─┬─► band1 = LPF(b1) − LPF(b0)
 *                     │             │
 *                     │             └────────► band2 = x − LPF(b1)
 *                     │
 *  Σ bands ─► limiter ─► output
 * ```
 *
 * Each band runs independently:
 *  - Static `preGain` (in dB → linear) on the input slice.
 *  - One-pole envelope follower on |x| (15 ms time constant).
 *  - **Compressor**: above [DynamicsConfig.Band.thresholdDb] the static gain
 *    curve approaches `1/ratio` slope; soft knee of [kneeWidthDb] interpolates
 *    smoothly between linear and compressed regions.
 *  - **Downward expander**: below [DynamicsConfig.Band.noiseGateDb] (in dB)
 *    the static gain follows `(envDb − gateDb) × expanderRatio` so quiet
 *    content gets attenuated (USB hiss gate).
 *  - Per-band attack / release smoothing applied to the gain delta.
 *  - Static `postGain` (in dB → linear) on the band's output.
 *
 * All three bands are summed (the one-pole crossover is *almost* unity-sum
 * — phase artefacts at the crossover frequencies are inaudible for speech
 * and the HAL exhibits the same behaviour).
 *
 * The summed signal then goes through a brick-wall limiter:
 *  - Same envelope-follower / compressor structure as the bands but with
 *    the configured limiter time constants and ratio (typically 20:1 at
 *    −1 dBFS) and a hard clip at full-scale.
 *
 * The filter keeps internal state across calls — a single instance must be
 * used for a single continuous stream. Call [reset] on stream restart.
 *
 * Implementation runs in float internally; input/output are int16 saturated
 * to `[Short.MIN_VALUE.Short.MAX_VALUE]`.
 *
 * Crossover topology:
 *  - Two cascaded one-pole low-pass filters at the upper edges of band0
 *    and band1 (≈ 6 dB/oct slopes, matching the simplest "linear-phase-ish"
 *    crossover used by mic-channel multiband compressors).
 *  - Linkwitz-Riley would be flatter-summing but introduces 4× more biquad
 *    state per sample for negligible audible gain on speech. If you need
 *    closer parity with the HAL's exact response, swap [updateLowPassState]
 *    for two cascaded one-poles per crossover (12 dB/oct).
 */
class DynamicsProcessingFilter(
    sampleRateHz: Int,
    config: DynamicsConfig,
) {

    /** Current sample rate in Hz. */
    var sampleRateHz: Int = sampleRateHz
        private set

    /** Active config. Use [configure] to swap at runtime. */
    var config: DynamicsConfig = config
        private set

    // ─── Resolved coefficients (rebuilt on every [configure]) ───────────

    private var inputGainLin: Float = 1f

    // Crossover one-pole alpha values: y[n] = y[n-1] + α (x − y[n-1]).
    private var alphaLowMid: Float = 0f
    private var alphaMidHigh: Float = 0f

    // Per-band processing
    private val bandStates = arrayOfNulls<BandState>(3)
    private lateinit var limiterState: LimiterState

    // ─── Crossover state ────────────────────────────────────────────────

    private var lpfLowMidState: Float = 0f
    private var lpfMidHighState: Float = 0f

    init {
        rebuild(this.config)
    }

    /** Replace config at runtime. Internal state is preserved. */
    fun configure(config: DynamicsConfig) {
        this.config = config
        rebuild(config)
    }

    /** Drop envelope / gain / crossover state. Call on stream restart. */
    fun reset() {
        lpfLowMidState = 0f
        lpfMidHighState = 0f
        for (s in bandStates) s?.reset()
        limiterState.reset()
    }

    // ─── Hot path ───────────────────────────────────────────────────────

    /** Process a block of int16 PCM samples in place. */
    fun processInPlace(pcm: ShortArray, length: Int = pcm.size) {
        val n = if (length > pcm.size) pcm.size else length
        if (n == 0) return
        val invFs = 1f / Short.MAX_VALUE.toFloat()
        val scale = Short.MAX_VALUE.toFloat()

        val a01 = alphaLowMid
        val a12 = alphaMidHigh
        val gIn = inputGainLin

        val s0 = bandStates[0]!!
        val s1 = bandStates[1]!!
        val s2 = bandStates[2]!!
        val sLim = limiterState

        for (i in 0 until n) {
            val xRaw = pcm[i] * invFs
            val x = xRaw * gIn

            // ── Crossover: split into 3 bands using cascaded one-pole LPFs ──
            // low      = LPF(highEdge0)              (0 .. b0)
            // midPlus  = LPF(highEdge1)              (0 .. b1)
            // mid      = midPlus − low               (b0 .. b1)
            // high     = x − midPlus                 (b1 .. Nyquist)
            lpfLowMidState += a01 * (x - lpfLowMidState)
            val low = lpfLowMidState
            lpfMidHighState += a12 * (x - lpfMidHighState)
            val midPlusLow = lpfMidHighState
            val mid = midPlusLow - low
            val high = x - midPlusLow

            // ── Per-band dynamics ──
            val out0 = processBand(low, s0)
            val out1 = processBand(mid, s1)
            val out2 = processBand(high, s2)

            // ── Sum + limiter ──
            val mixed = out0 + out1 + out2
            val limited = processLimiter(mixed, sLim)

            val iv = (limited * scale).roundToInt()
            pcm[i] = when {
                iv > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                iv < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> iv.toShort()
            }
        }
    }

    // ─── Per-band processing (compressor + downward expander) ───────────

    private fun processBand(input: Float, s: BandState): Float {
        // Apply pre-gain.
        val pre = input * s.preGainLin

        // Envelope follower (one-pole on |x|, RMS-ish).
        val absX = abs(pre)
        s.envelope += s.envCoeff * (absX - s.envelope)

        // Convert envelope to dBFS (clamped to avoid log of zero).
        val envClamped = max(s.envelope, ENV_FLOOR)
        val envDb = 20f * log10(envClamped)

        // Static gain curve in dB.
        val staticGainDb = computeStaticGainDb(envDb, s)

        // Smooth gain change with attack / release.
        val staticGainLin = dbToLinear(staticGainDb)
        val coeff = if (staticGainLin < s.smoothedGain) s.attackCoeff else s.releaseCoeff
        s.smoothedGain += coeff * (staticGainLin - s.smoothedGain)

        return pre * s.smoothedGain * s.postGainLin
    }

    /**
     * Returns the static gain (dB) the band would apply to a steady-state
     * sine of envelope [envDb]. Combines a downward expander below
     * [BandState.gateDb] and a soft-knee compressor above
     * [BandState.thresholdDb]. Between the two it's unity (0 dB).
     */
    private fun computeStaticGainDb(envDb: Float, s: BandState): Float {
        // Downward expander below noise gate threshold.
        if (s.expanderRatio > 1f && envDb < s.gateDb) {
            // gainDb = (envDb − gateDb) × (expanderRatio − 1)
            // → at envDb = gateDb gain = 0, going more negative as envDb drops.
            return (envDb - s.gateDb) * (s.expanderRatio - 1f)
        }

        if (s.ratio <= 1f) return 0f // no compression configured

        val knee = s.kneeWidthDb
        val over = envDb - s.thresholdDb

        // Below the knee: linear (no gain change).
        if (over <= -knee * 0.5f) return 0f

        // Inverse compression slope (in dB/dB) above threshold.
        val slope = 1f - 1f / s.ratio

        // Above the knee: full compression.
        if (over >= knee * 0.5f) return -slope * over

        // Inside the knee: quadratic interpolation between linear and
        // compressed regions (RBJ / DAFX style).
        val x = over + knee * 0.5f
        return -slope * (x * x) / (2f * knee)
    }

    // ─── Brick-wall limiter (post-sum) ──────────────────────────────────

    private fun processLimiter(input: Float, s: LimiterState): Float {
        val absX = abs(input)
        s.envelope += s.envCoeff * (absX - s.envelope)

        val envClamped = max(s.envelope, ENV_FLOOR)
        val envDb = 20f * log10(envClamped)

        val staticGainDb = if (s.ratio <= 1f) 0f else {
            val over = envDb - s.thresholdDb
            if (over <= 0f) 0f else -(1f - 1f / s.ratio) * over
        }
        val staticGainLin = dbToLinear(staticGainDb)
        val coeff = if (staticGainLin < s.smoothedGain) s.attackCoeff else s.releaseCoeff
        s.smoothedGain += coeff * (staticGainLin - s.smoothedGain)

        val out = input * s.smoothedGain * s.postGainLin

        // Hard clip just below full-scale as the final safety net (matches
        // the HAL's brick-wall behaviour for transients faster than attack).
        return when {
            out > LIMIT -> LIMIT
            out < -LIMIT -> -LIMIT
            else -> out
        }
    }

    // ─── (Re)build coefficients from config ─────────────────────────────

    private fun rebuild(c: DynamicsConfig) {
        inputGainLin = dbToLinear(c.inputGainDb)

        val nyquist = (sampleRateHz / 2f).coerceAtLeast(1f)
        // Clamp band edges to (0, Nyquist) — matches HAL's silent-clamp behaviour.
        val edge0 = c.band0.highEdgeHz.coerceIn(1f, nyquist - 1f)
        val edge1 = c.band1.highEdgeHz.coerceIn(edge0 + 1f, nyquist - 1f)

        alphaLowMid = onePoleAlpha(edge0, sampleRateHz)
        alphaMidHigh = onePoleAlpha(edge1, sampleRateHz)

        bandStates[0] = BandState.fromConfig(c.band0, sampleRateHz, bandStates[0])
        bandStates[1] = BandState.fromConfig(c.band1, sampleRateHz, bandStates[1])
        bandStates[2] = BandState.fromConfig(c.band2, sampleRateHz, bandStates[2])

        limiterState = if (::limiterState.isInitialized) {
            limiterState.also { it.updateFrom(c.limiter, sampleRateHz) }
        } else {
            LimiterState.fromConfig(c.limiter, sampleRateHz)
        }
    }

    // ─── Per-band state container ───────────────────────────────────────

    private class BandState(
        var attackCoeff: Float,
        var releaseCoeff: Float,
        var envCoeff: Float,
        var ratio: Float,
        var thresholdDb: Float,
        var kneeWidthDb: Float,
        var gateDb: Float,
        var expanderRatio: Float,
        var preGainLin: Float,
        var postGainLin: Float,
    ) {
        var envelope: Float = 0f
        var smoothedGain: Float = 1f

        fun reset() {
            envelope = 0f
            smoothedGain = 1f
        }

        companion object {
            fun fromConfig(b: DynamicsConfig.Band, sr: Int, prev: BandState?): BandState {
                val s = prev ?: BandState(
                    attackCoeff = 0f, releaseCoeff = 0f, envCoeff = 0f,
                    ratio = 1f, thresholdDb = 0f, kneeWidthDb = 0f,
                    gateDb = -100f, expanderRatio = 1f,
                    preGainLin = 1f, postGainLin = 1f,
                )
                s.attackCoeff = timeCoeff(b.attackMs, sr)
                s.releaseCoeff = timeCoeff(b.releaseMs, sr)
                s.envCoeff = timeCoeff(ENVELOPE_MS, sr)
                s.ratio = b.ratio
                s.thresholdDb = b.thresholdDb
                s.kneeWidthDb = max(0f, b.kneeWidthDb)
                s.gateDb = b.noiseGateDb
                s.expanderRatio = max(1f, b.expanderRatio)
                s.preGainLin = dbToLinear(b.preGainDb)
                s.postGainLin = dbToLinear(b.postGainDb)
                return s
            }
        }
    }

    private class LimiterState(
        var attackCoeff: Float,
        var releaseCoeff: Float,
        var envCoeff: Float,
        var ratio: Float,
        var thresholdDb: Float,
        var postGainLin: Float,
    ) {
        var envelope: Float = 0f
        var smoothedGain: Float = 1f

        fun reset() {
            envelope = 0f
            smoothedGain = 1f
        }

        fun updateFrom(l: DynamicsConfig.Limiter, sr: Int) {
            attackCoeff = timeCoeff(l.attackMs, sr)
            releaseCoeff = timeCoeff(l.releaseMs, sr)
            envCoeff = timeCoeff(ENVELOPE_MS, sr)
            ratio = l.ratio
            thresholdDb = l.thresholdDb
            postGainLin = dbToLinear(l.postGainDb)
        }

        companion object {
            fun fromConfig(l: DynamicsConfig.Limiter, sr: Int): LimiterState =
                LimiterState(
                    attackCoeff = timeCoeff(l.attackMs, sr),
                    releaseCoeff = timeCoeff(l.releaseMs, sr),
                    envCoeff = timeCoeff(ENVELOPE_MS, sr),
                    ratio = l.ratio,
                    thresholdDb = l.thresholdDb,
                    postGainLin = dbToLinear(l.postGainDb),
                )
        }
    }

    private companion object {
        /** Envelope follower time constant in ms (fixed; tuned for speech). */
        const val ENVELOPE_MS: Float = 15f

        /** Hard ceiling just below full-scale to leave a sliver of headroom. */
        const val LIMIT: Float = 0.99f

        /** Floor envelope to avoid log10(0) → −∞. ≈ −120 dBFS. */
        const val ENV_FLOOR: Float = 1e-6f

        /** One-pole α for time-constant smoothing: α = 1 − exp(−1 / (τ·fs)). */
        fun timeCoeff(timeMs: Float, sampleRateHz: Int): Float {
            if (timeMs <= 0f || sampleRateHz <= 0) return 1f
            val tau = timeMs * 0.001
            val a = 1.0 - exp(-1.0 / (tau * sampleRateHz))
            return a.coerceIn(0.0, 1.0).toFloat()
        }

        /** One-pole LPF α targeting [cutoffHz] −3 dB cutoff. */
        fun onePoleAlpha(cutoffHz: Float, sampleRateHz: Int): Float {
            if (cutoffHz <= 0f || sampleRateHz <= 0) return 1f
            // Bilinear-warped one-pole: α = 1 − exp(−2π·fc/fs).
            val a = 1.0 - exp(-2.0 * Math.PI * cutoffHz.toDouble() / sampleRateHz)
            return a.coerceIn(0.0, 1.0).toFloat()
        }

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    }
}