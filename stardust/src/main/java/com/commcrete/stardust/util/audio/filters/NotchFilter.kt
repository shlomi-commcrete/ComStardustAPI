package com.commcrete.stardust.util.audio.filters

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Configurable IIR notch filter intended for use in the jbox audio input
 * processing chain (alongside other DSP stages such as [LowPassFilter],
 * [com.commcrete.stardust.util.audio.Equalizer], noise suppression, AGC, etc.).
 *
 * The filter removes narrow bands around an arbitrary list of [bands], each
 * with its own frequency and Q. This is useful for killing tonal contaminants
 * such as the jbox "piiii" whine, mains hum (50/60 Hz + harmonics), switching
 * -power-supply spurs, etc., without touching the surrounding speech spectrum.
 *
 * Implementation: one second-order RBJ biquad notch per band, cascaded.
 *
 * ```
 *   ω0 = 2π·f0/fs
 *   α  = sin(ω0) / (2·Q)
 *   b0 =  1            b1 = -2·cos(ω0)   b2 =  1
 *   a0 =  1 + α        a1 = -2·cos(ω0)   a2 =  1 - α
 * ```
 *
 * Two constructors are provided:
 *  1. **Explicit bands** – pass a [Band] list, one per notch, each with its
 *     own frequency and Q. Use this when you want per-harmonic control:
 *     ```
 *     NotchFilter(24_000, listOf(
 *         NotchFilter.Band(1000f, q = 60f),  // narrow notch on f0
 *         NotchFilter.Band(2000f, q = 30f),  // wider notch on 2·f0
 *         NotchFilter.Band(3000f, q = 30f),
 *     ))
 *     ```
 *  2. **Fundamental + N harmonics with shared Q** – classic shortcut for the
 *     "kill f0 and its first N integer harmonics" case:
 *     ```
 *     NotchFilter(24_000, fundamentalHz = 1000f, q = 30f, numHarmonics = 4)
 *     ```
 *
 * Any band whose frequency lies at/above Nyquist is silently skipped.
 *
 * The filter keeps internal state between calls, so a single instance must
 * be used for a single continuous audio stream. Call [reset] on stream
 * restart (e.g. PTT key-up / key-down).
 */
class NotchFilter(
    sampleRateHz: Int,
    bands: List<Band>,
) {

    /** One notch section: a target frequency and its Q. */
    data class Band(
        /** Centre frequency of the notch, in Hz. */
        val frequencyHz: Float,
        /**
         * Quality factor – higher Q means a narrower, more selective notch
         * (and longer ringing). Typical values: 10..50 for mains hum, 30..100
         * for narrow whistles such as the jbox "piiii".
         */
        val q: Float,
    )

    /** Convenience constructor: fundamental + first N integer harmonics, shared Q. */
    constructor(
        sampleRateHz: Int,
        fundamentalHz: Float,
        q: Float,
        numHarmonics: Int = 1,
    ) : this(sampleRateHz, harmonicsToBands(fundamentalHz, q, numHarmonics))

    /** Current sample rate in Hz. */
    var sampleRateHz: Int = sampleRateHz
        private set

    /** Current list of notch bands (defensive copy). */
    var bands: List<Band> = bands.toList()
        private set

    // ─── Per-biquad coefficients & state ─────────────────────────────────
    // Only `activeSections` entries are valid (bands above Nyquist are
    // skipped at coefficient time).
    private var activeSections: Int = 0
    private var b0: FloatArray = FloatArray(0)
    private var b1: FloatArray = FloatArray(0)
    private var b2: FloatArray = FloatArray(0)
    private var a1: FloatArray = FloatArray(0)
    private var a2: FloatArray = FloatArray(0)
    // Direct-Form I state: previous inputs/outputs per section.
    private var x1: FloatArray = FloatArray(0)
    private var x2: FloatArray = FloatArray(0)
    private var y1: FloatArray = FloatArray(0)
    private var y2: FloatArray = FloatArray(0)

    init {
        rebuildCoefficients()
    }

    /**
     * Replace the band list at runtime. Internal state is preserved if the
     * number of active sections does not change; otherwise it is cleared (to
     * avoid feeding stale state into newly enabled biquads).
     */
    fun configure(
        sampleRateHz: Int = this.sampleRateHz,
        bands: List<Band> = this.bands,
    ) {
        this.sampleRateHz = sampleRateHz
        this.bands = bands.toList()
        rebuildCoefficients()
    }

    /** Shortcut: replace the band list with `fundamental + N integer harmonics @ shared Q`. */
    fun configureHarmonics(
        fundamentalHz: Float,
        q: Float,
        numHarmonics: Int,
        sampleRateHz: Int = this.sampleRateHz,
    ) = configure(sampleRateHz, harmonicsToBands(fundamentalHz, q, numHarmonics))

    /** Clear all internal biquad state. Call on stream start / restart. */
    fun reset() {
        for (i in 0 until activeSections) {
            x1[i] = 0f; x2[i] = 0f; y1[i] = 0f; y2[i] = 0f
        }
    }

    /**
     * Filter a block of 16-bit PCM samples in place.
     *
     * @param pcm    the buffer holding interleaved mono samples.
     * @param length number of valid samples in [pcm] (defaults to full size).
     */
    fun processInPlace(pcm: ShortArray, length: Int = pcm.size) {
        val n = if (length > pcm.size) pcm.size else length
        val sections = activeSections
        if (sections == 0) return
        for (i in 0 until n) {
            var v = pcm[i].toFloat()
            for (k in 0 until sections) {
                val xn = v
                val yn = b0[k] * xn + b1[k] * x1[k] + b2[k] * x2[k] -
                    a1[k] * y1[k] - a2[k] * y2[k]
                x2[k] = x1[k]; x1[k] = xn
                y2[k] = y1[k]; y1[k] = yn
                v = yn
            }
            val iv = v.roundToInt()
            pcm[i] = when {
                iv > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                iv < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> iv.toShort()
            }
        }
    }

    /** Filter a block of float PCM samples (typically in [-1.0, 1.0]) in place. */
    fun processInPlace(pcm: FloatArray, length: Int = pcm.size) {
        val n = if (length > pcm.size) pcm.size else length
        val sections = activeSections
        if (sections == 0) return
        for (i in 0 until n) {
            var v = pcm[i]
            for (k in 0 until sections) {
                val xn = v
                val yn = b0[k] * xn + b1[k] * x1[k] + b2[k] * x2[k] -
                    a1[k] * y1[k] - a2[k] * y2[k]
                x2[k] = x1[k]; x1[k] = xn
                y2[k] = y1[k]; y1[k] = yn
                v = yn
            }
            pcm[i] = v
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private fun rebuildCoefficients() {
        val fs = sampleRateHz
        val nyquist = fs * 0.5f
        // Keep only bands that fall safely below Nyquist (leave a small guard).
        val accepted = bands.filter { it.frequencyHz > 0f && it.frequencyHz < nyquist * 0.99f }
        val active = accepted.size
        if (active != activeSections) {
            b0 = FloatArray(active); b1 = FloatArray(active); b2 = FloatArray(active)
            a1 = FloatArray(active); a2 = FloatArray(active)
            x1 = FloatArray(active); x2 = FloatArray(active)
            y1 = FloatArray(active); y2 = FloatArray(active)
            activeSections = active
        }
        for (k in 0 until active) {
            val band = accepted[k]
            val q = band.q.coerceAtLeast(MIN_Q)
            val w0 = 2.0 * PI * band.frequencyHz / fs
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            // RBJ notch (band-reject, unity gain in pass-band).
            b0[k] = (1.0 / a0).toFloat()
            b1[k] = (-2.0 * cosW0 / a0).toFloat()
            b2[k] = (1.0 / a0).toFloat()
            a1[k] = (-2.0 * cosW0 / a0).toFloat()
            a2[k] = ((1.0 - alpha) / a0).toFloat()
        }
    }

    companion object {
        /** Guard against divide-by-zero / unstable biquads at extreme Q. */
        private const val MIN_Q = 0.1f

        /** Build a band list for `fundamental · h, h ∈ 1..numHarmonics`, all sharing the same Q. */
        fun harmonicsToBands(
            fundamentalHz: Float,
            q: Float,
            numHarmonics: Int,
        ): List<Band> {
            val n = max(1, numHarmonics)
            return List(n) { k -> Band(fundamentalHz * (k + 1), q) }
        }
    }
}

