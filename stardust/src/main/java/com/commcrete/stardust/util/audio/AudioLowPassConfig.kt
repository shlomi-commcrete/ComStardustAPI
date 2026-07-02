package com.commcrete.stardust.util.audio

/**
 * Configuration for the [LowPassFilter] stage. The LPF runs **last** in
 * the live filter chain, after AGC / AI-gain:
 *
 * ```
 * Declick → Notch → RNNoise → DP → (AGC) → AI-gain → LPF
 * ```
 *
 * Dual role:
 *  1. **Anti-aliasing** for the chain's resample to 24 kHz (codec target
 *     Nyquist = 12 kHz; the 7.5 kHz default cutoff sits well below it).
 *  2. **Killing residual hiss** above the voice band — anything supersonic
 *     that survived RNNoise / DP gets attenuated here.
 *
 * The filter is a cascade of first-order IIR sections (6 dB/octave per
 * stage), so [rollOffDbPerOctave] is rounded up to the nearest multiple
 * of 6. Cutoffs above Nyquist are silently clamped.
 *
 * Defaults preserve all intelligible speech content (vowel formants,
 * fricatives /s/ /sh/ /f/, sibilance) while attenuating residual hiss
 * above the voice band. 24 dB/octave is gentle enough that consonant
 * transients don't ring or pre-echo.
 *
 *
 */
data class LowPassConfig(
    val enabled: Boolean = true,
    val cutoffHz: Float = 3_800f,
    val rollOffDbPerOctave: Float = 30f,
) {
    /** Short human-readable summary for logs. */
    @Suppress("unused")
    internal fun describe(): String =
        if (!enabled) "off"
        else "%.0fHz @ %.0fdB/oct".format(cutoffHz, rollOffDbPerOctave)
            .replace(',', '.')

}

