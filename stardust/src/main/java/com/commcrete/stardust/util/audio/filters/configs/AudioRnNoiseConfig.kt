package com.commcrete.stardust.util.audio.filters.configs

import kotlin.math.pow

/**
 * Optional RNNoise-based denoiser stage applied **after** the [NotchConfig]
 * stage and **before** the [AGCConfig] / [LowPassConfig] / AI encoder.
 *
 * Internally uses
 * [com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor], which resamples
 * to 48 kHz, runs RNNoise's 480-sample frames, then writes the cleaned audio
 * back in place at the active sample rate.
 *
 * Falls back to pass-through if `librnnoise_jni.so` is missing — the stage
 * stays "enabled" but does nothing and the feeder logs a warning.
 *
 * ## Tuning aggressiveness
 *
 * RNNoise itself has no built-in "intensity" knob — it's a trained model
 * that outputs whatever it outputs. The parameter below lets you soften
 * its output without touching the native code:
 *
 * - [maxAttenuationDb] — per-frame RMS floor. When RNNoise suppresses a
 *   480-sample frame's RMS below `dry_rms × 10^(maxAttenuationDb/20)`,
 *   the entire frame is uniformly scaled up to meet the floor. This
 *   preserves RNNoise's spectral decisions (which frequencies to keep or
 *   cut) while preventing overall energy collapse (hollowness). Use
 *   `Float.NEGATIVE_INFINITY` (default) to disable the floor entirely
 *   and let RNNoise do whatever it wants.
 *
 */
data class RnNoiseConfig(
    val enabled: Boolean = true,
    /**
     * Maximum allowed attenuation per frame, in dB (negative number, or
     * `Float.NEGATIVE_INFINITY` to disable). Applied as a per-frame RMS
     * floor: if a 480-sample frame's RMS drops below
     * `dry_rms × 10^(maxAttenuationDb/20)`, the frame is uniformly scaled
     * up to the floor level. This preserves RNNoise's spectral shape
     * while preventing excessive suppression.
     *
     * E.g. `-6.0` means no frame's RMS may fall below 50 % of the
     * corresponding dry frame's RMS. Less negative = gentler denoising.
     *
     * Default `Float.NEGATIVE_INFINITY` disables the floor — RNNoise
     * output is used as-is. Set to e.g. `-6f` if RNNoise sounds hollow
     * on quiet voice content.
     */
    val maxAttenuationDb: Float = -20f,
) {
    /**
     * Linear floor derived from [maxAttenuationDb]. `0.0` if no floor is
     * configured (i.e. RNNoise output is used as-is, regardless of how
     * much it attenuated).
     */
    internal val attenuationFloorLin: Float
        get() = if (maxAttenuationDb.isFinite()) 10.0.pow(maxAttenuationDb / 20.0).toFloat() else 0f

    /** Short human-readable summary for logs. */
    internal fun describe(): String {
        val floorTxt = if (attenuationFloorLin > 0f)
            "maxAtten=${"%.1f".format(maxAttenuationDb).replace(',', '.')}dB"
        else "no-floor"
        return "rnnoise($floorTxt)"
    }

}
