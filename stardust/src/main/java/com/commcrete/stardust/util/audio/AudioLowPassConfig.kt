package com.commcrete.stardust.util.audio

/** Optional low-pass stage; last DSP step before the AI encoder. */
data class LowPassConfig(
    val enabled: Boolean = true,
    val cutoffHz: Float = 2_000f,
    val rollOffDbPerOctave: Float = 12f,
)

