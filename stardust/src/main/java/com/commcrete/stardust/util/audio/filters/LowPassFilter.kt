package com.commcrete.stardust.util.audio.filters

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Configurable IIR low-pass filter intended for use in the jbox audio input
 * processing chain (alongside other DSP filters such as the [com.commcrete.stardust.util.audio.Equalizer],
 * noise suppression, AGC, etc.).
 *
 * The filter is parameterised by:
 *  - [cutoffHz]            – the -3 dB corner frequency in Hertz.
 *  - [rollOffDbPerOctave]  – the desired stop-band slope in dB / octave.
 *
 * A single first-order (one-pole) IIR section gives 6 dB/octave of roll-off,
 * so the requested slope is realised by cascading
 * `ceil(rollOffDbPerOctave / 6)` identical first-order sections. This keeps
 * the implementation allocation-free, branch-free in the hot loop, and safe
 * to call from real-time audio threads (e.g. the jbox capture callback).
 *
 * The filter keeps internal state between calls, so a single instance must
 * be used for a single continuous audio stream. Call [reset] on stream
 * restart (e.g. PTT key-up / key-down).
 *
 * Typical usage in the jbox input chain:
 * ```
 * val lpf = LowPassFilter(
 *     sampleRateHz = 8000,
 *     cutoffHz = 3400f,
 *     rollOffDbPerOctave = 24f,
 * )
 * // ...inside the capture loop, after the raw read from the jbox ADC:
 * lpf.processInPlace(pcm, read)
 * // -> followed by the next filter stage (EQ, NS, AGC...)
 * ```
 */
class LowPassFilter(
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

    /** One-pole coefficient. y[n] = y[n-1] + alpha * (x[n] - y[n-1]). */
    private var alpha: Float = alphaFor(sampleRateHz, cutoffHz)

    /** Per-stage filter state (y[n-1]). */
    private var state: FloatArray = FloatArray(stages)

    /**
     * Replace the filter parameters at runtime. Internal state is preserved
     * if the number of stages does not change; otherwise it is cleared.
     */
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
                val prev = s[k]
                val out = prev + a * (v - prev)
                s[k] = out
                v = out
            }
            // Clamp to 16-bit range before writing back.
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
                val out = prev + a * (v - prev)
                s[k] = out
                v = out
            }
            pcm[i] = v
        }
    }

    private companion object {

        /** Map dB/octave slope to a number of cascaded first-order sections. */
        fun stagesFor(rollOffDbPerOctave: Float): Int {
            // Each first-order one-pole section contributes 6 dB/octave.
            val n = (rollOffDbPerOctave / 6f).roundToInt()
            return max(1, n)
        }

        /**
         * One-pole low-pass coefficient derived from the bilinear-like
         * formulation:  alpha = 1 - exp(-2*pi*fc/fs).
         * Numerically robust for the audio range we deal with (8–48 kHz).
         */
        fun alphaFor(sampleRateHz: Int, cutoffHz: Float): Float {
            if (sampleRateHz <= 0 || cutoffHz <= 0f) return 1f
            val fcClamped = cutoffHz.coerceAtMost(sampleRateHz * 0.499f)
            val x = 2.0 * PI * fcClamped / sampleRateHz
            val a = 1.0 - exp(-x)
            return a.coerceIn(0.0, 1.0).toFloat()
        }
    }
}


