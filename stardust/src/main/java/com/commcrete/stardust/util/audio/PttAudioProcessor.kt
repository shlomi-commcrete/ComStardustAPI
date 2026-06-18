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
    private var rnNoiseProcessor: RnNoiseProcessor? = null
    private var dynamicsFilter: DynamicsProcessingFilter? = null
    private var agcFilter: AGCFilter? = null
    private var lpf: LowPassFilter? = null

    // ── Tuning knobs derived from the current profile ─────────────────────

    /** RNNoise wet/dry mix in `[0,1]` (1 = full clean, 0 = bypass). */
    private var rnNoiseMix: Float = 1f

    /** RNNoise max-attenuation linear floor (0 = disabled). */
    private var rnNoiseAttenFloor: Float = 0f

    /** Post-AGC make-up multiplier (1 = passthrough). */
    private var filterAiGain: Float = 1f
    private var filterAiGainSoftSat: Boolean = false

    // ── Cache invalidation ────────────────────────────────────────────────

    /** Sample rate the currently-built chain runs at. `-1` = no chain yet. */
    @Volatile private var currentFilterRate: Int = -1

    /** Profile the currently-built chain was built for. */
    @Volatile private var currentFilterKey: AiRecorderProfile? = null

    @Volatile private var filtersBuilt: Boolean = false

    // ── Diagnostic logging dedupe (per flow / encoder type) ───────────────

    private val lastLoggedFilterSignatureByFlow: MutableMap<String, String> = mutableMapOf()
    private val lastLoggedChunkShapeByFlow: MutableMap<String, String> = mutableMapOf()

    // ──────────────────────────────────────────────────────────────────────
    // Per-device-type DSP profile registry
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Per-device-type DSP profile presets keyed by [RecordingDeviceType].
     * Each device type can have multiple presets for user selection (one
     * marked `isActive` at a time is the one [process] picks up via
     * [resolveActiveProfile]).
     *
     * Seeded eagerly with [getAiRecorderDefaultProfilePreset] for every
     * known device type. Persisted overrides loaded later via
     * [loadProfiles] (called from `RecorderUtils.init`).
     *
     * Currently one preset per device type, expandable for user-selectable
     * options without API churn.
     */
    private val profileMap: MutableMap<RecordingDeviceType, List<AiRecorderProfile>> = mutableMapOf()

    /**
     * Build the built-in default DSP preset for [recordingDeviceType].
     * Pulls per-stage defaults from each `*Config.getDefault(deviceType)`
     * so per-device tuning lives in one place (the config classes) rather
     * than scattered across the SDK.
     */
    fun getAiRecorderDefaultProfilePreset(recordingDeviceType: RecordingDeviceType): AiRecorderProfile {
        return AiRecorderProfile(
            title = "Default",
            isActive = true,
            lowPass = LowPassConfig.getDefault(recordingDeviceType),
            notch = NotchConfig.getDefault(recordingDeviceType),
            rnNoise = RnNoiseConfig.getDefault(recordingDeviceType),
            agc = AGCConfig.getDefault(recordingDeviceType),
            dynamics = DynamicsConfig.getDefault(recordingDeviceType),
            highPass = HighPassConfig.getDefault(recordingDeviceType),
        )
    }

    /** All presets registered for [recordingDeviceType], or null if none. */
    fun getAiRecorderProfiles(recordingDeviceType: RecordingDeviceType): List<AiRecorderProfile>? =
        profileMap[recordingDeviceType]

    /** The active preset for [recordingDeviceType], or null if none / none active. */
    fun getAiActiveRecorderProfile(recordingDeviceType: RecordingDeviceType): AiRecorderProfile? =
        profileMap[recordingDeviceType]?.find { it.isActive }

    /**
     * Update (or add) the full preset list for [recordingDeviceType] and
     * persist the flattened view to [SharedPreferencesUtil].
     *
     * Persistence model: SharedPreferences stores a flat
     * `Map<RecordingDeviceType, AiRecorderProfile>` (one preset per
     * device type), so the flatten step picks the first preset per type.
     * Multi-preset history lives only in-memory until the storage schema
     * is upgraded.
     */
    fun setAiRecorderProfiles(
        context: Context,
        recordingDeviceType: RecordingDeviceType,
        profiles: List<AiRecorderProfile>,
    ) {
        profileMap[recordingDeviceType] = profiles
        val flatMap = profileMap.flatMap { (type, profs) ->
            profs.map { type to it }
        }.toMap()
        SharedPreferencesUtil.setAiRecorderProfiles(context, flatMap)
    }

    /**
     * Convenience: update a single preset at [presetIndex] (or append as
     * a new preset when [presetIndex] is past the end) and persist.
     */
    fun setAiRecorderProfile(
        context: Context,
        recordingDeviceType: RecordingDeviceType,
        profile: AiRecorderProfile,
        presetIndex: Int = 0,
    ) {
        val profiles = profileMap[recordingDeviceType]?.toMutableList() ?: mutableListOf()
        if (presetIndex < profiles.size) {
            profiles[presetIndex] = profile
        } else {
            profiles.add(profile)
        }
        setAiRecorderProfiles(context, recordingDeviceType, profiles)
    }

    /**
     * Overlay persisted profiles onto the in-memory defaults. Call once
     * during SDK init (from `RecorderUtils.init`) so any device type the
     * host has saved overrides for picks them up. Device types with no
     * saved entry keep their built-in defaults seeded in [profileMap].
     */
    fun loadProfiles(context: Context) {
        val saved = SharedPreferencesUtil.getAiRecorderProfiles(context)
        // Group loaded profiles by device type to support multiple presets.
        saved.entries.groupBy { it.key }.forEach { (type, entries) ->
            profileMap[type] = entries.map { it.value }
        }
    }

    /** Active preset for [deviceType] — picked up by [process] internally. */
    private fun resolveActiveProfile(deviceType: RecordingDeviceType): AiRecorderProfile? =
        profileMap[deviceType]?.firstOrNull { it.isActive }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Run [pcmArray] through the full DSP chain (when [applyFilters] is
     * `true`) and resample to [targetRate] when [nativeRate] differs.
     *
     * **Filter-skip semantics — DSP is bypassed entirely (only the
     * resample step runs) when ANY of the following holds:**
     *  - [applyFilters] is `false` (caller has already filtered).
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
     * @param applyFilters    set `false` when the caller already filtered
     *                        the chunk (e.g. the test feeder runs its own
     *                        per-chunk chain) — this skips DSP and only
     *                        does the resample step.
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
        profile: AiRecorderProfile?,
        applyFilters: Boolean = true,
        flowKey: String = DEFAULT_FLOW_KEY,
        chunkIndex: Int = 0,
        chunkDurationMs: Int? = null,
    ): ShortArray {
        if (applyFilters) logFilterSignature(flowKey, chunkIndex, profile)
        logChunkShape(flowKey, chunkIndex, pcmArray.size, nativeRate, chunkDurationMs)

        // DSP runs only when ALL three conditions hold. A `null` profile
        // OR an inactive profile (`isActive == false`) is treated the same
        // as `applyFilters = false` — full bypass, just resample if needed.
        // No defaults are substituted; the profile is authoritative.
        val shouldFilter = applyFilters && profile != null && profile.isActive
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
     * for [deviceType] internally via [resolveActiveProfile], then forwards
     * to the main [process] entry point.
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
        applyFilters: Boolean = true,
        flowKey: String = DEFAULT_FLOW_KEY,
        chunkIndex: Int = 0,
        chunkDurationMs: Int? = null,
    ): ShortArray = process(
        pcmArray = pcmArray,
        nativeRate = nativeRate,
        targetRate = targetRate,
        profile = resolveActiveProfile(deviceType),
        applyFilters = applyFilters,
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
     */
    fun reset() {
        runCatching { rnNoiseProcessor?.release() }
            .onFailure { Log.w(TAG, "RnNoise release failed", it) }
        rnNoiseProcessor = null
        hpf = null
        declickFilter = null
        notchFilter = null
        dynamicsFilter = null
        agcFilter = null
        lpf = null
        rnNoiseMix = 1f
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

    private fun applyFilterChain(chunk: ShortArray, sampleRate: Int, profile: AiRecorderProfile) {
        ensureFiltersBuilt(sampleRate, profile)
        // HP first: kills HVAC / traffic / thump rumble before declick or
        // notch try to detect transients (a thump can fool a declicker)
        // and before RNNoise spends spectral budget on sub-voice content.
        hpf?.processInPlace(chunk)
        declickFilter?.processInPlace(chunk)
        notchFilter?.processInPlace(chunk)
        rnNoiseProcessor?.let { proc ->
            // Optional wet/dry blend + magnitude floor — same math as
            // AudioFeederEngine.LiveFilterChain.softenRnNoise so the test
            // feeder and live recording stay bit-comparable.
            val needsBlend = rnNoiseMix < 1f || rnNoiseAttenFloor > 0f
            val dry = if (needsBlend) chunk.copyOf() else null
            proc.process(chunk, chunk.size)
            if (dry != null) softenRnNoise(chunk, dry, rnNoiseMix, rnNoiseAttenFloor)
        }
        dynamicsFilter?.processInPlace(chunk)
        agcFilter?.processInPlace(chunk)
        if (filterAiGain != 1f) {
            if (filterAiGainSoftSat) AudioDsp.applyAiGainSoftSatInPlace(chunk, filterAiGain)
            else AudioDsp.applyAiGainInPlace(chunk, filterAiGain)
        }
        lpf?.processInPlace(chunk)
    }

    private fun ensureFiltersBuilt(sampleRate: Int, profile: AiRecorderProfile) {
        if (filtersBuilt && currentFilterRate == sampleRate && currentFilterKey == profile) return
        synchronized(this) {
            if (filtersBuilt && currentFilterRate == sampleRate && currentFilterKey == profile) return
            if (filtersBuilt) {
                Log.i(
                    TAG,
                    "Filter chain changed (rate=${currentFilterRate}Hz→${sampleRate}Hz / profile=${currentFilterKey?.title}→${profile.title}) — rebuilding",
                )
                releaseFiltersInternal()
            }
            currentFilterRate = sampleRate
            currentFilterKey = profile

            val rn: RnNoiseConfig? = profile.rnNoise?.takeIf { it.enabled }
            val dp: DynamicsConfig? = profile.dynamics?.takeIf { it.enabled }
            val lp: LowPassConfig? = profile.lowPass?.takeIf { it.enabled }
            val hp: HighPassConfig? = profile.highPass?.takeIf { it.enabled }
            val notch: NotchConfig? = profile.notch?.takeIf { it.enabled }
            val agc: AGCConfig? = profile.agc?.takeIf { it.enabled }

            hpf = hp?.let {
                HighPassFilter(
                    sampleRateHz = sampleRate,
                    cutoffHz = it.cutoffHz,
                    rollOffDbPerOctave = it.rollOffDbPerOctave,
                )
            }
            declickFilter = null
            notchFilter = notch?.let { NotchFilter(sampleRate, it.resolveBands()) }
            rnNoiseProcessor = rn?.let { RnNoiseProcessor().apply { init(sampleRate) } }
            rnNoiseMix = rn?.mixClamped ?: 1f
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
        dynamicsFilter = null
        agcFilter = null
        lpf = null
        rnNoiseMix = 1f
        rnNoiseAttenFloor = 0f
        filterAiGain = 1f
        filterAiGainSoftSat = false
        currentFilterRate = -1
        currentFilterKey = null
        filtersBuilt = false
    }

    /**
     * Identical math to `AudioFeederEngine.LiveFilterChain.softenRnNoise`:
     * clamp each cleaned sample's magnitude to be at least [floor] of the
     * dry sample's magnitude, then linearly blend cleaned vs. dry by [mix].
     */
    private fun softenRnNoise(wet: ShortArray, dry: ShortArray, mix: Float, floor: Float) {
        val n = minOf(wet.size, dry.size)
        for (i in 0 until n) {
            val d = dry[i].toInt()
            var w = wet[i].toInt()
            if (floor > 0f && d != 0) {
                val absDry = if (d < 0) -d else d
                val minAbs = (absDry * floor).toInt()
                val absWet = if (w < 0) -w else w
                if (absWet < minAbs) {
                    val sign = when {
                        w > 0 -> 1
                        w < 0 -> -1
                        d >= 0 -> 1
                        else -> -1
                    }
                    w = sign * minAbs
                }
            }
            val blended = if (mix >= 1f) w else (w * mix + d * (1f - mix)).toInt()
            wet[i] = blended.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Diagnostic logging
    // ──────────────────────────────────────────────────────────────────────

    private fun logFilterSignature(flowKey: String, chunkIndex: Int, profile: AiRecorderProfile?) {
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

    private fun buildFilterSignature(profile: AiRecorderProfile?): String {
        if (profile == null) return "preset=<none>; filters=[]"
        // An inactive profile is treated as a full DSP bypass by [process]
        // — surface that explicitly in the log instead of listing enabled
        // stages, which would otherwise wrongly suggest that those stages
        // are running on the audio.
        if (!profile.isActive) {
            return "preset=${profile.title}; active=false; filters=[] (profile inactive — DSP bypassed)"
        }
        val enabledFilters = mutableListOf<String>()
        profile.highPass?.takeIf { it.enabled }?.let {
            enabledFilters.add("highPass(cutoff=${it.cutoffHz}, rollOff=${it.rollOffDbPerOctave})")
        }
        profile.lowPass?.takeIf { it.enabled }?.let {
            enabledFilters.add("lowPass(cutoff=${it.cutoffHz}, rollOff=${it.rollOffDbPerOctave})")
        }
        profile.notch?.takeIf { it.enabled }?.let {
            enabledFilters.add("notch(harmonics=${it.harmonics.size})")
        }
        profile.rnNoise?.takeIf { it.enabled }?.let {
            enabledFilters.add("rnNoise(mix=${it.mix}, maxAttenDb=${it.maxAttenuationDb})")
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
        return "preset=${profile.title}; active=true; filters=[${enabledFilters.joinToString()}]"
    }
}

