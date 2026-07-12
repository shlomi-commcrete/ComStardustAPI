package com.commcrete.stardust.util.audio.filters.configs

/**
 * Optional [com.commcrete.stardust.util.audio.filters.AGCFilter] (Automatic Gain Control) stage applied to every emitted
 * chunk **after** the [NotchConfig] and [RnNoiseConfig] stages and **before**
 * the [LowPassConfig] / AI encoder. Continuously adjusts gain so the output
 * stays near [targetLevel] (RMS, normalised to full-scale).
 *
 *  - [targetLevel]    – desired output RMS, 0..1 full-scale (e.g. 0.2 ≈ −14 dBFS).
 *  - [attackMs]       – time constant when reducing gain (signal got louder).
 *  - [releaseMs]      – time constant when raising gain (signal got quieter).
 *  - [maxGainDb]      – maximum boost the AGC may apply.
 *  - [minGainDb]      – maximum cut the AGC may apply (negative dB).
 *  - [noiseGateLevel] – RMS below which gain is frozen (0 = disabled).
 *
 * The feeder will, per source, create one [com.commcrete.stardust.util.audio.filters.AGCFilter] instance (state carried
 * across chunks), process each chunk in place, accumulate the processed
 * samples and write a `<label>-agc-*.wav` file next to the other artifacts so
 * the post-AGC signal (exactly what the AI encoder receives) can be inspected
 * offline.
 */
data class AGCConfig(
    val enabled: Boolean = true,
    val targetLevel: Float = 0.15f,
    val attackMs: Float = 30f,
    val releaseMs: Float = 350f,
    val maxGainDb: Float = 18f,
    val minGainDb: Float = -6f,
    val noiseGateLevel: Float = 0.003f,
) {
    /** Short human-readable summary for logs. */
    internal fun describe(): String =
        "tgt=%.2f/atk=%.0fms/rel=%.0fms/+%.0fdB/%.0fdB/gate=%.3f".format(
            targetLevel, attackMs, releaseMs, maxGainDb, minGainDb, noiseGateLevel
        )

}

