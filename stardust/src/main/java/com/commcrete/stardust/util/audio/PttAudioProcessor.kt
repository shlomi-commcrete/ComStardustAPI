package com.commcrete.stardust.util.audio

import android.content.Context
import android.util.Log
import com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor
import com.commcrete.stardust.util.SharedPreferencesUtil
import timber.log.Timber

/**
 * Single source of truth for **pre-encode** PCM processing on the PTT path.
 *
 * Owns the full DSP filter chain, the resample-to-encoder-rate step, AND
 * the per-device-type DSP profile registry so `PttSendManager` (encode
 * pipeline) and `RecorderUtils` (recording lifecycle / device routing)
 * can stay focused on their own concerns.
 *
 * Pipeline (per chunk):
 * ```
 * raw PCM @ nativeRate
 *   ─► HPF              (sub-voice rumble: HVAC, traffic, thump)
 *   ─► Declick          (transient artefact suppression)
 *   ─► Notch            (mains-hum harmonics)
 *   ─► RNNoise          (ML denoise + optional wet/dry blend & floor)
 *   ─► DynamicsProcessor (multiband compression / voice focus)
 *   ─► AGC              (level normalisation)
 *   ─► AI-gain          (post-AGC make-up, optional soft saturation)
 *   ─► LPF              (band limit + anti-alias for the upcoming resample)
 *   ─► resampleLinear → targetRate (24 kHz for AI, 8 kHz for CODEC2)
 * ```
 *
 * Stateful: each filter holds per-stream history (biquad memory, RNNoise
 * denoise state, etc.). The chain is built lazily on the first call, and
 * **rebuilt** when [process] is invoked with a different sample rate or
 * profile. Call [reset] between sessions (e.g. from `PttSendManager.restart`)
 * to release native RNNoise resources and force a fresh build on the next
 * chunk.
 *
 * Thread safety: `ensureFiltersBuilt` is `synchronized`; `process` itself
 * is intended to run from a single audio thread per session (the
 * codec-mutex serialised session job in `PttSendManager`). Concurrent
 * processing of two chunks against the same processor instance is **not**
 * supported.
 */
object PttAudioProcessor {

    private const val TAG = "PttAudioProcessor"

    /** Sample rate the AI tokenizer encoder consumes. */
    const val AI_TARGET_SAMPLE_RATE: Int = 24_000

    /** Sample rate the Codec2 encoder consumes. */
    const val CODEC2_TARGET_SAMPLE_RATE: Int = 8_000

    /** Default flow key for diagnostic logging when caller doesn't tag. */
    private const val DEFAULT_FLOW_KEY = "default"

    // ── Filter chain instances ────────────────────────────────────────────

    private var hpf: HighPassFilter? = null
    private var declickFilter: DeclickFilter? = null
    private var notchFilter: NotchFilter? = null
    private var adaptiveNotchDetector: AdaptiveNotchDetector? = null
    /** Static bands resolved from the NotchConfig (kept for merging with adaptive). */
    private var staticNotchBands: List<NotchFilter.Band> = emptyList()
    private var spectralSubtractionFilter: SpectralSubtractionFilter? = null
    private var rnNoiseProcessor: RnNoiseProcessor? = null
    private var dynamicsFilter: DynamicsProcessingFilter? = null
    private var agcFilter: AGCFilter? = null
    private var lpf: LowPassFilter? = null

    // ── Tuning knobs derived from the current profile ─────────────────────

    /** RNNoise per-frame RMS floor, linear (0 = disabled). */
    private var rnNoiseAttenFloor: Float = 0f

    /** Post-AGC make-up multiplier (1 = passthrough). */
    private var filterAiGain: Float = 1f
    private var filterAiGainSoftSat: Boolean = false

    // ── Cache invalidation ────────────────────────────────────────────────

    /** Sample rate the currently-built chain runs at. `-1` = no chain yet. */
    @Volatile private var currentFilterRate: Int = -1

    /** Profile the currently-built chain was built for. */
    @Volatile private var currentFilterKey: RecorderProfile? = null

    @Volatile private var filtersBuilt: Boolean = false

    // ── Diagnostic logging dedupe (per flow / encoder type) ───────────────

    private val lastLoggedFilterSignatureByFlow: MutableMap<String, String> = mutableMapOf()
    private val lastLoggedChunkShapeByFlow: MutableMap<String, String> = mutableMapOf()

    // ──────────────────────────────────────────────────────────────────────
    // Per-device-type DSP profile registry
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Per-(device, codec) DSP profile presets keyed by [ProfileKey].
     * Each key can have multiple presets (user-selectable); exactly one
     * is `isActive = true` at a time. The active profile is resolved at
     * recording time via [resolveActiveProfile].
     *
     * Seeded lazily with [getDefaultProfile] on first access per key.
     * Persisted overrides loaded via [loadProfiles] (called from
     * `RecorderUtils.init`).
     */
    private val profileMap: MutableMap<ProfileKey, List<RecorderProfile>> = mutableMapOf()

    /**
     * Build the built-in default DSP preset for the given device +
     * codec combination. Each codec type gets filter configs optimized
     * for its encoder:
     *
     *  - **AI**: capture at 48 kHz (RNNoise/spectral-sub at full bandwidth),
     *    resample to 24 kHz. Notch for 48 kHz tones.
     *  - **Codec2**: capture at 8 kHz (hardware decimation), no resample.
     *    Minimal filters — notch for 8 kHz-mode tones only.
     */
    /**
     * Build all three [RecordingEnvironmentPreset] profiles for the given
     * device + codec combination. Each preset escalates noise reduction
     * aggressiveness while keeping the signal path minimal.
     */
    fun getDefaultProfiles(
        deviceType: RecordingDeviceType,
        codeType: RecorderUtils.CODE_TYPE,
    ): List<RecorderProfile> = RecordingEnvironmentPreset.entries.map { preset ->
        getDefaultProfile(deviceType, codeType, preset)
    }

    /**
     * Build one default profile for the given device + codec + preset.
     */
    fun getDefaultProfile(
        deviceType: RecordingDeviceType,
        codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
        preset: RecordingEnvironmentPreset = RecordingEnvironmentPreset.DEFAULT,
    ): RecorderProfile = when (deviceType) {

        // ════════════════════════════════════════════════════════════════
        // JBOX_INTERNAL — digital USB audio from PCM2900.
        // Noise: clicks + device tones. NO broadband noise.
        // RNNoise harmful. Use surgical filters only.
        // AI: capture 24kHz (close to encoder target, minimal resample).
        // Codec2: capture 8kHz native (hardware decimation, notch 1kHz).
        // ════════════════════════════════════════════════════════════════
        RecordingDeviceType.JBOX_INTERNAL -> when (codeType) {
            RecorderUtils.CODE_TYPE.AI -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.AI,
                captureRate = CaptureRate.RATE_32K, // closest PCM2900C rate above 24kHz
                highPass = HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f),
                declick = DeclickConfig(enabled = true),
                notch = NotchConfig(enabled = true, harmonics = listOf(
                    NotchConfig.Harmonic(375f, 150f),
                )),
                spectralSubtraction = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> SpectralSubtractionConfig(enabled = true)
                    RecordingEnvironmentPreset.NOISY -> SpectralSubtractionConfig(
                        enabled = true, overSubtractionFactor = 1.5f, spectralFloor = 0.03f,
                    )
                    RecordingEnvironmentPreset.EXTREME -> SpectralSubtractionConfig(
                        enabled = true, overSubtractionFactor = 2.0f, spectralFloor = 0.02f,
                    )
                },
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
            RecorderUtils.CODE_TYPE.CODEC2 -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.CODEC2,
                captureRate = CaptureRate.RATE_8K,
                highPass = HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f),
                declick = DeclickConfig(enabled = true),
                notch = NotchConfig(enabled = true, harmonics = listOf(
                    NotchConfig.Harmonic(1000f, 200f),
                )),
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
        }

        // ════════════════════════════════════════════════════════════════
        // JBOX_EXTERNAL — digital USB audio, noisier signal, no clicks.
        // Same digital path as INTERNAL but external mic picks up more
        // ambient noise. Still digital — no RNNoise.
        // AI: capture 24kHz. Codec2: capture 8kHz, notch 1kHz.
        // ════════════════════════════════════════════════════════════════
        RecordingDeviceType.JBOX_EXTERNAL -> when (codeType) {
            RecorderUtils.CODE_TYPE.AI -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.AI,
                captureRate = CaptureRate.RATE_32K, // closest PCM2900C rate above 24kHz
                highPass = HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f),
                notch = NotchConfig(enabled = true, harmonics = listOf(
                    NotchConfig.Harmonic(375f, 150f),
                )),
                spectralSubtraction = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> SpectralSubtractionConfig(enabled = true)
                    RecordingEnvironmentPreset.NOISY -> SpectralSubtractionConfig(
                        enabled = true, overSubtractionFactor = 1.5f, spectralFloor = 0.03f,
                        adaptiveAggressiveness = true,
                    )
                    RecordingEnvironmentPreset.EXTREME -> SpectralSubtractionConfig(
                        enabled = true, overSubtractionFactor = 2.0f, spectralFloor = 0.02f,
                        adaptiveAggressiveness = true,
                    )
                },
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
            RecorderUtils.CODE_TYPE.CODEC2 -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.CODEC2,
                captureRate = CaptureRate.RATE_8K,
                highPass = HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f),
                notch = NotchConfig(enabled = true, harmonics = listOf(
                    NotchConfig.Harmonic(1000f, 200f),
                )),
                spectralSubtraction = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> null
                    RecordingEnvironmentPreset.NOISY -> SpectralSubtractionConfig(
                        enabled = true, overSubtractionFactor = 1.5f, spectralFloor = 0.03f,
                        adaptiveAggressiveness = true,
                    )
                    RecordingEnvironmentPreset.EXTREME -> SpectralSubtractionConfig(
                        enabled = true, overSubtractionFactor = 2.0f, spectralFloor = 0.02f,
                        adaptiveAggressiveness = true,
                    )
                },
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
        }

        // ════════════════════════════════════════════════════════════════
        // PHONE_MIC — built-in phone microphone. Analog audio.
        // Capture at 48kHz. RNNoise appropriate (analog broadband noise).
        // NOISY/EXTREME: RNNoise floor loosens progressively.
        // ════════════════════════════════════════════════════════════════
        RecordingDeviceType.PHONE_MIC -> when (codeType) {
            RecorderUtils.CODE_TYPE.AI -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.AI,
                captureRate = CaptureRate.RATE_48K,
                highPass = HighPassConfig(enabled = true, cutoffHz = 100f, rollOffDbPerOctave = 24f),
                declick = DeclickConfig(enabled = true),
                rnNoise = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> RnNoiseConfig(enabled = true, maxAttenuationDb = -6f)
                    RecordingEnvironmentPreset.NOISY -> RnNoiseConfig(enabled = true, maxAttenuationDb = -10f)
                    RecordingEnvironmentPreset.EXTREME -> RnNoiseConfig(enabled = true, maxAttenuationDb = Float.NEGATIVE_INFINITY)
                },
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
            RecorderUtils.CODE_TYPE.CODEC2 -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.CODEC2,
                captureRate = CaptureRate.RATE_48K, // need 48kHz for RNNoise, resample to 8kHz
                highPass = HighPassConfig(enabled = true, cutoffHz = 100f, rollOffDbPerOctave = 24f),
                declick = DeclickConfig(enabled = true),
                rnNoise = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> RnNoiseConfig(enabled = true, maxAttenuationDb = -6f)
                    RecordingEnvironmentPreset.NOISY -> RnNoiseConfig(enabled = true, maxAttenuationDb = -10f)
                    RecordingEnvironmentPreset.EXTREME -> RnNoiseConfig(enabled = true, maxAttenuationDb = Float.NEGATIVE_INFINITY)
                },
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
        }

        // ════════════════════════════════════════════════════════════════
        // BLE_MIC — Bluetooth SCO mic (car, headset). Analog noise.
        // VOICE_COMMUNICATION required for SCO routing.
        // Request 48kHz for RNNoise; fallback to spectral subtraction
        // if device only supports 8/16kHz.
        // ════════════════════════════════════════════════════════════════
        RecordingDeviceType.BLE_MIC -> when (codeType) {
            RecorderUtils.CODE_TYPE.AI -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.AI,
                captureRate = CaptureRate.RATE_48K,
                audioSource = com.commcrete.stardust.AiAudioSource.VOICE_COMMUNICATION,
                highPass = HighPassConfig(enabled = true, cutoffHz = 150f, rollOffDbPerOctave = 24f),
                declick = DeclickConfig(enabled = true),
                rnNoise = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> RnNoiseConfig(enabled = true, maxAttenuationDb = -6f)
                    RecordingEnvironmentPreset.NOISY -> RnNoiseConfig(enabled = true, maxAttenuationDb = -10f)
                    RecordingEnvironmentPreset.EXTREME -> RnNoiseConfig(enabled = true, maxAttenuationDb = Float.NEGATIVE_INFINITY)
                },
                rnNoiseMinRateHz = CaptureRate.RATE_48K,
                rnNoiseFallback = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> SpectralSubtractionConfig(
                        enabled = true, silenceThresholdDbFs = -40f, noiseLearnAlpha = 0.2f,
                        overSubtractionFactor = 1.5f, spectralFloor = 0.05f, minNoiseFrames = 2,
                        adaptiveAggressiveness = true,
                    )
                    RecordingEnvironmentPreset.NOISY -> SpectralSubtractionConfig(
                        enabled = true, silenceThresholdDbFs = -38f, noiseLearnAlpha = 0.25f,
                        overSubtractionFactor = 2.0f, spectralFloor = 0.04f, minNoiseFrames = 2,
                        adaptiveAggressiveness = true,
                    )
                    RecordingEnvironmentPreset.EXTREME -> SpectralSubtractionConfig(
                        enabled = true, silenceThresholdDbFs = -35f, noiseLearnAlpha = 0.3f,
                        overSubtractionFactor = 2.5f, spectralFloor = 0.02f, minNoiseFrames = 1,
                        adaptiveAggressiveness = true,
                    )
                },
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.20f, attackMs = 5f, releaseMs = 300f,
                    maxGainDb = 15f, minGainDb = -8f, noiseGateLevel = 0.008f,
                ),
            )
            RecorderUtils.CODE_TYPE.CODEC2 -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.CODEC2,
                captureRate = CaptureRate.RATE_48K,
                audioSource = com.commcrete.stardust.AiAudioSource.VOICE_COMMUNICATION,
                highPass = HighPassConfig(enabled = true, cutoffHz = 150f, rollOffDbPerOctave = 24f),
                declick = DeclickConfig(enabled = true),
                rnNoise = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> RnNoiseConfig(enabled = true, maxAttenuationDb = -6f)
                    RecordingEnvironmentPreset.NOISY -> RnNoiseConfig(enabled = true, maxAttenuationDb = -10f)
                    RecordingEnvironmentPreset.EXTREME -> RnNoiseConfig(enabled = true, maxAttenuationDb = Float.NEGATIVE_INFINITY)
                },
                rnNoiseMinRateHz = CaptureRate.RATE_48K,
                rnNoiseFallback = when (preset) {
                    RecordingEnvironmentPreset.DEFAULT -> SpectralSubtractionConfig(
                        enabled = true, silenceThresholdDbFs = -40f, noiseLearnAlpha = 0.2f,
                        overSubtractionFactor = 1.5f, spectralFloor = 0.04f, minNoiseFrames = 2,
                        adaptiveAggressiveness = true,
                    )
                    RecordingEnvironmentPreset.NOISY -> SpectralSubtractionConfig(
                        enabled = true, silenceThresholdDbFs = -38f, noiseLearnAlpha = 0.25f,
                        overSubtractionFactor = 2.5f, spectralFloor = 0.03f, minNoiseFrames = 2,
                        adaptiveAggressiveness = true,
                    )
                    RecordingEnvironmentPreset.EXTREME -> SpectralSubtractionConfig(
                        enabled = true, silenceThresholdDbFs = -35f, noiseLearnAlpha = 0.3f,
                        overSubtractionFactor = 3.0f, spectralFloor = 0.02f, minNoiseFrames = 1,
                        adaptiveAggressiveness = true,
                    )
                },
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.20f, attackMs = 5f, releaseMs = 300f,
                    maxGainDb = 15f, minGainDb = -8f, noiseGateLevel = 0.008f,
                ),
            )
        }

        // ════════════════════════════════════════════════════════════════
        // OTHER — unknown device. Conservative: HPF + AGC only.
        // ════════════════════════════════════════════════════════════════
        RecordingDeviceType.OTHER -> when (codeType) {
            RecorderUtils.CODE_TYPE.AI -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.AI,
                captureRate = CaptureRate.RATE_48K,
                highPass = HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f),
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
            RecorderUtils.CODE_TYPE.CODEC2 -> RecorderProfile(
                preset = preset,
                codeType = RecorderUtils.CODE_TYPE.CODEC2,
                captureRate = CaptureRate.RATE_8K,
                highPass = HighPassConfig(enabled = true, cutoffHz = 80f, rollOffDbPerOctave = 24f),
                agc = AGCConfig(
                    enabled = true, targetLevel = 0.18f, attackMs = 5f, releaseMs = 400f,
                    maxGainDb = 12f, minGainDb = -8f, noiseGateLevel = 0.004f,
                ),
            )
        }
    }

    /** @deprecated Use [getDefaultProfile] with explicit codeType. */
    @Deprecated("Use getDefaultProfile(deviceType, codeType)",
        replaceWith = ReplaceWith("getDefaultProfile(recordingDeviceType)"))
    fun getAiRecorderDefaultProfilePreset(recordingDeviceType: RecordingDeviceType): RecorderProfile =
        getDefaultProfile(recordingDeviceType, RecorderUtils.CODE_TYPE.AI)

    /** All presets registered for the given key, or null if none. */
    fun getProfiles(key: ProfileKey): List<RecorderProfile>? =
        profileMap[key]

    /** All presets for a device + codec combination. */
    fun getProfiles(deviceType: RecordingDeviceType, codeType: RecorderUtils.CODE_TYPE): List<RecorderProfile>? =
        profileMap[ProfileKey(deviceType, codeType)]

    /** @deprecated Use [getProfiles] with explicit codeType. */
    @Deprecated("Use getProfiles(deviceType, codeType)",
        replaceWith = ReplaceWith("getProfiles(recordingDeviceType, RecorderUtils.CODE_TYPE.AI)"))
    fun getAiRecorderProfiles(recordingDeviceType: RecordingDeviceType): List<RecorderProfile>? =
        getProfiles(recordingDeviceType, RecorderUtils.CODE_TYPE.AI)

    /**
     * The profile matching the active [RecordingEnvironmentPreset] from
     * SharedPreferences, or `null` if the preset is `null` (all
     * profiles disabled — no filters applied).
     */
    fun getActiveProfile(key: ProfileKey): RecorderProfile? {
        val activePreset = SharedPreferencesUtil.getActiveRecordingEnvironmentPreset(
            com.commcrete.stardust.util.DataManager.context
        ) ?: return null
        return profileMap[key]?.firstOrNull { it.preset == activePreset }
    }

    /** The active profile for a device + codec combination. */
    fun getActiveProfile(deviceType: RecordingDeviceType, codeType: RecorderUtils.CODE_TYPE): RecorderProfile? =
        getActiveProfile(ProfileKey(deviceType, codeType))

    /** @deprecated Use [getActiveProfile] with explicit codeType. */
    @Deprecated("Use getActiveProfile(deviceType, codeType)",
        replaceWith = ReplaceWith("getActiveProfile(recordingDeviceType, RecorderUtils.CODE_TYPE.AI)"))
    fun getAiActiveRecorderProfile(recordingDeviceType: RecordingDeviceType): RecorderProfile? =
        getActiveProfile(recordingDeviceType, RecorderUtils.CODE_TYPE.AI)

    /**
     * Update (or add) the full preset list for [key] and persist.
     */
    fun setProfiles(
        context: Context,
        key: ProfileKey,
        profiles: List<RecorderProfile>,
    ) {
        profileMap[key] = profiles
        persistAllProfiles(context)
    }

    /** @deprecated Use [setProfiles] with a [ProfileKey]. */
    @Deprecated("Use setProfiles(context, ProfileKey, profiles)")
    fun setAiRecorderProfiles(
        context: Context,
        recordingDeviceType: RecordingDeviceType,
        profiles: List<RecorderProfile>,
    ) {
        setProfiles(context, ProfileKey(recordingDeviceType, RecorderUtils.CODE_TYPE.AI), profiles)
    }

    /**
     * Convenience: update a single preset at [presetIndex] (or append as
     * a new preset when [presetIndex] is past the end) and persist.
     */
    fun setProfile(
        context: Context,
        key: ProfileKey,
        profile: RecorderProfile,
        presetIndex: Int = 0,
    ) {
        val profiles = profileMap[key]?.toMutableList() ?: mutableListOf()
        if (presetIndex < profiles.size) {
            profiles[presetIndex] = profile
        } else {
            profiles.add(profile)
        }
        setProfiles(context, key, profiles)
    }

    /** @deprecated Use [setProfile] with a [ProfileKey]. */
    @Deprecated("Use setProfile(context, ProfileKey, profile, presetIndex)")
    fun setAiRecorderProfile(
        context: Context,
        recordingDeviceType: RecordingDeviceType,
        profile: RecorderProfile,
        presetIndex: Int = 0,
    ) {
        setProfile(
            context,
            ProfileKey(recordingDeviceType, profile.codeType),
            profile,
            presetIndex,
        )
    }

    /**
     * Overlay persisted profiles onto the in-memory defaults. Call once
     * during SDK init (from `RecorderUtils.init`) so any saved overrides
     * are picked up. Keys with no saved entry keep their built-in
     * defaults seeded lazily by [resolveActiveProfile].
     *
     * Backward-compatible: old SharedPreferences entries keyed by device
     * type alone (no codec type) are imported as AI profiles.
     */
    fun loadProfiles(context: Context) {
        val saved = SharedPreferencesUtil.getRecorderProfiles(context)
        saved.forEach { (key, profile) ->
            profileMap[key] = listOf(profile)
        }
    }

    /**
     * Active preset for [deviceType] + [codeType] — picked up by
     * [process] internally. Returns `null` when:
     *  - The active [RecordingEnvironmentPreset] in SharedPreferences is `null`
     *    (user disabled all profiles → no filters).
     *  - No profile with the matching preset is registered for the key.
     *
     * `null` tells [process] to skip all filters and only resample.
     */
    fun resolveActiveProfile(
        deviceType: RecordingDeviceType,
        codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
    ): RecorderProfile? {
        val activePreset = SharedPreferencesUtil.getActiveRecordingEnvironmentPreset(
            com.commcrete.stardust.util.DataManager.context
        ) ?: return null
        val key = ProfileKey(deviceType, codeType)
        return profileMap[key]?.firstOrNull { it.preset == activePreset }
    }

    /**
     * Resolve the audio source (Android `MediaRecorder.AudioSource` int)
     * for a recording session. Checks the active profile first; if the
     * profile doesn't specify an audio source, returns `null` so the
     * caller can fall back to the legacy SharedPreferences setting.
     */
    fun resolveAudioSource(
        deviceType: RecordingDeviceType,
        codeType: RecorderUtils.CODE_TYPE,
    ): Int? = resolveActiveProfile(deviceType, codeType)?.resolveAudioSourceInt()

    /**
     * Resolve the requested capture sample rate from the active profile.
     * Returns `null` if no profile is registered, so the caller can fall
     * back to the recorder's default rate.
     */
    fun resolveRequestedSampleRate(
        deviceType: RecordingDeviceType,
        codeType: RecorderUtils.CODE_TYPE,
    ): Int? = resolveActiveProfile(deviceType, codeType)?.captureRate?.hz

    /**
     * Resolve the input gain (linear multiplier) from the active profile.
     * Returns `null` if no profile is registered or the profile doesn't
     * specify a gain, so the caller can fall back to the legacy
     * SharedPreferences setting.
     */
    fun resolveInputGain(
        deviceType: RecordingDeviceType,
        codeType: RecorderUtils.CODE_TYPE,
    ): Float? = resolveActiveProfile(deviceType, codeType)
        ?.inputGainPercent
        ?.let { it / 100f }

    /** Persist the full profileMap to SharedPreferences. */
    private fun persistAllProfiles(context: Context) {
        val flat = profileMap.flatMap { (key, profs) ->
            profs.map { key to it }
        }.toMap()
        SharedPreferencesUtil.setRecorderProfiles(context, flat)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Run [pcmArray] through the full DSP chain  and resample to [targetRate] when [nativeRate] differs.
     *
     * **Filter-skip semantics — DSP is bypassed entirely (only the
     * resample step runs) when ANY of the following holds:**
     *  - [profile] is `null`.
     *  - [profile]`.isActive` is `false`.
     *
     * In addition, **within** an active profile each individual filter
     * stage is gated by both its presence (`profile.X != null`) AND its
     * `enabled` flag — a stage that is `null` OR `enabled = false` is
     * NOT built, NOT applied, and NOT replaced by any default. The
     * profile is the single source of truth for what runs.
     *
     * @param pcmArray        raw mono 16-bit PCM at [nativeRate].
     * @param nativeRate      sample rate of [pcmArray]; the chain is built
     *                        / rebuilt for this rate on the first chunk
     *                        and whenever it changes.
     * @param targetRate      encoder-side rate. Use [AI_TARGET_SAMPLE_RATE]
     *                        or [CODEC2_TARGET_SAMPLE_RATE].
     * @param profile         per-device DSP configuration. `null` or a
     *                        profile with `isActive = false` runs no
     *                        filters even when [applyFilters] is `true`
     *                        — see "Filter-skip semantics" above.
     * @param flowKey         identifier for diagnostic dedupe; pass
     *                        `"AI"` / `"CODEC2"` so the log lines per flow
     *                        are independent.
     * @param chunkIndex      zero-based index of this chunk in the current
     *                        emission. Chunks 0/1 always log; subsequent
     *                        chunks only log when something changes.
     * @param chunkDurationMs optional declared duration of [pcmArray] in
     *                        ms; when set, an "expected sample count"
     *                        diagnostic is included so size mismatches
     *                        surface clearly.
     * @return the processed PCM ready for the encoder. May be the same
     *         array instance as [pcmArray] when [applyFilters] is `false`
     *         and no resample is needed; otherwise a fresh array.
     */
    fun process(
        pcmArray: ShortArray,
        nativeRate: Int,
        targetRate: Int,
        profile: RecorderProfile?,
        flowKey: String = DEFAULT_FLOW_KEY,
        chunkIndex: Int = 0,
        chunkDurationMs: Int? = null,
    ): ShortArray {
        logFilterSignature(flowKey, chunkIndex, profile)
        logChunkShape(flowKey, chunkIndex, pcmArray.size, nativeRate, chunkDurationMs)

        // profile is non-null only when resolveActiveProfile found a
        // matching preset in SharedPreferences. null = no filters.
        val shouldFilter = profile != null
        val filtered: ShortArray = if (shouldFilter) {
            val mutable = pcmArray.copyOf()
            applyFilterChain(mutable, nativeRate, profile!!)
            mutable
        } else {
            pcmArray
        }
        return if (nativeRate != targetRate) {
            AudioDsp.resampleLinear(filtered, nativeRate, targetRate)
        } else {
            filtered
        }
    }

    /**
     * Device-aware overload of [process]: resolves the active DSP profile
     * for [deviceType] + [codeType] internally via [resolveActiveProfile],
     * then forwards to the main [process] entry point.
     *
     * Use this from recording paths that already infer the device route
     * (e.g. `RecorderUtils.preprocessChunkForEncoding`) so neither the
     * profile registry nor the resolve step has to leak outside this
     * processor.
     */
    fun process(
        pcmArray: ShortArray,
        nativeRate: Int,
        targetRate: Int,
        deviceType: RecordingDeviceType,
        codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
        flowKey: String = DEFAULT_FLOW_KEY,
        chunkIndex: Int = 0,
        chunkDurationMs: Int? = null,
    ): ShortArray = process(
        pcmArray = pcmArray,
        nativeRate = nativeRate,
        targetRate = targetRate,
        profile = resolveActiveProfile(deviceType, codeType),
        flowKey = flowKey,
        chunkIndex = chunkIndex,
        chunkDurationMs = chunkDurationMs,
    )

    /**
     * Tear down the filter chain so the next [process] call rebuilds it
     * from scratch. Releases native RNNoise resources and clears the
     * dedupe-logging cache. Safe to call multiple times. Call from
     * `PttSendManager.restart` so a new session starts with fresh
     * per-stream state (no biquad ringing across recordings, no stale
     * RNNoise frame history).
     *
     * Synchronized on `this` — same lock as [applyFilterChain] and
     * [ensureFiltersBuilt] — so a concurrent [process] call on the audio
     * thread finishes its current chunk before the native state is torn
     * down.
     */
    fun reset() = synchronized(this) {
        runCatching { rnNoiseProcessor?.release() }
            .onFailure { Log.w(TAG, "RnNoise release failed", it) }
        rnNoiseProcessor = null
        hpf = null
        declickFilter = null
        notchFilter = null
        adaptiveNotchDetector = null
        staticNotchBands = emptyList()
        spectralSubtractionFilter = null
        dynamicsFilter = null
        agcFilter = null
        lpf = null
        rnNoiseAttenFloor = 0f
        filterAiGain = 1f
        filterAiGainSoftSat = false
        currentFilterRate = -1
        currentFilterKey = null
        filtersBuilt = false
        lastLoggedFilterSignatureByFlow.clear()
        lastLoggedChunkShapeByFlow.clear()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Filter chain
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Optional debug hook: called after each filter stage with a snapshot
     * of the chunk at that point. Set by the test feeder to save
     * per-filter WAV artifacts; `null` during live recording (zero
     * overhead — the snapshot copy is skipped entirely).
     *
     * Callback signature: `(stepIndex: Int, filterName: String, chunk: ShortArray) -> Unit`
     */
    @Volatile
    var onFilterStepDebug: ((Int, String, ShortArray) -> Unit)? = null

    /**
     * Run the full DSP chain on [chunk] in place. Synchronized on `this`
     * so [reset] (called from the restart thread) cannot destroy native
     * filter state while the audio thread is mid-chunk.
     */
    private fun applyFilterChain(chunk: ShortArray, sampleRate: Int, profile: RecorderProfile) = synchronized(this) {
        ensureFiltersBuilt(sampleRate, profile)
        val dbg = onFilterStepDebug
        var step = 0

        fun debugSnapshot(name: String) {
            dbg?.invoke(step++, name, chunk.copyOf())
        }

        declickFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("declick")
        }
        notchFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("notch")
        }
        adaptiveNotchDetector?.let { detector ->
            val newBands = detector.detectAndTrack(chunk)
            if (newBands != null) {
                val merged = staticNotchBands + newBands
                notchFilter?.configure(bands = merged)
            }
        }
        spectralSubtractionFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("spectral_sub")
        }
        rnNoiseProcessor?.let { proc ->
            val needsFloor = rnNoiseAttenFloor > 0f
            val dry = if (needsFloor) chunk.copyOf() else null
            proc.process(chunk, chunk.size)
            if (dry != null) softenRnNoise(chunk, dry, rnNoiseAttenFloor)
            debugSnapshot("rnnoise")
        }
        dynamicsFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("dynamics")
        }
        agcFilter?.let {
            it.processInPlace(chunk)
            debugSnapshot("agc")
        }
        if (filterAiGain != 1f) {
            if (filterAiGainSoftSat) AudioDsp.applyAiGainSoftSatInPlace(chunk, filterAiGain)
            else AudioDsp.applyAiGainInPlace(chunk, filterAiGain)
            debugSnapshot("ai_gain")
        }
        hpf?.let {
            it.processInPlace(chunk)
            debugSnapshot("hpf")
        }
        lpf?.let {
            it.processInPlace(chunk)
            debugSnapshot("lpf")
        }
    }

    private fun ensureFiltersBuilt(sampleRate: Int, profile: RecorderProfile) {
        if (filtersBuilt && currentFilterRate == sampleRate && currentFilterKey == profile) return
        synchronized(this) {
            if (filtersBuilt && currentFilterRate == sampleRate && currentFilterKey == profile) return
            if (filtersBuilt) {
                Log.i(
                    TAG,
                    "Filter chain changed (rate=${currentFilterRate}Hz→${sampleRate}Hz / profile=${currentFilterKey?.preset}→${profile.preset}) — rebuilding",
                )
                releaseFiltersInternal()
            }
            currentFilterRate = sampleRate
            currentFilterKey = profile

            // RNNoise + fallback resolution: if the capture rate is below
            // the profile's rnNoiseMinRateHz, skip RNNoise and use the
            // fallback (spectral subtraction) instead — if configured.
            val rnNoiseUsable = profile.rnNoise?.takeIf { it.enabled }
                ?.takeIf { profile.rnNoiseMinRateHz.hz <= 0 || sampleRate >= profile.rnNoiseMinRateHz.hz }
            val useFallback = profile.rnNoise?.enabled == true && rnNoiseUsable == null
            val fallbackSs = if (useFallback) profile.rnNoiseFallback?.takeIf { it.enabled } else null

            val rn: RnNoiseConfig? = rnNoiseUsable
            val dp: DynamicsConfig? = profile.dynamics?.takeIf { it.enabled }
            val lp: LowPassConfig? = profile.lowPass?.takeIf { it.enabled }
            val hp: HighPassConfig? = profile.highPass?.takeIf { it.enabled }
            val notch: NotchConfig? = profile.notch?.takeIf { it.enabled }
            val agc: AGCConfig? = profile.agc?.takeIf { it.enabled }
            val declick: DeclickConfig? = profile.declick?.takeIf { it.enabled }
            // Spectral subtraction: use the profile's own config, OR the
            // RNNoise fallback if RNNoise was skipped due to low capture rate.
            val ss: SpectralSubtractionConfig? =
                profile.spectralSubtraction?.takeIf { it.enabled } ?: fallbackSs

            hpf = hp?.let {
                HighPassFilter(
                    sampleRateHz = sampleRate,
                    cutoffHz = it.cutoffHz,
                    rollOffDbPerOctave = it.rollOffDbPerOctave,
                )
            }
            declickFilter = declick?.let {
                DeclickFilter(sampleRateHz = sampleRate, config = it)
            }
            val resolvedBands = notch?.resolveBands() ?: emptyList()
            staticNotchBands = resolvedBands
            notchFilter = notch?.let { NotchFilter(sampleRate, resolvedBands) }
            adaptiveNotchDetector = notch?.adaptive?.let {
                AdaptiveNotchDetector(sampleRate, it)
            }
            spectralSubtractionFilter = ss?.let {
                SpectralSubtractionFilter(sampleRateHz = sampleRate, config = it)
            }
            rnNoiseProcessor = rn?.let { RnNoiseProcessor().apply { init(sampleRate) } }
            rnNoiseAttenFloor = rn?.attenuationFloorLin ?: 0f
            dynamicsFilter = dp?.let { DynamicsProcessingFilter(sampleRateHz = sampleRate, config = it) }
            agcFilter = agc?.let {
                AGCFilter(
                    sampleRateHz = sampleRate,
                    targetLevel = it.targetLevel,
                    attackMs = it.attackMs,
                    releaseMs = it.releaseMs,
                    maxGainDb = it.maxGainDb,
                    minGainDb = it.minGainDb,
                    noiseGateLevel = it.noiseGateLevel,
                )
            }
            lpf = lp?.let {
                LowPassFilter(
                    sampleRateHz = sampleRate,
                    cutoffHz = it.cutoffHz,
                    rollOffDbPerOctave = it.rollOffDbPerOctave,
                )
            }
            filtersBuilt = true
        }
    }

    /**
     * Free filter instances (and native RNNoise state) without touching
     * the dedupe-logging cache. Used by the rebuild path inside
     * [ensureFiltersBuilt] where we *want* the next chunk's signature line
     * to log as a transition rather than repeat from scratch.
     */
    private fun releaseFiltersInternal() {
        runCatching { rnNoiseProcessor?.release() }
            .onFailure { Log.w(TAG, "RnNoise release failed", it) }
        rnNoiseProcessor = null
        hpf = null
        declickFilter = null
        notchFilter = null
        adaptiveNotchDetector = null
        staticNotchBands = emptyList()
        spectralSubtractionFilter = null
        dynamicsFilter = null
        agcFilter = null
        lpf = null
        rnNoiseAttenFloor = 0f
        filterAiGain = 1f
        filterAiGainSoftSat = false
        currentFilterRate = -1
        currentFilterKey = null
        filtersBuilt = false
    }

    /**
     * Per-frame RMS floor for RNNoise output.
     *
     * Replaces the old per-sample magnitude clamp + wet/dry blend which had
     * two bugs:
     *
     *  1. **Per-sample floor** compared individual sample magnitudes between
     *     wet and dry. A sample's magnitude says nothing about voice vs.
     *     noise — the floor re-introduced noise with spectral artifacts.
     *
     *  2. **Wet/dry blend** (`mix < 1`) combined two signals with different
     *     phase spectra (RNNoise reshapes phase). The sum produced
     *     frequency-dependent cancellation = comb-filtering = echo.
     *
     * The fix: no blend (always `mix = 1`), and a **per-frame RMS floor**
     * that uniformly scales a frame up when RNNoise suppresses it too much.
     * This preserves RNNoise's spectral decisions (which frequencies to
     * keep/cut) while preventing overall energy collapse (hollowness).
     *
     * @param wet   RNNoise output (modified in place).
     * @param dry   copy of the input saved before RNNoise ran.
     * @param floor linear RMS floor ratio in `(0, 1]`. If the wet frame's
     *              RMS falls below `dry_rms * floor`, the entire frame is
     *              scaled up so `wet_rms == dry_rms * floor`. `0` disables.
     */
    private fun softenRnNoise(wet: ShortArray, dry: ShortArray, floor: Float) {
        if (floor <= 0f) return
        val n = minOf(wet.size, dry.size)
        if (n == 0) return

        // Process in 480-sample frames (RNNoise's native frame size) so the
        // floor decision aligns with the granularity RNNoise operates at.
        val frameSize = 480
        var off = 0
        while (off < n) {
            val end = minOf(off + frameSize, n)
            val len = end - off

            // Compute RMS of wet and dry for this frame.
            var wetSumSq = 0.0
            var drySumSq = 0.0
            for (i in off until end) {
                val w = wet[i].toDouble()
                val d = dry[i].toDouble()
                wetSumSq += w * w
                drySumSq += d * d
            }
            val wetRms = kotlin.math.sqrt(wetSumSq / len)
            val dryRms = kotlin.math.sqrt(drySumSq / len)

            // If wet RMS is below the floor threshold, scale up uniformly.
            val minRms = dryRms * floor
            if (wetRms > 0.0 && wetRms < minRms) {
                val gain = (minRms / wetRms).toFloat()
                for (i in off until end) {
                    val scaled = (wet[i] * gain).toInt()
                    wet[i] = scaled.coerceIn(
                        Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                    ).toShort()
                }
            }
            off = end
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Diagnostic logging
    // ──────────────────────────────────────────────────────────────────────

    private fun logFilterSignature(flowKey: String, chunkIndex: Int, profile: RecorderProfile?) {
        val signature = buildFilterSignature(profile)
        val previous = lastLoggedFilterSignatureByFlow[flowKey]
        if (chunkIndex == 0 || previous != signature) {
            Log.d(TAG, "$flowKey filter preset for chunk=$chunkIndex -> $signature")
            lastLoggedFilterSignatureByFlow[flowKey] = signature
        }
    }

    private fun logChunkShape(
        flowKey: String,
        chunkIndex: Int,
        actualSize: Int,
        nativeRate: Int,
        chunkDurationMs: Int?,
    ) {
        val expectedSamples = chunkDurationMs?.let { (nativeRate * it) / 1000 }
        val shape = if (expectedSamples != null) {
            "rate=${nativeRate}Hz size=$actualSize expected=$expectedSamples"
        } else {
            "rate=${nativeRate}Hz size=$actualSize"
        }
        val previous = lastLoggedChunkShapeByFlow[flowKey]
        val mismatch = expectedSamples != null && actualSize != expectedSamples
        if (chunkIndex <= 1 || previous != shape || mismatch) {
            val message = "$flowKey chunk shape chunk=$chunkIndex -> $shape"
            if (mismatch) Timber.w(message) else Timber.d(message)
            lastLoggedChunkShapeByFlow[flowKey] = shape
        }
    }

    private fun buildFilterSignature(profile: RecorderProfile?): String {
        if (profile == null) return "preset=<none>; filters=[] (no active preset — DSP bypassed)"
        val enabledFilters = mutableListOf<String>()
        profile.highPass?.takeIf { it.enabled }?.let {
            enabledFilters.add("highPass(cutoff=${it.cutoffHz}, rollOff=${it.rollOffDbPerOctave})")
        }
        profile.declick?.takeIf { it.enabled }?.let {
            enabledFilters.add(
                "declick(thr=${it.thresholdMad}xMAD, minPk=${it.minPeakDbFs}dBFS, maxRun=${it.maxTickSamples}, dDet=${it.useDerivativeDetection})"
            )
        }
        profile.lowPass?.takeIf { it.enabled }?.let {
            enabledFilters.add("lowPass(cutoff=${it.cutoffHz}, rollOff=${it.rollOffDbPerOctave})")
        }
        profile.notch?.takeIf { it.enabled }?.let {
            val adaptiveTag = if (it.adaptive != null) "+adaptive" else ""
            enabledFilters.add("notch(harmonics=${it.harmonics?.size ?: 0}$adaptiveTag)")
        }
        profile.spectralSubtraction?.takeIf { it.enabled }?.let {
            enabledFilters.add(it.describe())
        }
        profile.rnNoise?.takeIf { it.enabled }?.let {
            enabledFilters.add("rnNoise(maxAttenDb=${it.maxAttenuationDb})")
        }
        profile.agc?.takeIf { it.enabled }?.let {
            enabledFilters.add(
                "agc(target=${it.targetLevel}, atk=${it.attackMs}, rel=${it.releaseMs}, +${it.maxGainDb}/${it.minGainDb}, gate=${it.noiseGateLevel})"
            )
        }
        profile.dynamics?.takeIf { it.enabled }?.let {
            enabledFilters.add(
                "dynamics(in=${it.inputGainDb}, b0Gate=${it.band0.noiseGateDb}, b1Gate=${it.band1.noiseGateDb}, b2Gate=${it.band2.noiseGateDb}, lim=${it.limiter.thresholdDb})"
            )
        }
        return "preset=${profile.preset}; active=true; filters=[${enabledFilters.joinToString()}]"
    }
}

