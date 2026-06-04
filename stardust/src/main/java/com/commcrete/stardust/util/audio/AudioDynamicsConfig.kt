package com.commcrete.stardust.util.audio

/**
 * Software-side mirror of the HAL `android.media.audiofx.DynamicsProcessing`
 * chain that `AudioRecorderAI.tryAttachDynamicsProcessing` attaches to live
 * mic captures. Lets the test feeder reproduce the same input-gain → 3-band
 * MBC → limiter shaping that production sees, so `*-ai_input_24k.wav` is
 * comparable to what the AI encoder receives in the field.
 *
 * Defaults match the HAL preset for **jbox / PCM2900C speech capture**:
 *
 *  - Input gain: +6 dB makeup (compensates for the PCM2900C's quiet analog stage).
 *  - Band 0 (sub-bass, 0–200 Hz): −6 dB pre-gain, ratio 1:1 (just attenuation).
 *  - Band 1 (speech, 200–4 000 Hz): 3:1 above −24 dBFS, knee 6 dB, +6 dB make-up.
 *  - Band 2 (highs, 4 000+ Hz): −3 dB pre-gain, downward expander 2:1
 *    below −60 dBFS (USB hiss gate).
 *  - Limiter: −1 dBFS ceiling, 1 ms attack, 50 ms release, 20:1.
 *
 * Stage order in [com.commcrete.stardust.util.audio.AudioFeederEngine]'s
 * live chain (when DP is the first stage):
 *
 * ```
 * gain → DP → notch → rnnoise → AGC → LPF → AI encoder
 * ```
 *
 * Notes on parity with the HAL:
 *  - The HAL `MbcBand.cutoffFrequency` is the **upper edge** of each band;
 *    we keep the same convention here ([Band.highEdgeHz]).
 *  - The HAL is at-rate (48 kHz on jbox); this software path runs at
 *    `LiveFilterChain.sampleRate`, which is the source's native rate
 *    (so 48 kHz for jbox WAVs and 24 kHz for already-decimated sources).
 *    Band cutoffs that exceed Nyquist are silently clamped, mirroring the
 *    HAL's behaviour on lower-rate sessions.
 */
data class DynamicsConfig(
    val enabled: Boolean = true,
    val inputGainDb: Float = 6f,
    val band0: Band = Band.subBassDefault(),
    val band1: Band = Band.speechDefault(),
    val band2: Band = Band.highsDefault(),
    val limiter: Limiter = Limiter.defaultPreset(),
) {
    /**
     * One band of the multiband compressor. Lower edge is implicit (0 Hz for
     * the first band, the previous band's [highEdgeHz] for the others).
     */
    data class Band(
        /** Upper edge in Hz. Above Nyquist → clamped to Nyquist. */
        val highEdgeHz: Float,
        val attackMs: Float,
        val releaseMs: Float,
        /** Compression ratio above [thresholdDb]. 1 = no compression. */
        val ratio: Float,
        /** Compression threshold, dBFS (negative). */
        val thresholdDb: Float,
        /** Soft-knee width in dB centred on [thresholdDb]. 0 = hard knee. */
        val kneeWidthDb: Float,
        /** Downward-expander threshold, dBFS. Below this the [expanderRatio] kicks in. */
        val noiseGateDb: Float,
        /** Downward-expansion ratio below [noiseGateDb]. 1 = no expansion. */
        val expanderRatio: Float,
        /** Static gain applied BEFORE the dynamics stage, in dB. */
        val preGainDb: Float,
        /** Static gain applied AFTER the dynamics stage, in dB. */
        val postGainDb: Float,
    ) {
        companion object {
            /** Sub-bass kill: 0–200 Hz, −6 dB attenuation, no compression. */
            fun subBassDefault() = Band(
                highEdgeHz = 200f,
                attackMs = 10f,
                releaseMs = 100f,
                ratio = 1f,
                thresholdDb = 0f,
                kneeWidthDb = 0f,
                noiseGateDb = -100f,
                expanderRatio = 1f,
                preGainDb = -6f,
                postGainDb = 0f,
            )

            /** Speech band: 200–4 kHz, 3:1 above −24 dBFS, +6 dB make-up. */
            fun speechDefault() = Band(
                highEdgeHz = 4_000f,
                attackMs = 5f,
                releaseMs = 80f,
                ratio = 3f,
                thresholdDb = -24f,
                kneeWidthDb = 6f,
                noiseGateDb = -100f,
                expanderRatio = 1f,
                preGainDb = 0f,
                postGainDb = 6f,
            )

            /** Highs: 4 kHz–20 kHz, −3 dB attenuation, expander gate at −60 dBFS. */
            fun highsDefault() = Band(
                // 20 kHz upper edge mirrors the HAL preset
                // (DynamicsProcessing.MbcBand.cutoffFrequency = 20000f in
                // AudioRecorderAI.tryAttachDynamicsProcessing). At rates
                // below 40 kHz this gets silently clamped to Nyquist by the
                // filter — same behaviour as the HAL on lower-rate sessions.
                highEdgeHz = 20_000f,
                attackMs = 5f,
                releaseMs = 80f,
                ratio = 1f,
                thresholdDb = 0f,
                kneeWidthDb = 0f,
                noiseGateDb = -60f,
                expanderRatio = 2f,
                preGainDb = -3f,
                postGainDb = 0f,
            )
        }
    }

    /** Brick-wall limiter applied after the bands are summed. */
    data class Limiter(
        val thresholdDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val ratio: Float,
        val postGainDb: Float,
    ) {
        companion object {
            /** −1 dBFS ceiling, 1 ms attack, 50 ms release, 20:1. */
            fun defaultPreset() = Limiter(
                thresholdDb = -1f,
                attackMs = 1f,
                releaseMs = 50f,
                ratio = 20f,
                postGainDb = 0f,
            )
        }
    }

    /** Short human-readable summary for logs. */
    internal fun describe(): String = (
        "in=%+.1fdB | b0(<%.0fHz pre%+.0fdB ratio%.1f) | " +
            "b1(<%.0fHz %.1f:1@%.0fdB+%.0fdB) | " +
            "b2(>%.0fHz pre%+.0fdB exp%.1f@%.0fdB) | " +
            "lim(%.0fdBFS %.1f:1)"
        ).format(
            inputGainDb,
            band0.highEdgeHz, band0.preGainDb, band0.ratio,
            band1.highEdgeHz, band1.ratio, band1.thresholdDb, band1.postGainDb,
            band2.highEdgeHz.takeIf { it.isFinite() } ?: -1f,
            band2.preGainDb, band2.expanderRatio, band2.noiseGateDb,
            limiter.thresholdDb, limiter.ratio,
        ).replace(',', '.')
}

