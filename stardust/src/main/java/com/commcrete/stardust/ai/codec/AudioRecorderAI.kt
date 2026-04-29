package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.example.chunkrecorder.DebugRawWavWriter
import com.example.chunkrecorder.NoiseProcessor
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt


/**
 * Records microphone audio and emits 500 ms PCM chunks written as standalone WAV files.
 *
 * Configuration:
 *  - sampleRate = 24000 Hz
 *  - mono
 *  - 16-bit PCM
 *  - chunk duration = 500 ms (12,000 samples, 24,000 bytes audio data)
 *
 * Usage:
 *  val recorder = AudioRecorder(filesDirProvider = { getExternalFilesDir("chunks") ?: filesDir })
 *  recorder.onChunkReady = { file, index -> ... }
 *  recorder.onError = { throwable -> ... }
 *  recorder.start()
 *  recorder.stop()
 */
class AudioRecorderAI(
    private val context: Context,
    private val chunkDurationMs: Long,
    private val filesDirProvider: () -> File,
    private val sampleRate: Int = 24_000,
    private val bitsPerSample: Int = 16,
    private val channels: Int = 1,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope : CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private var recordingThread: Thread? = null
) {

    // Callbacks
    var onChunkReady: ((pcmArray: ShortArray, chunkIndex: Int) -> Unit)? = null
    var onPartialFinalChunk: ((pcmArray: ShortArray, chunkIndex: Int) -> Unit)? = null
    var onStateChanged: ((recording: Boolean) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // Public state
    val isRecording: Boolean
        get() = job?.isActive == true

    // Internal
    private val running = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    private val bytesPerSample = bitsPerSample / 8
    private val samplesPerChunk = (sampleRate * chunkDurationMs / 1000.0).toInt() // 24,000
    private val bytesPerChunk = samplesPerChunk * bytesPerSample * channels

    /**
     * When true, every captured chunk is appended to a WAV file under
     * `Downloads/Stardust/` BEFORE [processSamples] is called — so the file
     * contains the raw, untouched mic signal for debugging.
     */
    var saveRawAudioToDownloads: Boolean = true

    /** Writer used when [saveRawAudioToDownloads] is enabled. */
    private val debugRawWriter = DebugRawWavWriter()

    /**
     * Additional debug writer for post-cleanup audio (after [processSamples],
     * before AI parsing callback). Saved with `cleaned` in file name.
     */
    private val debugCleanedWriter = DebugRawWavWriter()

    /**
     * Additional debug writer for post-AI-parsing audio (captured AFTER the
     * [onChunkReady] callback has returned, so it reflects any in-place
     * mutations performed by the AI parsing stage). Saved with `ai_parsed`
     * in file name.
     */
    private val debugAiParsedWriter = DebugRawWavWriter()

    // --- Audio cleaning configuration ---
    /** Enable Android platform NoiseSuppressor / AGC / AEC (only effective with VOICE_* sources). */
    var useHardwareEffects: Boolean = true
    /** Enable software DC offset removal (stateful across chunks). */
    var useDcRemoval: Boolean = true
    /** Enable software high-pass filter to remove low-frequency rumble. */
    var useHighPass: Boolean = true
    /** High-pass cutoff in Hz. */
    var highPassCutoffHz: Float = 80f
    /** Enable soft-clipping before quantization. */
    var useSoftClip: Boolean = true
    /** Enable noise gate (mutes residual silence between speech). */
    var useNoiseGate: Boolean = true
    /** RMS threshold (0..32767) below which audio is treated as silence. */
    var noiseGateThresholdRms: Int = 300
    /**
     * Optional pluggable neural / spectral noise suppressor (e.g. RNNoise, WebRTC NS).
     * Runs AFTER high-pass and BEFORE the noise gate, so the gate keys off the cleaned signal.
     * Set to null to disable. The processor is initialized in [recordLoop] (with [sampleRate])
     * and released in the `finally` block.
     */
    var noiseProcessor: NoiseProcessor? = null

    // ─── External mic / device routing ──────────────────────────────────────
    /**
     * Explicit input device for [AudioRecord] (`setPreferredDevice`). When null,
     * the recorder asks the system to pick "the best external mic available"
     * via [pickPreferredExternalInputDevice]. Set to a specific
     * [AudioDeviceInfo] to override (e.g. user picked one in your UI), or set
     * to `AudioDeviceInfo` of type `TYPE_BUILTIN_MIC` to force the built-in mic.
     *
     * Call [listInputDevices] to enumerate options.
     */
    var preferredInputDevice: AudioDeviceInfo? = null

    /**
     * If true and an external mic is active (USB / wired / BT), the platform
     * effects ([useHardwareEffects]) are silently bypassed. Most OEM NS/AGC/AEC
     * implementations are tuned for the built-in mic array geometry and DEGRADE
     * external mic signal (warbling artifacts, AGC pumping, false echo cancel).
     */
    var disableHardwareEffectsForExternalMic: Boolean = true

    /**
     * If true, BT SCO routing is enabled ONLY when the selected input device is
     * a BT_SCO mic. Without this, calling enableBluetoothSco() while using a
     * USB or built-in mic forces the system into 8 kHz narrowband for no
     * benefit and often hurts USB audio quality.
     */
    var enableScoOnlyForBluetoothInput: Boolean = true

    /**
     * Optional per-session gain boost applied to external mics (most external
     * mics are quieter at the diaphragm than the built-in array). 1.0f = no
     * change. 2.0f = +6 dB. Applied on top of [SharedPreferencesUtil.getAIGain].
     */
    var externalMicGainBoost: Float = 2.0f

    /**
     * Lowered noise-gate threshold for external mics (their noise floor is
     * typically lower; if we leave the gate at the built-in default, real
     * speech can be muted). Used only if [useNoiseGate] is true.
     */
    var noiseGateThresholdRmsExternal: Int = 30

    // ─── Software AGC / compressor ─────────────────────────────────────────
    /**
     * Enables a software AGC (automatic gain control) that tracks signal
     * energy and dynamically scales gain so that off-axis / dispersing speech
     * is brought up while close / loud speech is held below clipping.
     *
     * This is what fixes the symptom: "voice clear when spoken into mic, hard
     * to hear when user talks normally". A single static boost can't handle
     * both cases — AGC can.
     *
     * Runs AFTER static [externalMicGainBoost] and BEFORE soft-clip / gate.
     */
    var useSoftwareAgc: Boolean = true

    /**
     * Apply software AGC only when an external mic is the active input. The
     * built-in mic array already has hardware AGC and an OEM voice pipeline.
     */
    var softwareAgcOnlyForExternalMic: Boolean = true

    // AGC tuning (target / max / min / noise-floor / attack / release) lives in
    // [SourceProfile] now and is resolved per-source in [recordLoop].

    // ─── Per-AudioSource compensation ──────────────────────────────────────
    /**
     * Different [MediaRecorder.AudioSource] values feed the recorder very
     * different signals on the same hardware:
     *
     *  - [MediaRecorder.AudioSource.MIC] / `DEFAULT`: RAW, hot, NO OEM
     *    noise-suppression on most devices → "loud but noisy".
     *  - [MediaRecorder.AudioSource.VOICE_RECOGNITION]: cleaned by the OEM
     *    voice pipeline but typically attenuated → "clean but quiet".
     *  - [MediaRecorder.AudioSource.VOICE_COMMUNICATION]: cleaned + AEC,
     *    also quieter, sometimes narrow-banded.
     *
     * To get "clean AND loud" we apply a per-source profile that adjusts:
     *   - static makeup gain
     *   - AGC target / max gain
     *   - noise gate threshold
     *
     * Override any field via [sourceProfileOverrides] without changing the
     * defaults below.
     */
    data class SourceProfile(
        /** Extra static gain applied on top of [SharedPreferencesUtil.getAIGain]. */
        val makeupGain: Float,
        /** AGC target RMS (0..32767). Higher = louder output. */
        val agcTargetRms: Float,
        /** AGC maximum amplification. Higher = louder quiet input. */
        val agcMaxGain: Float,
        /**
         * Below this RMS the AGC freezes its gain (won't amplify pure hiss).
         * For pre-cleaned sources it's safe to lower this so quiet speech is
         * still pushed up.
         */
        val agcNoiseFloorRms: Float,
        /** Floor on AGC gain — used to ATTENUATE when the input is too hot. */
        val agcMinGain: Float,
        /** AGC attack (seconds). Smaller = faster gain reduction on loud peaks. */
        val agcAttackSec: Float,
        /** AGC release (seconds). Larger = smoother gain rise on quiet speech. */
        val agcReleaseSec: Float,
        /**
         * Noise gate threshold (RMS). For pre-cleaned sources we keep this
         * very low so the gate doesn't cut the quiet-but-clean speech.
         */
        val noiseGateRms: Int,
        // ─── Software downward expander (noise cancellation) ──────────────
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
    )

    /**
     * Defaults used when no override is supplied for a given source. Tuned to
     * solve the reported symptom: VOICE_RECOGNITION is amplified harder so
     * speech is audible; MIC / DEFAULT relies on AGC + gate to tame noise
     * without clipping near-field speech.
     */
    private val defaultSourceProfiles: Map<Int, SourceProfile> = mapOf(
        MediaRecorder.AudioSource.MIC to SourceProfile(
            makeupGain = 1.0f,
            agcTargetRms = 3000f,
            agcMaxGain = 6f,
            agcNoiseFloorRms = 120f,   // raw mic → don't pump up hiss
            agcMinGain = 0.25f,
            agcAttackSec = 0.010f,
            agcReleaseSec = 0.350f,
            noiseGateRms = 300,
            expanderRatio = 5.5f,
            expanderOpenSnr = 7.0f,
            expanderMinGain = 0.003f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        MediaRecorder.AudioSource.DEFAULT to SourceProfile(
            makeupGain = 1.0f,
            agcTargetRms = 2600f,
            agcMaxGain = 3f,
            agcNoiseFloorRms = 180f,
            agcMinGain = 0.25f,
            agcAttackSec = 0.010f,
            agcReleaseSec = 0.350f,
            noiseGateRms = 500,
            expanderRatio = 5.5f,
            expanderOpenSnr = 7.0f,
            expanderMinGain = 0.003f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        MediaRecorder.AudioSource.VOICE_RECOGNITION to SourceProfile(
            makeupGain = 1.8f,
            agcTargetRms = 4500f,
            agcMaxGain = 10f,
            agcNoiseFloorRms = 100f,
            agcMinGain = 0.25f,
            agcAttackSec = 0.010f,
            agcReleaseSec = 0.350f,
            noiseGateRms = 200,
            expanderRatio = 2.2f,
            expanderOpenSnr = 3.2f,
            expanderMinGain = 0.08f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to SourceProfile(
            makeupGain = 1.6f,
            agcTargetRms = 3000f,
            agcMaxGain = 4.0f,
            agcNoiseFloorRms = 140f,
            agcMinGain = 0.25f,
            agcAttackSec = 0.010f,
            agcReleaseSec = 0.350f,
            noiseGateRms = 160,
            expanderRatio = 2.8f,
            expanderOpenSnr = 3.8f,
            expanderMinGain = 0.05f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        MediaRecorder.AudioSource.CAMCORDER to SourceProfile(
            makeupGain = 1.2f,
            agcTargetRms = 3500f,
            agcMaxGain = 8f,
            agcNoiseFloorRms = 80f,
            agcMinGain = 0.25f,
            agcAttackSec = 0.010f,
            agcReleaseSec = 0.350f,
            noiseGateRms = 150,
            expanderRatio = 4.0f,
            expanderOpenSnr = 5.5f,
            expanderMinGain = 0.01f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
        MediaRecorder.AudioSource.VOICE_CALL to SourceProfile(
            makeupGain = 1.5f,
            agcTargetRms = 4000f,
            agcMaxGain = 10f,
            agcNoiseFloorRms = 60f,
            agcMinGain = 0.25f,
            agcAttackSec = 0.010f,
            agcReleaseSec = 0.350f,
            noiseGateRms = 80,
            expanderRatio = 4.0f,
            expanderOpenSnr = 5.5f,
            expanderMinGain = 0.01f,
            expanderAttackSec = 0.005f,
            expanderReleaseSec = 0.100f,
        ),
    )

    /** Per-source overrides; values here win over [defaultSourceProfiles]. */
    var sourceProfileOverrides: Map<Int, SourceProfile> = emptyMap()

    /** Master switch — set false to disable per-source compensation entirely. */
    var useSourceProfile: Boolean = true

    private fun resolveSourceProfile(source: Int): SourceProfile? {
        if (!useSourceProfile) return null
        return sourceProfileOverrides[source] ?: defaultSourceProfiles[source]
    }

    /**
     * When the configured AudioSource is the RAW [MediaRecorder.AudioSource.MIC]
     * or `DEFAULT`, transparently swap to [MediaRecorder.AudioSource.VOICE_RECOGNITION]
     * before opening the AudioRecord.
     *
     * Why: the Android audio HAL only runs the OEM voice pipeline (NS/AGC/EQ)
     * on `VOICE_*` sources. With `MIC`/`DEFAULT` you get the raw analog stream
     * — louder but noisier than anything we can clean in software. Upgrading
     * to `VOICE_RECOGNITION` is the single most effective noise reducer we
     * can apply, and the [SourceProfile] system makes up the level loss.
     *
     * Set to `false` if you specifically need the raw stream (e.g. for an
     * external DSP that does its own NS).
     */
    var preferProcessedSource: Boolean = true

    // ─── Software expander (downward, smooth, adaptive) ────────────────────
    /**
     * Replaces / augments the hard [useNoiseGate]. The expander attenuates
     * audio close to the estimated noise floor by a ratio (e.g. 1:4) — instead
     * of muting / passing through in binary, it smoothly ducks hiss while
     * letting speech come through unmodified. Far more effective at killing
     * constant background noise (fan, traffic, mic preamp hiss) than a gate.
     *
     * Runs AFTER the AGC and BEFORE soft-clip / quantize.
     */
    var useSoftwareExpander: Boolean = true

    // Expander tuning (ratio / openSnr / minGain / attack / release) lives in
    // [SourceProfile] now and is resolved per-source in [recordLoop].

    // --- Stateful DSP state (persist across chunks to avoid boundary clicks) ---
    private var dcEstimate: Float = 0f
    private val dcAlpha: Float = 0.995f
    private val highPass: OnePoleHighPass by lazy {
        OnePoleHighPass(highPassCutoffHz, sampleRate.toFloat())
    }
    private var gateHangoverRemaining: Int = 0
    private val gateHangoverSamples: Int by lazy { sampleRate / 5 } // ~200 ms

    /** Effective noise-gate threshold for the current session (chosen per device). */
    private var activeGateThresholdRms: Int = 300

    /** Software AGC instance for the current session; null when disabled. */
    private var agc: SoftwareAgc? = null

    /** Software downward expander for the current session; null when disabled. */
    private var expander: SoftwareExpander? = null

    // Active platform effects (released alongside AudioRecord)
    private val activeEffects: MutableList<AudioEffect> = mutableListOf()

    /**
     * True only when *this* recorder enabled BT SCO routing during the current
     * session. We track it so [disableBluetoothSco] does NOT clobber the
     * communication device / SCO state of unrelated parts of the app (which
     * was the root cause of "no sound at all" after stopping the recorder).
     */
    private var scoEnabledByUs: Boolean = false



    fun start() {
        Log.d("AudioRecorder", "Starting audio recorder")
        synchronized(this) {
            // Already running or cancelling
            if (job?.isActive == true) return

            job = scope.launch {
                onStateChanged?.invoke(true)
                try {
                    recordLoop()
                } catch (t: CancellationException) {
                    // normal cancellation — ignore
                } catch (t: Throwable) {
                    onError?.invoke(t)
                } finally {
                    onStateChanged?.invoke(false)
                }
            }
        }
    }


    fun stop() {
        synchronized(this) {
            job?.cancel()
        }
        // NOTE: Do NOT call disableBluetoothSco() here. The recordLoop's
        // finally{} block already restores routing IF this recorder enabled
        // it. Calling clearCommunicationDevice() unconditionally would silence
        // the rest of the app (was: "no sound at all" after recording).
    }

//    fun start() {
//        Log.d("AudioRecorder", "Starting audio recorder")
//        if (running.getAndSet(true)) return
//        job = scope.launch {
//            onStateChanged?.invoke(true)
//            try {
//                recordLoop()
//            } catch (t: Throwable) {
//                onError?.invoke(t)
//            } finally {
//                running.set(false)
//                onStateChanged?.invoke(false)
//            }
//        }
//    }

//    fun stop() {
//        running.set(false)
//        job?.cancel()
//
//        disableBluetoothSco()
//    }

    fun release() {
        stop()
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordLoop() = withContext(Dispatchers.IO) {
        Log.d("AudioRecorder", "recordLoop")

        val baseGain = SharedPreferencesUtil.getAIGain(context) / 100f

        // ─── Resolve the actual input device BEFORE creating AudioRecord ────
        // Priority:
        //   1. User preference saved in SharedPreferencesUtil.KEY_INPUT_DEFAULT
        //      (only if a matching device is currently connected and the
        //      preference is not TYPE_UNKNOWN).
        //   2. Explicit programmatic override via [preferredInputDevice].
        //   3. Auto-pick the best external mic.
        //   4. Null → let the audio HAL choose (usually built-in).
        val resolvedDevice: AudioDeviceInfo? =
            pickPreferredInputDeviceFromPrefs()
                ?: preferredInputDevice
                ?: pickPreferredExternalInputDevice()

        val isExternalMic = resolvedDevice?.let { isExternalInput(it) } == true
        val isBluetoothMic = resolvedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        // BT SCO must be active BEFORE AudioRecord is opened, but only when
        // the selected mic actually IS the BT SCO device — otherwise SCO just
        // forces narrowband 8 kHz and hurts everything else.
        if (isBluetoothMic || !enableScoOnlyForBluetoothInput) {
            if (enableBluetoothSco()) {
                scoEnabledByUs = true
            }
        }

        Log.d(
            "AudioRecorder",
            "input=${resolvedDevice?.productName}/${resolvedDevice?.type} " +
                "external=$isExternalMic bt=$isBluetoothMic"
        )

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(minBuffer > 0) { "Unsupported sample rate or format" }

        val recordBufferSize =
            (minBuffer * 1.5).toInt().coerceAtLeast(bytesPerChunk)

        val prefsSource = SharedPreferencesUtil.getAIAudioSource(context)

        // Upgrade RAW sources to VOICE_RECOGNITION so the OEM voice pipeline
        // (NS / AGC / EQ) actually runs. This is the most effective noise
        // reducer available — anything we do in software is a fallback.
        val configuredSource =
            if (preferProcessedSource &&
                (prefsSource == MediaRecorder.AudioSource.MIC ||
                    prefsSource == MediaRecorder.AudioSource.DEFAULT)
            ) {
                Log.d("AudioRecorder",
                    "preferProcessedSource: upgrading $prefsSource → VOICE_RECOGNITION")
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            } else prefsSource

        Log.d("AudioRecorder", "audioSource(prefs)=$prefsSource effective=$configuredSource")

        var audioRecord = AudioRecord(
            configuredSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferSize
        )

        // Some devices/permissions silently reject sources like VOICE_CALL,
        // VOICE_COMMUNICATION, etc. and leave AudioRecord uninitialized — the
        // user-visible symptom is "recording produces silence". Fall back to
        // MIC, which is always allowed with RECORD_AUDIO.
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED &&
            configuredSource != MediaRecorder.AudioSource.MIC) {
            Log.w("AudioRecorder",
                "AudioRecord init FAILED for source=$configuredSource; falling back to MIC")
            try { audioRecord.release() } catch (_: Exception) {}
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize
            )
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        // ─── Per-source profile compensation ────────────────────────────────
        // The actual source the AudioRecord ended up using (post-fallback).
        val effectiveSource = audioRecord.audioSource
        val profile = resolveSourceProfile(effectiveSource)

        // Static gain stack: prefs * external-mic-boost * source-makeup.
        // Source makeup compensates for OEM behavior on this AudioSource
        // (e.g. VOICE_RECOGNITION attenuates the cleaned signal → boost it).
        val externalBoost = if (isExternalMic) externalMicGainBoost else 1f
        val sourceMakeup = profile?.makeupGain ?: 1f
        val gain = baseGain * externalBoost * sourceMakeup

        // Noise gate threshold: external-mic profile wins, otherwise per-source.
        val effectiveGateRms = when {
            isExternalMic -> noiseGateThresholdRmsExternal
            profile != null -> profile.noiseGateRms
            else -> noiseGateThresholdRms
        }
        activeGateThresholdRms = effectiveGateRms

        // AGC parameters: per-source values override the global defaults
        // unless the user has explicitly disabled the profile system.
        val effectiveAgcTarget = profile?.agcTargetRms ?: 3000f
        val effectiveAgcMaxGain = profile?.agcMaxGain ?: 8f
        val effectiveAgcNoiseFloor = profile?.agcNoiseFloorRms ?: 80f
        val effectiveAgcMinGain = profile?.agcMinGain ?: 0.25f
        val effectiveAgcAttackSec = profile?.agcAttackSec ?: 0.010f
        val effectiveAgcReleaseSec = profile?.agcReleaseSec ?: 0.350f

        Log.d(
            "AudioRecorder",
            "source=$effectiveSource profile=${profile != null} " +
                "gain=$gain (base=$baseGain ext=$externalBoost makeup=$sourceMakeup) " +
                "gateRms=$effectiveGateRms agcTarget=$effectiveAgcTarget " +
                "agcMax=$effectiveAgcMaxGain agcFloor=$effectiveAgcNoiseFloor"
        )

        // 🔑 Explicitly route AudioRecord to the resolved input device so the
        // audio HAL can't silently fall back to the built-in array.
        if (resolvedDevice != null) {
            val ok = audioRecord.setPreferredDevice(resolvedDevice)
            Log.d("AudioRecorder",
                "setPreferredDevice(${resolvedDevice.productName})=$ok")

            // On Android 12+ also pin the *communication* route so the audio
            // policy can't re-route us to a freshly-attached USB / BT mic.
            // Without this, KEY_INPUT_DEFAULT (e.g. TYPE_BUILTIN_MIC) is
            // ignored as soon as a USB device is plugged in.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.setCommunicationDevice(resolvedDevice)
                } catch (e: Exception) {
                    Log.w("AudioRecorder",
                        "setCommunicationDevice(${resolvedDevice.type}) failed", e)
                }
            }
        }

        // Attach platform NS / AGC / AEC. Skip them on external mics: most OEM
        // implementations are tuned for the built-in array geometry and
        // DEGRADE external mic signal.
        val applyHwEffects = useHardwareEffects &&
            !(disableHardwareEffectsForExternalMic && isExternalMic)
        if (applyHwEffects) {
            attachPlatformEffects(audioRecord.audioSessionId)
        } else if (useHardwareEffects && isExternalMic) {
            Log.d("AudioRecorder",
                "Skipping platform NS/AGC/AEC: external mic is active")
        }

        // Reset stateful DSP so a new recording session does not inherit old filter state.
        dcEstimate = 0f
        gateHangoverRemaining = 0
        highPass.reset()

        // Build software AGC for this session if applicable.
        // Hard rule: never run software AGC on built-in input paths. They are
        // already heavily processed by OEM voice pipelines and AGC here tends
        // to raise background hiss between words.
        agc = if (useSoftwareAgc && isExternalMic) {
            SoftwareAgc(
                sampleRate = sampleRate,
                targetRms = effectiveAgcTarget,
                maxGain = effectiveAgcMaxGain,
                minGain = effectiveAgcMinGain,
                noiseFloorRms = effectiveAgcNoiseFloor,
                attackSec = effectiveAgcAttackSec,
                releaseSec = effectiveAgcReleaseSec
            ).also {
                Log.d("AudioRecorder",
                    "SoftwareAGC enabled target=$effectiveAgcTarget max=$effectiveAgcMaxGain " +
                        "min=$effectiveAgcMinGain floor=$effectiveAgcNoiseFloor " +
                        "attack=$effectiveAgcAttackSec release=$effectiveAgcReleaseSec")
            }
        } else null

        // Build software expander for this session.
        // Tune aggressiveness by source:
        // - DEFAULT/MIC: stronger suppression to tame raw noise.
        // - VOICE_RECOGNITION/VOICE_COMMUNICATION: milder suppression so
        //   OEM-cleaned speech remains natural and does not sound gated.
        val effectiveExpanderRatio = profile?.expanderRatio ?: when (effectiveSource) {
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC -> 5.5f

            MediaRecorder.AudioSource.VOICE_RECOGNITION -> 2.2f
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> 2.8f
            else -> 4f
        }
        val effectiveExpanderOpenSnr = profile?.expanderOpenSnr ?: when (effectiveSource) {
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC -> 7.0f

            MediaRecorder.AudioSource.VOICE_RECOGNITION -> 3.2f
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> 3.8f
            else -> 5.5f
        }
        val effectiveExpanderMinGain = profile?.expanderMinGain ?: when (effectiveSource) {
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC -> 0.003f

            MediaRecorder.AudioSource.VOICE_RECOGNITION -> 0.08f
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> 0.05f
            else -> 0.01f
        }
        val effectiveExpanderAttackSec = profile?.expanderAttackSec ?: 0.005f
        val effectiveExpanderReleaseSec = profile?.expanderReleaseSec ?: 0.100f

        expander = if (useSoftwareExpander) {
            SoftwareExpander(
                sampleRate = sampleRate,
                ratio = effectiveExpanderRatio,
                openSnr = effectiveExpanderOpenSnr,
                minGain = effectiveExpanderMinGain,
                attackSec = effectiveExpanderAttackSec,
                releaseSec = effectiveExpanderReleaseSec,
            ).also {
                Log.d("AudioRecorder",
                    "SoftwareExpander enabled ratio=$effectiveExpanderRatio " +
                        "openSnr=$effectiveExpanderOpenSnr minGain=$effectiveExpanderMinGain " +
                        "attack=$effectiveExpanderAttackSec release=$effectiveExpanderReleaseSec")
            }
        } else null

        try {
            noiseProcessor?.init(sampleRate)
        } catch (e: Throwable) {
            Log.w("AudioRecorder", "noiseProcessor.init failed; disabling for this session", e)
            noiseProcessor = null
        }

        // 🔑 Force AudioRecord to unblock when coroutine is cancelled
        coroutineContext.job.invokeOnCompletion {
            try {
                audioRecord.stop()
            } catch (_: Exception) {}
        }

        val shortBuffer = ShortArray(sampleRate / 100)
        val chunkSamples = ShortArray(samplesPerChunk)
        var chunkSampleIndex = 0
        var chunkIndex = 1

        // Open raw debug capture file BEFORE we start recording so chunk #1 is captured.
        if (saveRawAudioToDownloads) {
            debugRawWriter.start(
                context = context,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample
            )
            debugCleanedWriter.start(
                context = context,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                fileNamePrefix = "stardust_cleaned"
            )
            debugAiParsedWriter.start(
                context = context,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                fileNamePrefix = "stardust_ai_parsed"
            )
        }

        audioRecord.startRecording()

        try {
            while (isActive) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) continue

                var consumed = 0
                while (consumed < read && isActive) {
                    val remaining = samplesPerChunk - chunkSampleIndex
                    val toCopy = minOf(remaining, read - consumed)

                    System.arraycopy(
                        shortBuffer,
                        consumed,
                        chunkSamples,
                        chunkSampleIndex,
                        toCopy
                    )

                    chunkSampleIndex += toCopy
                    consumed += toCopy

                    if (chunkSampleIndex == samplesPerChunk) {
                        // 🔴 Capture RAW samples (pre-processing) for debugging.
                        if (saveRawAudioToDownloads) {
                            debugRawWriter.append(chunkSamples, samplesPerChunk)
                        }
                        val processed = processSamples(chunkSamples, gain)
                        // 🟢 Capture cleaned samples (post-noise cleanup / DSP)
                        // before AI callback parsing.
                        if (saveRawAudioToDownloads) {
                            debugCleanedWriter.append(processed, processed.size)
                        }
                        onChunkReady?.invoke(processed, chunkIndex++)
                        // 🟣 Capture post-AI-parsing samples (after the callback
                        // returns; reflects any in-place AI mutations).
                        if (saveRawAudioToDownloads) {
                            debugAiParsedWriter.append(processed, processed.size)
                        }
                        chunkSampleIndex = 0
                    }
                }
            }
        } finally {
            try {
                if (chunkSampleIndex > 0) {
                    // Capture RAW partial tail too, before processing.
                    if (saveRawAudioToDownloads) {
                        debugRawWriter.append(chunkSamples, chunkSampleIndex)
                    }
                    val partial = processSamples(
                        chunkSamples.copyOf(chunkSampleIndex),
                        gain
                    )
                    if (saveRawAudioToDownloads) {
                        debugCleanedWriter.append(partial, partial.size)
                    }
                    onPartialFinalChunk?.invoke(partial, chunkIndex)
                    if (saveRawAudioToDownloads) {
                        debugAiParsedWriter.append(partial, partial.size)
                    }
                }
            } catch (_: Exception) {}

            try {
                audioRecord.stop()
            } catch (_: Exception) {}

            // Close & finalize the debug WAV (patches header sizes).
            try { debugRawWriter.stop() } catch (_: Throwable) {}
            try { debugCleanedWriter.stop() } catch (_: Throwable) {}
            try { debugAiParsedWriter.stop() } catch (_: Throwable) {}

            releasePlatformEffects()
            try { noiseProcessor?.release() } catch (_: Throwable) {}
            agc = null
            expander = null
            audioRecord.release()
            if (scoEnabledByUs) {
                disableBluetoothSco()
                scoEnabledByUs = false
            }
            // Release any communication-device pin we set in recordLoop so a
            // subsequent playback / recording session can pick its own route.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.clearCommunicationDevice()
                } catch (_: Throwable) {}
            }
        }
    }

//    @SuppressLint("MissingPermission")
//    private suspend fun recordLoop() {
//        Log.d("AudioRecorder", "recordLoop")
//
//        val gain = SharedPreferencesUtil.getAIGain(context) / 100f
//        enableBluetoothSco()
//
//        val minBuffer = AudioRecord.getMinBufferSize(
//            sampleRate,
//            AudioFormat.CHANNEL_IN_MONO,
//            AudioFormat.ENCODING_PCM_16BIT
//        )
//
//        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
//            throw IllegalStateException("Unsupported sample rate or format")
//        }
//
//        val recordBufferSize = (minBuffer * 1.5).toInt().coerceAtLeast(bytesPerChunk)
//        val audioRecord = AudioRecord(
//            SharedPreferencesUtil.getAIAudioSource(context),
//            sampleRate,
//            AudioFormat.CHANNEL_IN_MONO,
//            AudioFormat.ENCODING_PCM_16BIT,
//            recordBufferSize
//        )
//
////        try {
////            val sessionId = audioRecord.audioSessionId
////
////            if (AutomaticGainControl.isAvailable()) {
////                AutomaticGainControl.create(sessionId)?.enabled = false
////            }
////            if (NoiseSuppressor.isAvailable()) {
////                NoiseSuppressor.create(sessionId)?.enabled = false
////            }
////            if (AcousticEchoCanceler.isAvailable()) {
////                AcousticEchoCanceler.create(sessionId)?.enabled = false
////            }
////        }catch ( e : Exception) {
////            e.printStackTrace()
////        }
//        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
//            audioRecord.release()
//            throw IllegalStateException("AudioRecord initialization failed")
//        }
//
//        val shortBuffer = ShortArray(sampleRate / 100)
//        val chunkSamples = ShortArray(samplesPerChunk)
//        var chunkSampleIndex = 0
//        var chunkIndex = 1
//        Log.d("AudioRecorder", "startRecording")
//
//        audioRecord.startRecording()
//
//        try {
//
//            while (coroutineContext.isActive && running.get()) {
////                Log.d("AudioRecorder", "while recording")
//                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
//                if (read <= 0) continue
//
//                var consumed = 0
//                while (consumed < read) {
//                    val remainingInChunk = samplesPerChunk - chunkSampleIndex
//                    val toCopy = minOf(remainingInChunk, read - consumed)
//                    System.arraycopy(shortBuffer, consumed, chunkSamples, chunkSampleIndex, toCopy)
//                    chunkSampleIndex += toCopy
//                    consumed += toCopy
//
//                    if (chunkSampleIndex == samplesPerChunk) {
//                        Log.d("AudioRecorder", "TS when invoking chunk $chunkIndex: ${System.currentTimeMillis()}")
//
//                        val processedSamples: ShortArray = processSamples(chunkSamples, gain)
//                        onChunkReady?.invoke(processedSamples, chunkIndex)
//                        chunkIndex++
//                        chunkSampleIndex = 0
//                    }
//                }
//            }
//        } finally {
//            try {
//                audioRecord.stop()
//                // Optionally flush partial chunk
//                if (chunkSampleIndex > 0) {
//                    val samples = chunkSamples.copyOf(chunkSampleIndex)
//                    val partial = processSamples(samples, gain)
//                    onPartialFinalChunk?.invoke(partial, chunkIndex)
//                }
//                audioRecord.release()
//            } catch (_: Exception) {}
//            audioRecord.release()
//        }
//    }

    private fun processSamples(samples: ShortArray, gain: Float): ShortArray {
        return if(SharedPreferencesUtil.isVoiceCancellationEnabled(context))  processSamplesWithNoNoiseCancellation(samples, gain)
        else processSamplesWithNoiseCancellation(samples, gain)
    }

    private fun processSamplesWithNoNoiseCancellation(samples: ShortArray, gain: Float) = samples.map { sample ->
        (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }.toShortArray()

    private fun processSamplesWithNoiseCancellation(samples: ShortArray, gain: Float): ShortArray {
        val len = samples.size
        if (len == 0) return ShortArray(0)

        // We do all internal processing in float to avoid quantization between
        // stages. Quantize back to PCM16 only at the end.
        val work = FloatArray(len)

        // 1) DC offset removal (stateful) + 2) static gain (prefs * external boost)
        for (i in 0 until len) {
            var x = samples[i].toFloat()
            if (useDcRemoval) {
                dcEstimate = dcAlpha * dcEstimate + (1f - dcAlpha) * x
                x -= dcEstimate
            }
            work[i] = x * gain
        }

        // 3) High-pass filter (stateful) — done in float
        if (useHighPass) highPass.processFloat(work, len)

        // 4) Software AGC (dynamic gain). Brings up off-axis / dispersing
        //    speech without clipping near-field speech. Runs on the cleaned
        //    signal so AGC keys off voice, not rumble.
        agc?.process(work, len)

        // 5) Pluggable neural / spectral noise suppression. It works in PCM16,
        //    so we temporarily quantize, run it, then go back to float.
        val np = noiseProcessor
        if (np != null) {
            val tmp = ShortArray(len)
            for (i in 0 until len) {
                tmp[i] = work[i].toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            try {
                np.process(tmp, len)
                for (i in 0 until len) work[i] = tmp[i].toFloat()
            } catch (e: Throwable) {
                Log.w("AudioRecorder", "noiseProcessor.process failed; disabling", e)
                noiseProcessor = null
            }
        }

        // 5.5) Software downward expander. Adapts to the observed noise floor
        //      and ducks audio that's close to it by [expanderRatio]. Far more
        //      effective than the hard noise gate at killing constant hiss
        //      without chopping speech tails.
        expander?.process(work, len)

        // 6) Soft-clip + quantize to PCM16
        val out = ShortArray(len)
        for (i in 0 until len) {
            var x = work[i]
            if (useSoftClip) x = softClip(x)
            out[i] = x.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // 7) Hard noise gate — only used as a fallback if the expander is off.
        //    Running both at once just chops speech onsets unnecessarily.
        if (useNoiseGate && expander == null) {
            applyNoiseGate(out, len, activeGateThresholdRms, gateHangoverSamples)
        }

        return out
    }

    private fun softClip(x: Float): Float {
        val limit = 30000f
        val y = when {
            x >  limit ->  limit + (x - limit) * 0.1f
            x < -limit -> -limit + (x + limit) * 0.1f
            else -> x
        }
        return y.coerceIn(-32768f, 32767f)
    }

    private fun applyNoiseGate(
        buf: ShortArray, len: Int, thresholdRms: Int, hangoverSamples: Int
    ) {
        if (len <= 0) return
        var sumSq = 0.0
        for (i in 0 until len) {
            val s = buf[i].toInt()
            sumSq += (s * s).toDouble()
        }
        val rms = sqrt(sumSq / len)

        if (rms >= thresholdRms) {
            gateHangoverRemaining = hangoverSamples
        } else if (gateHangoverRemaining > 0) {
            gateHangoverRemaining -= len
        } else {
            for (i in 0 until len) buf[i] = 0
        }
    }

    private fun attachPlatformEffects(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = true
                    activeEffects += it
                    Log.d("AudioRecorder", "NoiseSuppressor enabled")
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.also {
                    it.enabled = true
                    activeEffects += it
                    Log.d("AudioRecorder", "AutomaticGainControl enabled")
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = true
                    activeEffects += it
                    Log.d("AudioRecorder", "AcousticEchoCanceler enabled")
                }
            }
        } catch (e: Exception) {
            Log.w("AudioRecorder", "attachPlatformEffects failed", e)
        }
    }

    private fun releasePlatformEffects() {
        for (fx in activeEffects) {
            try { fx.release() } catch (_: Exception) {}
        }
        activeEffects.clear()
    }

    // ─── Input device enumeration / selection ───────────────────────────────

    /**
     * Returns all currently connected input devices the system reports as
     * usable for recording. Useful for building a UI mic-picker.
     */
    fun listInputDevices(): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }

    /**
     * Looks up the user's saved input preference
     * ([SharedPreferencesUtil.getInputDevice]) and returns a connected
     * [AudioDeviceInfo] of that type, if any. Returns null when:
     *  - the preference is TYPE_UNKNOWN (no user choice), or
     *  - no currently-connected input device matches the preferred type.
     *
     * This is what makes `KEY_INPUT_DEFAULT` actually win over a freshly-
     * plugged-in USB / BT peripheral.
     */
    private fun pickPreferredInputDeviceFromPrefs(): AudioDeviceInfo? {
        val wanted = try {
            SharedPreferencesUtil.getInputDevice(context)
        } catch (_: Throwable) {
            AudioDeviceInfo.TYPE_UNKNOWN
        }
        if (wanted == AudioDeviceInfo.TYPE_UNKNOWN) return null
        return listInputDevices().firstOrNull { it.type == wanted }
    }

    /**
     * Auto-selects the "best" external input device, in priority order:
     *   1. Wired headset (TYPE_WIRED_HEADSET)
     *   2. USB headset / mic (TYPE_USB_HEADSET, TYPE_USB_DEVICE, TYPE_USB_ACCESSORY)
     *   3. Bluetooth SCO (TYPE_BLUETOOTH_SCO)
     *   4. Bluetooth A2DP (TYPE_BLE_HEADSET on newer APIs)
     *
     * Returns null when no external mic is connected (caller should let the
     * audio HAL choose the default — usually the built-in array).
     */
    fun pickPreferredExternalInputDevice(): AudioDeviceInfo? {
        val devices = listInputDevices()
        if (devices.isEmpty()) return null

        val priority = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        ).let { base ->
            // BLE_HEADSET only exists on API 31+; append if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base + AudioDeviceInfo.TYPE_BLE_HEADSET
            } else base
        }

        for (type in priority) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    /** True for any non-built-in mic input (USB / wired / BT). */
    private fun isExternalInput(d: AudioDeviceInfo): Boolean = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_TELEPHONY -> false
        else -> true
    }

    /** One-pole high-pass filter; state persists across chunks for click-free filtering. */
    private class OnePoleHighPass(cutoffHz: Float, sampleRate: Float) {
        private val alpha: Float
        private var prevIn: Float = 0f
        private var prevOut: Float = 0f

        init {
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            val dt = 1f / sampleRate
            alpha = rc / (rc + dt)
        }

        fun reset() {
            prevIn = 0f
            prevOut = 0f
        }

        fun process(buf: ShortArray, len: Int) {
            for (i in 0 until len) {
                val x = buf[i].toFloat()
                val y = alpha * (prevOut + x - prevIn)
                prevIn = x
                prevOut = y
                buf[i] = y.toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        /** Same filter, but operates on a float buffer in-place (no quantization). */
        fun processFloat(buf: FloatArray, len: Int) {
            for (i in 0 until len) {
                val x = buf[i]
                val y = alpha * (prevOut + x - prevIn)
                prevIn = x
                prevOut = y
                buf[i] = y
            }
        }
    }

    /**
     * Per-sample software AGC + soft limiter.
     *
     * Strategy:
     *  - Track instantaneous |sample| with attack/release envelope follower.
     *  - Compute desired gain = [targetRms] / envelope, clamped to
     *    [[minGain], [maxGain]].
     *  - Below [noiseFloorRms], freeze the gain to avoid pumping up room hiss.
     *  - Smoothly interpolate the applied gain (attack/release on gain itself)
     *    so we don't introduce zipper noise on transients.
     *  - Final peak limiter prevents clipping when the AGC briefly over-shoots.
     *
     * State is kept across chunks so chunk boundaries don't introduce clicks.
     */
    private class SoftwareAgc(
        sampleRate: Int,
        private val targetRms: Float,
        private val maxGain: Float,
        private val minGain: Float,
        private val noiseFloorRms: Float,
        attackSec: Float,
        releaseSec: Float,
    ) {
        // Envelope follower coefficients (per-sample, exponential smoothing)
        private val envAttack: Float = expCoef(attackSec, sampleRate)
        private val envRelease: Float = expCoef(releaseSec, sampleRate)
        // Gain smoothing coefficients (slightly slower so gain changes are audible-smooth)
        private val gainAttack: Float = expCoef(attackSec, sampleRate)
        private val gainRelease: Float = expCoef(releaseSec, sampleRate)

        private var envelope: Float = 0f
        private var currentGain: Float = 1f

        // Soft limiter ceiling (keeps headroom under PCM16 max).
        private val limit: Float = 30000f

        fun process(buf: FloatArray, len: Int) {
            var env = envelope
            var g = currentGain
            for (i in 0 until len) {
                val absX = abs(buf[i])

                // 1) Envelope: fast attack, slow release.
                env = if (absX > env) {
                    envAttack * env + (1f - envAttack) * absX
                } else {
                    envRelease * env + (1f - envRelease) * absX
                }

                // 2) Desired gain. Freeze when below the noise floor so we
                //    don't amplify pure silence/hiss.
                val desired: Float = if (env < noiseFloorRms) {
                    g.coerceIn(minGain, maxGain) // hold
                } else {
                    (targetRms / env).coerceIn(minGain, maxGain)
                }

                // 3) Smooth gain (per-sample exponential).
                g = if (desired < g) {
                    gainAttack * g + (1f - gainAttack) * desired   // pull down fast
                } else {
                    gainRelease * g + (1f - gainRelease) * desired // bring up slow
                }

                // 4) Apply gain + soft peak limit.
                var y = buf[i] * g
                if (y > limit)       y = limit + (y - limit) * 0.1f
                else if (y < -limit) y = -limit + (y + limit) * 0.1f
                buf[i] = y
            }
            envelope = env
            currentGain = g
        }

        companion object {
            /** Convert a time constant in seconds to a one-pole smoothing coefficient. */
            private fun expCoef(timeSec: Float, sampleRate: Int): Float {
                if (timeSec <= 0f) return 0f
                return exp(-1.0 / (timeSec * sampleRate)).toFloat()
            }
        }
    }

    /**
     * Software downward expander with an **adaptive** noise-floor estimator.
     *
     * Unlike a fixed-threshold gate that either passes or mutes audio, the
     * expander multiplies samples by a smoothly-varying gain that depends on
     * how far the current envelope is above the estimated background noise
     * floor (the SNR).
     *
     *  - SNR ≥ [openSnr]   → gain = 1.0 (speech passes untouched)
     *  - SNR ≤ 1           → gain = [minGain] (heavy attenuation)
     *  - In between        → gain follows a power law of slope [ratio]
     *
     * The noise floor itself is tracked with a fast-rise/slow-fall envelope
     * follower of |sample|, but it only RISES during silence (when current
     * envelope is below current floor), so speech doesn't pull the floor up.
     * This is the key idea behind classical "minimum-statistics" NS.
     */
    private class SoftwareExpander(
        sampleRate: Int,
        private val ratio: Float,
        private val openSnr: Float,
        private val minGain: Float,
        attackSec: Float,
        releaseSec: Float,
    ) {
        // Envelope follower for the signal level (fast attack, slow release).
        private val envAttack: Float = expCoef(attackSec, sampleRate)
        private val envRelease: Float = expCoef(releaseSec, sampleRate)

        // Noise-floor follower: only updates when signal envelope is BELOW the
        // current floor estimate (so speech can't drive it up). Very slow
        // upward adaptation, faster downward.
        private val floorAttack: Float = expCoef(2.000f, sampleRate)  // 2 s up
        private val floorRelease: Float = expCoef(0.250f, sampleRate) // 250 ms down

        // Smoothing for the applied gain (avoids zipper noise on transients).
        private val gainAttack: Float = expCoef(0.003f, sampleRate)
        private val gainRelease: Float = expCoef(0.080f, sampleRate)

        private var envelope: Float = 0f
        // Seed noise floor with a small positive value so SNR is well-defined
        // for the first few hundred ms.
        private var noiseFloor: Float = 50f
        private var currentGain: Float = 1f

        fun process(buf: FloatArray, len: Int) {
            var env = envelope
            var floor = noiseFloor
            var g = currentGain

            for (i in 0 until len) {
                val absX = abs(buf[i])

                // 1) Track signal envelope.
                env = if (absX > env) {
                    envAttack * env + (1f - envAttack) * absX
                } else {
                    envRelease * env + (1f - envRelease) * absX
                }

                // 2) Update noise floor estimate. RISE only when envelope is
                //    near/below the floor (i.e. silence). DROP whenever
                //    envelope drops below the floor — keeps the floor honest
                //    if the room gets quieter mid-recording.
                if (env <= floor) {
                    floor = floorRelease * floor + (1f - floorRelease) * env
                } else if (env < floor * 1.5f) {
                    // gentle upward adaptation when only slightly above floor
                    floor = floorAttack * floor + (1f - floorAttack) * env
                }
                // Clamp floor so a totally silent passage doesn't drive it to 0.
                if (floor < 5f) floor = 5f

                // 3) Compute target gain from SNR.
                val snr = env / floor
                val target = when {
                    snr >= openSnr -> 1f
                    snr <= 1f -> minGain
                    else -> {
                        // Smooth power-law curve between minGain at SNR=1 and
                        // 1.0 at SNR=openSnr.
                        val t = ((snr - 1f) / (openSnr - 1f)).coerceIn(0f, 1f)
                        // Apply ratio: steeper curve = more aggressive expansion.
                        val curved = exp((ln(t.coerceAtLeast(1e-6f)) * ratio).toDouble())
                            .toFloat().coerceIn(0f, 1f)
                        minGain + (1f - minGain) * curved
                    }
                }

                // 4) Smooth the applied gain (open fast, close slow).
                g = if (target > g) {
                    gainAttack * g + (1f - gainAttack) * target
                } else {
                    gainRelease * g + (1f - gainRelease) * target
                }

                buf[i] = buf[i] * g
            }

            envelope = env
            noiseFloor = floor
            currentGain = g
        }

        companion object {
            private fun expCoef(timeSec: Float, sampleRate: Int): Float {
                if (timeSec <= 0f) return 0f
                return exp(-1.0 / (timeSec * sampleRate)).toFloat()
            }
        }
    }

    // In your BleManager or recording activity
//    @SuppressLint("ServiceCast")
    @SuppressLint("NewApi")
    private fun enableBluetoothSco(): Boolean {
        // Get an AudioManager instance
        val audioManager: AudioManager =
            context.getSystemService<AudioManager?>(AudioManager::class.java)
        var speakerDevice: AudioDeviceInfo? = null
        val devices = audioManager.availableCommunicationDevices
        for (device in devices) {
            if (device != null) {
                Log.d("AudioRecorder", "audio device ${device.productName}, type ${device.type}")
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    speakerDevice = device as AudioDeviceInfo?
                    break
                }
            }
        }
        if (speakerDevice != null) {
            // Turn speakerphone ON.
            val result = audioManager.setCommunicationDevice(speakerDevice)
            if (!result) {
                // Handle error.
                Log.e("AudioRecorder", "setCommunicationDevice failed to set ble device")
                return false
            }
            return true
        }
        return false
    }

    @SuppressLint("NewApi")
    private fun disableBluetoothSco() {
        val audioManager: AudioManager =
            context.getSystemService<AudioManager?>(AudioManager::class.java)
        audioManager.clearCommunicationDevice()
        audioManager.isBluetoothScoOn = false
    }

//    private fun makeChunkFile(index: Int): File {
//        val dir = filesDirProvider()
//        if (!dir.exists()) dir.mkdirs()
//        val name = "chunk_${index.toString().padStart(5, '0')}.wav"
//        return File(dir, name)
//    }
//
//    @Throws(IOException::class)
//    private fun writeWav(target: File, samples: ShortArray, sampleCount: Int) {
//        FileOutputStream(target).use { fos ->
//            val dataSize = sampleCount * bytesPerSample * channels
//            val header = createWavHeader(
//                totalAudioBytes = dataSize,
//                sampleRate = sampleRate,
//                channels = channels,
//                bitsPerSample = bitsPerSample
//            )
//            fos.write(header)
//            val bb = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
//            for (i in 0 until sampleCount) {
//                bb.putShort(samples[i])
//            }
//            fos.write(bb.array())
//        }
//    }
//
//    private fun createWavHeader(
//        totalAudioBytes: Int,
//        sampleRate: Int,
//        channels: Int,
//        bitsPerSample: Int
//    ): ByteArray {
//        val byteRate = sampleRate * channels * bitsPerSample / 8
//        val totalDataLen = 36 + totalAudioBytes
//        val header = ByteArray(44)
//        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
//
//        // RIFF
//        header[0] = 'R'.code.toByte()
//        header[1] = 'I'.code.toByte()
//        header[2] = 'F'.code.toByte()
//        header[3] = 'F'.code.toByte()
//        bb.putInt(4, totalDataLen)
//        header[8] = 'W'.code.toByte()
//        header[9] = 'A'.code.toByte()
//        header[10] = 'V'.code.toByte()
//        header[11] = 'E'.code.toByte()
//        header[12] = 'f'.code.toByte()
//        header[13] = 'm'.code.toByte()
//        header[14] = 't'.code.toByte()
//        header[15] = ' '.code.toByte()
//        bb.putInt(16, 16) // Subchunk1Size
//        bb.putShort(20, 1.toShort()) // PCM
//        bb.putShort(22, channels.toShort())
//        bb.putInt(24, sampleRate)
//        bb.putInt(28, byteRate)
//        bb.putShort(32, (channels * bitsPerSample / 8).toShort())
//        bb.putShort(34, bitsPerSample.toShort())
//        header[36] = 'd'.code.toByte()
//        header[37] = 'a'.code.toByte()
//        header[38] = 't'.code.toByte()
//        header[39] = 'a'.code.toByte()
//        bb.putInt(40, totalAudioBytes)
//        return header
//    }
}
