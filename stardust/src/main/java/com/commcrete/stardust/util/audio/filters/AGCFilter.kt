package com.commcrete.stardust.util.audio.filters

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Configurable Automatic Gain Control (AGC) filter intended for use in the
 * jbox audio input processing chain (alongside other DSP stages such as
 * [LowPassFilter], [NotchFilter], [com.commcrete.stardust.util.audio.Equalizer], noise suppression, etc.).
 *
 * The filter continuously estimates the short-term signal level and applies
 * a smoothly-varying gain so the output stays near a configurable
 * [targetLevel]. A simple feed-forward envelope follower with separate
 * [attackMs] / [releaseMs] time constants is used; gain changes are limited
 * by [maxGainDb] and [minGainDb], and a final hard limiter prevents clipping
 * if the loop briefly overshoots.
 *
 * Parameters:
 *  - [sampleRateHz]   – audio sample rate in Hz.
 *  - [targetLevel]    – desired output RMS level, normalised to full-scale
 *                       (0.0..1.0). Typical speech target: 0.1..0.3 (≈ -20…-10 dBFS).
 *  - [attackMs]       – time constant when gain has to DROP (signal got louder).
 *                       Short attack (1–10 ms) catches transients fast.
 *  - [releaseMs]      – time constant when gain has to RISE (signal got quieter).
 *                       Long release (100–1000 ms) avoids pumping on speech.
 *  - [maxGainDb]      – maximum boost the AGC is allowed to apply, in dB.
 *                       Keeps it from amplifying background noise during silence.
 *  - [minGainDb]      – maximum attenuation the AGC is allowed to apply, in dB.
 *  - [noiseGateLevel] – RMS level below which gain is frozen (no further boost).
 *                       Set to 0 to disable. Normalised to full-scale.
 *
 * Implementation runs on float internally and clamps to 16-bit on output.
 * The filter keeps internal state between calls, so a single instance must
 * be used for a single continuous audio stream. Call [reset] on stream
 * restart (e.g. PTT key-up / key-down).
 *
 * Typical usage in the jbox input chain:
 * ```
 * val agc = AGCFilter(
 *     sampleRateHz = 8000,
 *     targetLevel = 0.2f,
 *     attackMs = 5f,
 *     releaseMs = 250f,
 *     maxGainDb = 24f,
 *     minGainDb = -12f,
 *     noiseGateLevel = 0.005f,
 * )
 * // ...inside the capture loop, after LPF / notch / EQ:
 * agc.processInPlace(pcm, read)
 * // -> followed by the next stage (codec, AI encoder...)
 * ```
 */
class AGCFilter(
    sampleRateHz: Int,
    targetLevel: Float = 0.2f,
    attackMs: Float = 5f,
    releaseMs: Float = 250f,
    maxGainDb: Float = 24f,
    minGainDb: Float = -12f,
    noiseGateLevel: Float = 0f,
) {

    /** Current sample rate in Hz. */
    var sampleRateHz: Int = sampleRateHz
        private set

    /** Desired output RMS level (0..1, full-scale). */
    var targetLevel: Float = targetLevel
        private set

    /** Attack time constant in ms (gain-decrease smoothing). */
    var attackMs: Float = attackMs
        private set

    /** Release time constant in ms (gain-increase smoothing). */
    var releaseMs: Float = releaseMs
        private set

    /** Max boost the AGC will apply, in dB. */
    var maxGainDb: Float = maxGainDb
        private set

    /** Max cut the AGC will apply, in dB (negative number). */
    var minGainDb: Float = minGainDb
        private set

    /** Below this RMS level, gain is frozen (no boosting silence/noise). */
    var noiseGateLevel: Float = noiseGateLevel
        private set

    // ─── Derived state ───────────────────────────────────────────────────
    private var attackCoeff: Float = coeff(attackMs, sampleRateHz)
    private var releaseCoeff: Float = coeff(releaseMs, sampleRateHz)
    private var envCoeff: Float = coeff(ENVELOPE_MS, sampleRateHz)
    private var maxGain: Float = dbToLinear(maxGainDb)
    private var minGain: Float = dbToLinear(minGainDb)

    /** Smoothed signal envelope (RMS-like). */
    private var envelope: Float = 0f

    /** Smoothed gain currently applied to the signal. */
    private var currentGain: Float = 1f

    /**
     * Replace AGC parameters at runtime. Internal state (envelope, gain) is
     * preserved so the loop does not glitch on a config change.
     */
    fun configure(
        sampleRateHz: Int = this.sampleRateHz,
        targetLevel: Float = this.targetLevel,
        attackMs: Float = this.attackMs,
        releaseMs: Float = this.releaseMs,
        maxGainDb: Float = this.maxGainDb,
        minGainDb: Float = this.minGainDb,
        noiseGateLevel: Float = this.noiseGateLevel,
    ) {
        this.sampleRateHz = sampleRateHz
        this.targetLevel = targetLevel
        this.attackMs = attackMs
        this.releaseMs = releaseMs
        this.maxGainDb = maxGainDb
        this.minGainDb = minGainDb
        this.noiseGateLevel = noiseGateLevel
        this.attackCoeff = coeff(attackMs, sampleRateHz)
        this.releaseCoeff = coeff(releaseMs, sampleRateHz)
        this.envCoeff = coeff(ENVELOPE_MS, sampleRateHz)
        this.maxGain = dbToLinear(maxGainDb)
        this.minGain = dbToLinear(minGainDb)
    }

    /** Clear envelope / gain state. Call on stream start / restart. */
    fun reset() {
        envelope = 0f
        currentGain = 1f
    }

    /**
     * Process a block of 16-bit PCM samples in place.
     *
     * @param pcm    the buffer holding interleaved mono samples.
     * @param length number of valid samples in [pcm] (defaults to full size).
     */
    fun processInPlace(pcm: ShortArray, length: Int = pcm.size) {
        val n = if (length > pcm.size) pcm.size else length
        if (n == 0) return
        val invFs = 1f / Short.MAX_VALUE.toFloat()
        val scale = Short.MAX_VALUE.toFloat()
        for (i in 0 until n) {
            val x = pcm[i] * invFs
            val y = step(x)
            val iv = (y * scale).roundToInt()
            pcm[i] = when {
                iv > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                iv < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> iv.toShort()
            }
        }
    }

    /**
     * Process a block of float PCM samples (typically in [-1.0, 1.0]) in place.
     */
    fun processInPlace(pcm: FloatArray, length: Int = pcm.size) {
        val n = if (length > pcm.size) pcm.size else length
        if (n == 0) return
        for (i in 0 until n) {
            pcm[i] = step(pcm[i]).coerceIn(-1f, 1f)
        }
    }

    /** Current smoothed gain applied to the signal (linear, not dB). */
    fun currentGainLinear(): Float = currentGain

    /** Current smoothed envelope estimate (linear, full-scale). */
    fun currentEnvelope(): Float = envelope

    // ─── Hot path ────────────────────────────────────────────────────────

    private fun step(x: Float): Float {
        // 1) Envelope follower (one-pole on |x|, RMS-ish).
        val absX = abs(x)
        envelope += envCoeff * (absX - envelope)

        // 2) Desired gain to bring envelope to target (if above noise gate).
        val desiredGain: Float = if (envelope > noiseGateLevel && envelope > 1e-6f) {
            (targetLevel / envelope).coerceIn(minGain, maxGain)
        } else {
            // Below the gate: don't change gain (freeze) – avoids pumping noise up.
            currentGain
        }

        // 3) Smooth gain change with attack/release.
        val c = if (desiredGain < currentGain) attackCoeff else releaseCoeff
        currentGain += c * (desiredGain - currentGain)

        // 4) Apply + soft hard-limit at full-scale.
        val out = x * currentGain
        return when {
            out > LIMIT -> LIMIT
            out < -LIMIT -> -LIMIT
            else -> out
        }
    }

    private companion object {

        /** Envelope follower time constant in ms (fixed; tuned for speech). */
        const val ENVELOPE_MS: Float = 15f

        /** Hard ceiling just below full-scale to keep some headroom. */
        const val LIMIT: Float = 0.99f

        /** One-pole smoothing coefficient for the given time constant. */
        fun coeff(timeMs: Float, sampleRateHz: Int): Float {
            if (timeMs <= 0f || sampleRateHz <= 0) return 1f
            val tau = timeMs * 0.001
            val a = 1.0 - exp(-1.0 / (tau * sampleRateHz))
            return a.coerceIn(0.0, 1.0).toFloat()
        }

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    }
}