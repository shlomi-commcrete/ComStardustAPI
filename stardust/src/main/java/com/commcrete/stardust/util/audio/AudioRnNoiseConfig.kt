package com.commcrete.stardust.util.audio

/**
 * Optional RNNoise-based denoiser stage applied **after** the [NotchConfig]
 * stage and **before** the [AGCConfig] / [LowPassConfig] / AI encoder.
 *
 * Internally uses
 * [com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor], which resamples
 * to 48 kHz, runs RNNoise's 480-sample frames, then writes the cleaned audio
 * back in place at [AudioTestFeeder.TARGET_SAMPLE_RATE].
 *
 * Falls back to pass-through if `librnnoise_jni.so` is missing — the stage
 * stays "enabled" but does nothing and the feeder logs a warning.
 */
data class RnNoiseConfig(
    val enabled: Boolean = true,
) {
    /** Short human-readable summary for logs. */
    internal fun describe(): String = "rnnoise"
}

