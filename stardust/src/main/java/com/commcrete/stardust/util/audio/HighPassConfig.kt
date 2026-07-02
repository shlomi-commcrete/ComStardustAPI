package com.commcrete.stardust.util.audio

/**
 * Configuration for the [HighPassFilter] stage. The HPF runs **first**
 * in the live filter chain — before [NotchFilter] and RNNoise — so
 * low-frequency rumble (HVAC, traffic, hand-held thump) is removed
 * before the spectral / ML stages even see it:
 *
 * ```
 * HPF → Declick → Notch → RNNoise → DP → (AGC) → AI-gain → LPF
 * ```
 *
 * Defaults preserve the entire voice fundamental range (male ≈ 100 Hz,
 * female ≈ 150–250 Hz) while attenuating sub-voice rumble:
 *
 *  - 80 Hz cutoff, 24 dB/octave → ‑18 dB at 40 Hz, ‑36 dB at 20 Hz
 *  - Voice fundamentals (≥ 100 Hz) sit in the pass-band → no warmth loss
 *  - Reduces RNNoise's workload; lets it focus on hiss / non-stationary
 *    noise where it excels
 *
 * 24 dB/octave is gentle enough that low-bass content fades smoothly
 * without phase-distortion artefacts that a steeper FIR would introduce.
 */
data class HighPassConfig(
    val enabled: Boolean = true,
    val cutoffHz: Float = 80f,
    val rollOffDbPerOctave: Float = 24f,
) {
    /** Short human-readable summary for logs. */
    @Suppress("unused")
    internal fun describe(): String =
        if (!enabled) "off"
        else "%.0fHz @ %.0fdB/oct".format(cutoffHz, rollOffDbPerOctave)
            .replace(',', '.')


}

