package com.commcrete.stardust.util.audio


data class AiRecorderProfile(
    val title: String,
    val isActive: Boolean = false,
    val lowPass: LowPassConfig? = null,
    val notch: NotchConfig? = null,
    val rnNoise: RnNoiseConfig? = null,
    val agc: AGCConfig? = null,
    val dynamics: DynamicsConfig? = null,
    val highPass: HighPassConfig? = null,
    val declick: DeclickConfig? = null,
)

enum class RecordingDeviceType {
    OTHER,
    JBOX_INTERNAL,
    JBOX_EXTERNAL,
}

