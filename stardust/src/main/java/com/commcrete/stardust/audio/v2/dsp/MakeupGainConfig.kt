package com.commcrete.stardust.audio.v2.dsp

import com.commcrete.stardust.util.audio.RecordingDeviceType

/**
 * Post-AGC make-up gain stage — final shaping before the encoder.
 *
 * v1 had two scattered implementations:
 *  - AI: [com.commcrete.stardust.ai.codec.AudioRecorderAI.processSamples]
 *    applies digital gain followed by a `tanh` soft-clip so harsh
 *    square-wave clipping is replaced by a rounded saturation.
 *  - CODEC2: [com.commcrete.stardust.util.audio.AudioRecorderCodec2.writeAudioDataToFile]
 *    multiplies by `getCodecGain / 100f` then `coerceIn(MIN, MAX)`.
 *    Hard-clip — produces aliasing on peaks but is cheaper.
 *
 * v2 lifts both into this sealed class so:
 *  - the choice lives in the [com.commcrete.stardust.audio.v2.dsp.AiRecorderProfileV2]
 *    DSP profile, NOT in the recorder,
 *  - every codec can pick whichever policy suits its capture chain,
 *  - new shapers (e.g. polynomial waveshaper, lookahead limiter) can
 *    be added as new subclasses without touching the recorder.
 */
sealed class MakeupGainConfig {

    /** Whether this stage runs at all. `false` ⇒ bypassed. */
    abstract val enabled: Boolean

    /**
     * Linear pre-stage multiplier — `1.0f` = unity. Matches the
     * `gain` variable in the legacy `processSamples` (which derives
     * from `SharedPreferencesUtil.getAIGain / 100f`).
     */
    abstract val gainLinear: Float

    /**
     * Hard-clip after multiplication. Maps the legacy CODEC2
     * `coerceIn(Short.MIN_VALUE, Short.MAX_VALUE)` path. Cheap;
     * audibly harsh on peaks.
     */
    data class HardClip(
        override val enabled: Boolean = true,
        override val gainLinear: Float = 1.0f,
    ) : MakeupGainConfig()

    /**
     * Tanh-shaped soft-clip after multiplication. Maps the legacy AI
     * `processSamples` path.
     *
     * Behavior (per the legacy code's docstring):
     *  - |x| ≤ knee·FS → fully linear
     *  - |x| > knee·FS → asymptotes smoothly to ±FS via
     *    `knee + (1 − knee) · tanh((|x|/FS − knee) / (1 − knee))`
     *
     * @property knee  fraction of full-scale at which saturation
     *                 begins. `0.5f` matches the legacy `processSamples`
     *                 constant. Lower = more linear region; higher =
     *                 more headroom before shaping.
     */
    data class SoftClip(
        override val enabled: Boolean = true,
        override val gainLinear: Float = 1.0f,
        val knee: Float = 0.5f,
    ) : MakeupGainConfig()

    companion object {
        /**
         * Per-device default. Mirrors the policy the legacy recorders
         * applied (AI used soft-clip; CODEC2 used hard-clip), but
         * generalised so any device type can pick either.
         *
         * JBOX hardware benefits from soft-clip because the USB
         * codec's fixed analog gain commonly pushes peaks above unity.
         * `OTHER` (phone built-in mic) keeps hard-clip as the
         * conservative default — overridable per profile.
         */
        fun getDefault(deviceType: RecordingDeviceType): MakeupGainConfig = when (deviceType) {
            RecordingDeviceType.JBOX_INTERNAL,
            RecordingDeviceType.JBOX_EXTERNAL -> SoftClip(gainLinear = 1.0f, knee = 0.5f)
            RecordingDeviceType.PHONE_MIC,
            RecordingDeviceType.BLE_MIC,
            RecordingDeviceType.OTHER -> HardClip(gainLinear = 1.0f)
        }
    }
}

