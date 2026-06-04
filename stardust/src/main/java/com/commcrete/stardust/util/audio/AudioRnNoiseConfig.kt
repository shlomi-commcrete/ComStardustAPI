package com.commcrete.stardust.util.audio

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
 * that outputs whatever it outputs. The two parameters below let you soften
 * its output without touching the native code:
 *
 * - [mix] — straight wet/dry blend. `1.0` = full RNNoise, `0.0` = bypass,
 *   `0.5` = equal blend. Use this when RNNoise sounds artificial / hollow.
 *
 * - [maxAttenuationDb] — clamp per-sample attenuation. RNNoise often pulls
 *   noise-only frames down by 30+ dB which can sound "swallowed". Set this
 *   to e.g. `-18.0` to ensure no sample is ever attenuated below 12.6 % of
 *   its dry magnitude (preserves room tone / breathing). Use `Float.NEGATIVE_INFINITY`
 *   (default) to disable the clamp entirely and let RNNoise do whatever it wants.
 *
 * The two stack: RNNoise output is first attenuation-clamped against the
 * dry signal, then wet/dry mixed.
 */
data class RnNoiseConfig(
    val enabled: Boolean = true,
    /**
     * Wet/dry blend in `[0, 1]`. `1.0` = full RNNoise output, `0.0` = bypass
     * (pure original), `0.5` = equal mix. Values outside `[0, 1]` are clamped.
     */
    val mix: Float = 1.0f,
    /**
     * Maximum allowed attenuation per sample, in dB (negative number, or
     * `Float.NEGATIVE_INFINITY` to disable). E.g. `-18.0` means no output
     * sample's magnitude may fall below `10^(-18/20) ≈ 0.126` of the
     * corresponding dry input sample's magnitude. Less negative values =
     * gentler denoising.
     */
    val maxAttenuationDb: Float = Float.NEGATIVE_INFINITY,
) {
    /** Wet/dry mix clamped to `[0, 1]`. */
    internal val mixClamped: Float get() = mix.coerceIn(0f, 1f)

    /**
     * Linear floor derived from [maxAttenuationDb]. `0.0` if no clamp is
     * configured (i.e. RNNoise output is used as-is, regardless of how
     * much it attenuated).
     */
    internal val attenuationFloorLin: Float
        get() = if (maxAttenuationDb.isFinite()) 10.0.pow(maxAttenuationDb / 20.0).toFloat() else 0f

    /** Short human-readable summary for logs. */
    internal fun describe(): String {
        val mixPct = (mixClamped * 100f).toInt()
        val floorTxt = if (attenuationFloorLin > 0f)
            ", maxAtten=${"%.1f".format(maxAttenuationDb).replace(',', '.')}dB"
        else ""
        return "rnnoise(mix=${mixPct}%$floorTxt)"
    }
}

