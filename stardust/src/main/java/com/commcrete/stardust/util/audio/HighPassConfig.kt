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

    companion object {
        /**
         * Standard rumble killer — 80 Hz / 24 dB/oct. Equivalent to the
         * no-arg [HighPassConfig] constructor; named for intent.
         * Removes HVAC, traffic, table thump, mic stand vibration while
         * preserving every voice fundamental.
         */
        @Suppress("unused")
        fun rumbleKill() = HighPassConfig()

        /**
         * Tighter telephony-style HP at 150 Hz. Use when capture
         * environment has very loud low-frequency content (vehicle
         * cabin, near A/C unit) AND only male voices won't be present.
         * Female voice fundamentals start to thin out near this cutoff.
         */
        @Suppress("unused")
        fun telephony() = HighPassConfig(cutoffHz = 150f, rollOffDbPerOctave = 24f)

        /**
         * Per-device default. JBOX captures via USB UART tend to pick up
         * board-level low-frequency noise; phone built-in mics are
         * cleaner at the bottom but also benefit from a gentle HP. The
         * preset is on for both with the same conservative 80 Hz / 24
         * dB/oct setting; tighten via [telephony] for hostile rooms.
         */
        fun getDefault(deviceType: RecordingDeviceType): HighPassConfig = when (deviceType) {
            RecordingDeviceType.JBOX_EXTERNAL -> HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f)
            else -> HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f)
        }
    }
}

