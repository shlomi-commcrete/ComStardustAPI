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
/**
 * Capture sample rates supported by the audio pipeline.
 * Matches PCM2900C ADC rates (8, 11.025, 16, 22.05, 32, 44.1, 48 kHz)
 * plus standard Android AudioRecord rates.
 */
enum class CaptureRate(val hz: Int) {
    RATE_8K(8_000),
    RATE_11K(11_025),
    RATE_16K(16_000),
    RATE_22K(22_050),
    RATE_24K(24_000),
    RATE_32K(32_000),
    RATE_44K(44_100),
    RATE_48K(48_000),
}

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
    val codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
    val captureRate: CaptureRate = CaptureRate.RATE_48K,
    val audioSource: AiAudioSource? = null,
    val inputGainPercent: Float? = null,
    val lowPass: LowPassConfig? = null,
    val notch: NotchConfig? = null,
    val rnNoise: RnNoiseConfig? = null,
    val rnNoiseMinRateHz: CaptureRate = CaptureRate.RATE_48K,
    val agc: AGCConfig? = null,
    val dynamics: DynamicsConfig? = null,
    val highPass: HighPassConfig? = null,
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
