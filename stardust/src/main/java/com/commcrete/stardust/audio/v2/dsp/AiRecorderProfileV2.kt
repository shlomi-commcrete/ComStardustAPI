package com.commcrete.stardust.audio.v2.dsp

import com.commcrete.stardust.util.audio.RecordingEnvironmentPreset
import com.commcrete.stardust.util.audio.RecorderProfile
import com.commcrete.stardust.util.audio.RecordingDeviceType

/**
 * v2 DSP profile — envelope around the existing
 * [com.commcrete.stardust.util.audio.RecorderProfile] PLUS the new
 * [MakeupGainConfig] stage that the legacy profile didn't carry.
 *
 * Kept as a separate type so v2 doesn't have to modify
 * [com.commcrete.stardust.util.audio.RecorderProfile] (per "don't
 * change existing files" constraint). When v1 is eventually retired
 * the two can be merged.
 *
 * @property base       seven stage configs (HPF, declick, notch, LPF,
 *                      RNNoise, dynamics, AGC) — handled by the
 *                      existing v1 filter classes.
 * @property makeupGain new post-AGC make-up stage. `null` ⇒ skipped.
 *                      Defaulted per-device-type via
 *                      [MakeupGainConfig.getDefault].
 */
data class AiRecorderProfileV2(
    val base: RecorderProfile,
    val makeupGain: MakeupGainConfig? = null,
) {

    /** Convenience: environment preset (delegates to [base]). */
    val environmentPreset: RecordingEnvironmentPreset get() = base.preset

    /** Convenience: profile preset (delegates to [base]). */
    val preset: com.commcrete.stardust.util.audio.RecordingEnvironmentPreset get() = base.preset

    companion object {

        /**
         * Build a v2 envelope around an existing v1 profile, picking
         * the per-device-type default [MakeupGainConfig] when none was
         * configured. Used by [com.commcrete.stardust.audio.v2.dsp.PttAudioProcessorV2]
         * when the caller doesn't supply a v2 profile but the v1
         * registry has one.
         */
        fun fromV1(
            base: RecorderProfile,
            deviceType: RecordingDeviceType,
        ): AiRecorderProfileV2 = AiRecorderProfileV2(
            base = base,
            makeupGain = MakeupGainConfig.getDefault(deviceType),
        )

        /** A no-op profile — every stage disabled. Useful in tests. */
        fun bypass(): AiRecorderProfileV2 = AiRecorderProfileV2(
            base = RecorderProfile(preset = com.commcrete.stardust.util.audio.RecordingEnvironmentPreset.DEFAULT),
            makeupGain = null,
        )
    }
}

