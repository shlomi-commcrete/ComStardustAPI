package com.commcrete.stardust.util.audio


data class RecorderFiltersProfile(
    val lowPass: LowPassConfig? = null,
    val notch: NotchConfig? = null,
    val rnNoise: RnNoiseConfig? = null,
    val agc: AGCConfig? = null,
    val dynamics: DynamicsConfig? = null,
    val highPass: HighPassConfig? = null,
)

