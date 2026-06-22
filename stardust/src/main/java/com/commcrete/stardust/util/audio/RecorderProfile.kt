package com.commcrete.stardust.util.audio

import com.commcrete.stardust.AiAudioSource

/**
 * DSP profile for the pre-encode filter chain in [PttAudioProcessor].
 *
 * Each profile is scoped to a **device type + codec type** pair. The same
 * physical device (e.g. JBOX_INTERNAL) may have different optimal filter
 * settings for AI (WavTokenizer, 48 kHz capture → 24 kHz encode) vs.
 * Codec2 (8 kHz native capture, no resample).
 *
 * ## Profile registry
 *
 * [PttAudioProcessor] maintains a map keyed by [ProfileKey]:
 * ```
 * profileMap: Map<ProfileKey, List<RecorderProfile>>
 * ```
 * Multiple presets can exist per key; exactly one is `isActive = true`.
 * The active profile is resolved at recording time via
 * [PttAudioProcessor.resolveActiveProfile].
 *
 * ## Sample rate
 *
 * [requestedSampleRateHz] tells the audio recorder what sample rate to
 * request from the capture device. This is NOT the encoder's target rate
 * (which is fixed per codec: 24 kHz for AI, 8 kHz for Codec2). The
 * filter chain runs at the capture rate; [PttAudioProcessor] resamples
 * to the encoder target rate after filtering.
 *
 * Examples:
 *  - AI + JBOX_INTERNAL: capture 48 kHz (RNNoise needs 48 kHz),
 *    filter at 48 kHz, resample to 24 kHz.
 *  - Codec2 + JBOX_INTERNAL: capture 8 kHz (PCM2900 hardware decimation),
 *    filter at 8 kHz, no resample needed.
 */
/**
 * Environment preset. Determines the noise reduction aggressiveness.
 * Each [RecordingDeviceType] + [RecorderUtils.CODE_TYPE] combination
 * can have up to three presets; exactly one is `isActive = true`.
 */
enum class RecordingEnvironmentPreset {
    /** Clean / low-noise environment (office, quiet room). */
    DEFAULT,
    /** Moderate noise (car, restaurant, street). */
    NOISY,
    /** Extreme noise (plane, train, factory floor). */
    EXTREME,
}

data class RecorderProfile(
    val preset: RecordingEnvironmentPreset = RecordingEnvironmentPreset.DEFAULT,
    /**
     * Codec type this profile is designed for. Determines which default
     * filter configs are applied and which encoder target rate is used.
     */
    val codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
    /**
     * Sample rate (Hz) to request from the audio capture device. The
     * filter chain runs at this rate. [PttAudioProcessor] resamples to
     * the encoder's target rate (24 kHz for AI, 8 kHz for Codec2) after
     * filtering. Set to the encoder target rate to skip the resample.
     */
    val requestedSampleRateHz: Int = 48_000,
    /**
     * Android audio source to use for recording. `null` = fall back to
     * the per-codec SharedPreferences setting (legacy behavior:
     * `SharedPreferencesUtil.getAIAudioSource` for AI,
     * `SharedPreferencesUtil.getCodecAudioSource` for Codec2).
     */
    val audioSource: AiAudioSource? = null,
    /**
     * Input gain as a percentage (100 = unity, 50 = -6 dB, 200 = +6 dB).
     * Applied to raw PCM before the filter chain. `null` = fall back to
     * the per-codec SharedPreferences setting (legacy behavior:
     * `SharedPreferencesUtil.getAIGain` for AI,
     * `SharedPreferencesUtil.getCodecGain` for Codec2).
     */
    val inputGainPercent: Float? = null,
    val lowPass: LowPassConfig? = null,
    val notch: NotchConfig? = null,
    val rnNoise: RnNoiseConfig? = null,
    /**
     * Minimum capture rate (Hz) required for [rnNoise] to run. If the
     * actual capture rate is below this value, RNNoise is skipped and
     * [rnNoiseFallback] is used instead (if non-null and enabled).
     *
     * RNNoise operates at 48 kHz internally. When capture rate < 48 kHz,
     * RnNoiseProcessor upsamples → processes → downsamples, which adds
     * artifacts on band-limited signals (e.g. 16 kHz BLE). Set this to
     * `48000` to skip RNNoise when the capture rate is below 48 kHz.
     *
     * Default `0` = always run RNNoise regardless of capture rate.
     */
    val rnNoiseMinRateHz: Int = 0,
    /**
     * Fallback noise reduction when [rnNoise] is skipped due to
     * [rnNoiseMinRateHz]. `null` = no fallback (no noise reduction if
     * RNNoise can't run). If the user disables this, no fallback is
     * applied — the audio passes through without noise reduction.
     */
    val rnNoiseFallback: SpectralSubtractionConfig? = null,
    val agc: AGCConfig? = null,
    val dynamics: DynamicsConfig? = null,
    val highPass: HighPassConfig? = null,
    val declick: DeclickConfig? = null,
    val spectralSubtraction: SpectralSubtractionConfig? = null,
) {
    /**
     * Resolve [audioSource] to the Android `MediaRecorder.AudioSource` int,
     * or `null` if not set (caller should fall back to SharedPreferences).
     */
    fun resolveAudioSourceInt(): Int? = audioSource?.androidSource
}

/**
 * Composite key for the profile registry: one active profile per
 * (device type, codec type) pair.
 */
data class ProfileKey(
    val deviceType: RecordingDeviceType,
    val codeType: RecorderUtils.CODE_TYPE,
) {
    /** Serialization key for SharedPreferences: `"JBOX_INTERNAL:AI"`. */
    fun toStorageKey(): String = "${deviceType.name}:${codeType.name}"

    companion object {
        /** Deserialize from the `"DEVICE:CODEC"` format used in SharedPreferences. */
        fun fromStorageKey(key: String): ProfileKey? {
            val parts = key.split(":", limit = 2)
            if (parts.size != 2) return null
            val deviceType = runCatching { RecordingDeviceType.valueOf(parts[0]) }.getOrNull()
                ?: return null
            val codeType = runCatching { RecorderUtils.CODE_TYPE.valueOf(parts[1]) }.getOrNull()
                ?: return null
            return ProfileKey(deviceType, codeType)
        }

        /**
         * Attempt to parse as new composite key (`"DEVICE:CODEC"`); if that
         * fails, fall back to legacy format (`"DEVICE"` alone, defaulting
         * to AI). Enables transparent migration of old SharedPreferences
         * data.
         */
        fun fromStorageKeyCompat(key: String): ProfileKey? {
            fromStorageKey(key)?.let { return it }
            // Legacy: key is just the device type name (pre-codeType era).
            val deviceType = runCatching { RecordingDeviceType.valueOf(key) }.getOrNull()
                ?: return null
            return ProfileKey(deviceType, RecorderUtils.CODE_TYPE.AI)
        }
    }
}

enum class RecordingDeviceType {
    OTHER,
    JBOX_INTERNAL,
    JBOX_EXTERNAL,
    /** Built-in phone microphone (not routed through jbox). */
    PHONE_MIC,
    /** Bluetooth SCO microphone (car kit, BLE headset). */
    BLE_MIC,
}
