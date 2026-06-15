package com.commcrete.stardust.util.audio


/**
 * DSP profile for a recorder stream fed into `PttSendManager.addNewFrame`.
 *
 * Contains only signal-processing configuration and the source
 * [recordingDeviceType]. Routing/session metadata is passed per frame.
 */
data class AiRecorderProfile(
    val title: String,
    val isActive: Boolean = false,
    val lowPass: LowPassConfig?,
    val notch: NotchConfig?,
    val rnNoise: RnNoiseConfig?,
    val agc: AGCConfig?,
    val dynamics: DynamicsConfig?,
)

enum class RecordingDeviceType {
    OTHER,
    JBOX_INTERNAL,
    JBOX_EXTERNAL,
}

