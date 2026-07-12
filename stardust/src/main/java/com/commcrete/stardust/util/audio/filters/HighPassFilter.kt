package com.commcrete.stardust.util.audio.filters

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Configurable IIR high-pass filter intended for use in the jbox audio
 * input processing chain. Mirrors [LowPassFilter] but built around the
 * complementary one-pole pair:
 *
 * ```
 * y_lp[n] = y_lp[n-1] + alpha * (x[n] - y_lp[n-1])    // tracking LPF
 * y_hp[n] = x[n] - y_lp[n]                             // residual = HP
 * ```
 *
 * cascaded `ceil(rollOffDbPerOctave / 6)` times. This gives a
 * well-behaved minimum-phase HP that's allocation-free in the hot loop
 * and safe to call from real-time audio threads.
 *
 * **Why an HP belongs in the chain.** RNNoise is excellent at
 * non-stationary noise (typing, paper rustle, hiss) but lets through the
 * low-frequency broadband stuff that dominates real-world capture:
 *
 *  - HVAC / A/C drone (40–80 Hz)
 *  - Traffic rumble (20–100 Hz)
 *  - Hand-held device thumb / table thump (DC–100 Hz)
 *  - Mic stand vibration (40–200 Hz)
 *
 * The [NotchFilter] only kills narrow mains-hum lines (50/60 Hz), not
 * surrounding broadband rumble. A gentle ~80 Hz HP placed BEFORE
 * RNNoise removes that energy at zero cost to voice clarity (male
 * fundamental ≈ 100 Hz, female ≈ 150–250 Hz — both above 80 Hz).
 *
 * The filter keeps internal state between calls, so a single instance
 * must be used for a single continuous audio stream. Call [reset] on
 * stream restart (e.g. PTT key-up / key-down).
 */
class HighPassFilter(
    sampleRateHz: Int,
    cutoffHz: Float,
    rollOffDbPerOctave: Float,
) {

    /** Current sample rate in Hz. */
    var sampleRateHz: Int = sampleRateHz
        private set

    /** Current -3 dB cutoff frequency in Hz. */
    var cutoffHz: Float = cutoffHz
        private set

    /** Current roll-off slope in dB / octave. */
    var rollOffDbPerOctave: Float = rollOffDbPerOctave
        private set

    /** Number of cascaded first-order sections (>= 1). */
    private var stages: Int = stagesFor(rollOffDbPerOctave)

    /** One-pole LPF coefficient used to derive the HP output. */
    private var alpha: Float = alphaFor(sampleRateHz, cutoffHz)

    /** Per-stage tracking-LPF state. */
    private var state: FloatArray = FloatArray(stages)

    fun configure(
        sampleRateHz: Int = this.sampleRateHz,
        cutoffHz: Float = this.cutoffHz,
        rollOffDbPerOctave: Float = this.rollOffDbPerOctave,
    ) {
        this.sampleRateHz = sampleRateHz
        this.cutoffHz = cutoffHz
        this.rollOffDbPerOctave = rollOffDbPerOctave
        this.alpha = alphaFor(sampleRateHz, cutoffHz)
        val newStages = stagesFor(rollOffDbPerOctave)
        if (newStages != stages) {
            stages = newStages
            state = FloatArray(newStages)
        }
    }

    /** Clear all internal state. Call on stream start / restart. */
    fun reset() {
        for (i in state.indices) state[i] = 0f
    }

    /**
     * Filter a block of 16-bit PCM samples in place.
     *
     * @param pcm    the buffer holding interleaved mono samples.
     * @param length number of valid samples in [pcm] (defaults to full size).
     */
    fun processInPlace(pcm: ShortArray, length: Int = pcm.size) {
        val n = if (length > pcm.size) pcm.size else length
        val a = alpha
        val s = state
        val stg = stages
        for (i in 0 until n) {
            var v = pcm[i].toFloat()
            for (k in 0 until stg) {
                // Tracking LPF state, then residual is the HP output.
                val prev = s[k]
                val lp = prev + a * (v - prev)
                s[k] = lp
                v = v - lp
            }
            val iv = v.roundToInt()
            pcm[i] = when {
                iv > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                iv < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> iv.toShort()
            }
        }
    }

    /**
     * Filter a block of float PCM samples (typically in [-1.0, 1.0]) in place.
     */
    fun processInPlace(pcm: FloatArray, length: Int = pcm.size) {
        val n = if (length > pcm.size) pcm.size else length
        val a = alpha
        val s = state
        val stg = stages
        for (i in 0 until n) {
            var v = pcm[i]
            for (k in 0 until stg) {
                val prev = s[k]
                val lp = prev + a * (v - prev)
                s[k] = lp
                v = v - lp
            }
            pcm[i] = v
        }
    }

    private companion object {

        /** Map dB/octave slope to a number of cascaded first-order sections. */
        fun stagesFor(rollOffDbPerOctave: Float): Int {
            val n = (rollOffDbPerOctave / 6f).roundToInt()
            return max(1, n)
        }

        /**
         * One-pole LPF coefficient used by the complementary HP. Same
         * derivation as [LowPassFilter.alphaFor] —
         * `alpha = 1 - exp(-2*pi*fc/fs)` — clamped against Nyquist.
         */
        fun alphaFor(sampleRateHz: Int, cutoffHz: Float): Float {
            if (sampleRateHz <= 0 || cutoffHz <= 0f) return 0f
            val fcClamped = cutoffHz.coerceAtMost(sampleRateHz * 0.499f)
            val x = 2.0 * PI * fcClamped / sampleRateHz
            val a = 1.0 - exp(-x)
            return a.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

