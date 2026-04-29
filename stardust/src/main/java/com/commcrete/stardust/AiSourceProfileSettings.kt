package com.commcrete.stardust

import android.media.MediaRecorder

/** Public, app-facing source profile model for AI recorder tuning. */
data class AiSourceProfile(
    val makeupGain: Float,
    val agcTargetRms: Float,
    val agcMaxGain: Float,
    val agcNoiseFloorRms: Float,
    val noiseGateRms: Int,
    // ─── Software downward expander (noise cancellation) ──────────────────
    /** How aggressive the downward expansion is. 1f = none, 8f = brutal. */
    val expanderRatio: Float,
    /** SNR (linear, signal/noise-floor) above which speech passes untouched. */
    val expanderOpenSnr: Float,
    /** Maximum attenuation applied between words. 0.001 ≈ −60 dB, 1.0 = off. */
    val expanderMinGain: Float,
    /** Expander attack (seconds). Smaller = opens faster on speech onsets. */
    val expanderAttackSec: Float,
    /** Expander release (seconds). Larger = smoother tails, less chopping. */
    val expanderReleaseSec: Float,
) {
    companion object Limits {
        const val MAKEUP_GAIN_MIN = 0.1f
        const val MAKEUP_GAIN_MAX = 6.0f

        const val AGC_TARGET_RMS_MIN = 300f
        const val AGC_TARGET_RMS_MAX = 10000f

        const val AGC_MAX_GAIN_MIN = 1.0f
        const val AGC_MAX_GAIN_MAX = 20.0f

        const val AGC_NOISE_FLOOR_RMS_MIN = 0f
        const val AGC_NOISE_FLOOR_RMS_MAX = 5000f

        const val NOISE_GATE_RMS_MIN = 0
        const val NOISE_GATE_RMS_MAX = 5000

        const val EXPANDER_RATIO_MIN = 1.0f
        const val EXPANDER_RATIO_MAX = 8.0f

        const val EXPANDER_OPEN_SNR_MIN = 1.5f
        const val EXPANDER_OPEN_SNR_MAX = 10.0f

        const val EXPANDER_MIN_GAIN_MIN = 0.001f
        const val EXPANDER_MIN_GAIN_MAX = 1.0f

        const val EXPANDER_ATTACK_SEC_MIN = 0.001f
        const val EXPANDER_ATTACK_SEC_MAX = 0.050f

        const val EXPANDER_RELEASE_SEC_MIN = 0.020f
        const val EXPANDER_RELEASE_SEC_MAX = 0.500f

        val MAKEUP_GAIN_RANGE: ClosedFloatingPointRange<Float> = MAKEUP_GAIN_MIN..MAKEUP_GAIN_MAX
        val AGC_TARGET_RMS_RANGE: ClosedFloatingPointRange<Float> = AGC_TARGET_RMS_MIN..AGC_TARGET_RMS_MAX
        val AGC_MAX_GAIN_RANGE: ClosedFloatingPointRange<Float> = AGC_MAX_GAIN_MIN..AGC_MAX_GAIN_MAX
        val AGC_NOISE_FLOOR_RMS_RANGE: ClosedFloatingPointRange<Float> = AGC_NOISE_FLOOR_RMS_MIN..AGC_NOISE_FLOOR_RMS_MAX
        val NOISE_GATE_RMS_RANGE: IntRange = NOISE_GATE_RMS_MIN..NOISE_GATE_RMS_MAX

        val EXPANDER_RATIO_RANGE: ClosedFloatingPointRange<Float> = EXPANDER_RATIO_MIN..EXPANDER_RATIO_MAX
        val EXPANDER_OPEN_SNR_RANGE: ClosedFloatingPointRange<Float> = EXPANDER_OPEN_SNR_MIN..EXPANDER_OPEN_SNR_MAX
        val EXPANDER_MIN_GAIN_RANGE: ClosedFloatingPointRange<Float> = EXPANDER_MIN_GAIN_MIN..EXPANDER_MIN_GAIN_MAX
        val EXPANDER_ATTACK_SEC_RANGE: ClosedFloatingPointRange<Float> = EXPANDER_ATTACK_SEC_MIN..EXPANDER_ATTACK_SEC_MAX
        val EXPANDER_RELEASE_SEC_RANGE: ClosedFloatingPointRange<Float> = EXPANDER_RELEASE_SEC_MIN..EXPANDER_RELEASE_SEC_MAX
    }
}

enum class AiAudioSource(val androidSource: Int) {
    MIC(MediaRecorder.AudioSource.MIC),
    DEFAULT(MediaRecorder.AudioSource.DEFAULT),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),
    VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL);

    companion object {
        fun fromAndroidSource(source: Int): AiAudioSource? =
            entries.firstOrNull { it.androidSource == source }
    }
}

data class AiSourceProfileSettings(
    val useSourceProfile: Boolean,
    val preferProcessedSource: Boolean,
    val profiles: Map<AiAudioSource, AiSourceProfile>,
)

object AiSourceProfileDefaults {
    val profiles: Map<AiAudioSource, AiSourceProfile> = mapOf(
        AiAudioSource.MIC to AiSourceProfile(
            makeupGain = 1.0f,
            agcTargetRms = 3000f,
            agcMaxGain = 6f,
            agcNoiseFloorRms = 120f,
            noiseGateRms = 300,
            expanderRatio = 5.5f,
            expanderOpenSnr = 7.0f,
            expanderMinGain = 0.003f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        AiAudioSource.DEFAULT to AiSourceProfile(
            makeupGain = 1.0f,
            agcTargetRms = 2600f,
            agcMaxGain = 3f,
            agcNoiseFloorRms = 180f,
            noiseGateRms = 500,
            expanderRatio = 5.5f,
            expanderOpenSnr = 7.0f,
            expanderMinGain = 0.003f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        AiAudioSource.VOICE_RECOGNITION to AiSourceProfile(
            makeupGain = 1.8f,
            agcTargetRms = 4500f,
            agcMaxGain = 10f,
            agcNoiseFloorRms = 100f,
            noiseGateRms = 200,
            expanderRatio = 2.2f,
            expanderOpenSnr = 3.2f,
            expanderMinGain = 0.08f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        AiAudioSource.VOICE_COMMUNICATION to AiSourceProfile(
            makeupGain = 1.6f,
            agcTargetRms = 3000f,
            agcMaxGain = 4.0f,
            agcNoiseFloorRms = 140f,
            noiseGateRms = 160,
            expanderRatio = 2.8f,
            expanderOpenSnr = 3.8f,
            expanderMinGain = 0.05f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        AiAudioSource.CAMCORDER to AiSourceProfile(
            makeupGain = 1.2f,
            agcTargetRms = 3500f,
            agcMaxGain = 8f,
            agcNoiseFloorRms = 80f,
            noiseGateRms = 150,
            expanderRatio = 4.0f,
            expanderOpenSnr = 5.5f,
            expanderMinGain = 0.01f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        AiAudioSource.VOICE_CALL to AiSourceProfile(
            makeupGain = 1.5f,
            agcTargetRms = 4000f,
            agcMaxGain = 10f,
            agcNoiseFloorRms = 60f,
            noiseGateRms = 80,
            expanderRatio = 4.0f,
            expanderOpenSnr = 5.5f,
            expanderMinGain = 0.01f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
    )

    val settings = AiSourceProfileSettings(
        useSourceProfile = true,
        preferProcessedSource = true,
        profiles = profiles,
    )
}
