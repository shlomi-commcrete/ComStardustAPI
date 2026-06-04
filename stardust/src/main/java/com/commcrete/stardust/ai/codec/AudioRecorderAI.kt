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
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.ai.codec.testing.DebugRawWavWriter
import com.commcrete.stardust.ai.codec.testing.StreamingAudioStatsLogger
import com.commcrete.stardust.util.audio.LowPassFilter
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean


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

        disableBluetoothSco()
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

        val gain = SharedPreferencesUtil.getAIGain(context) / 100f

        // -----------------------------------------------------------------
        // 1) Pick the USB input device FIRST, before opening AudioRecord.
        //    The jbox uses a TI PCM2900C ADC whose native input rates are
        //    {16000, 32000, 44100, 48000} Hz. The recorder's logical rate is
        //    24000 Hz, which the chip CANNOT deliver — so if we open
        //    AudioRecord at 24 kHz the Android USB-audio HAL has to
        //    resample 48 k → 24 k and that low-quality resampler is the
        //    source of the high-frequency hiss/whine you hear on Android
        //    but not on Windows (which records at 48 k natively).
        //
        //    Strategy: open AudioRecord at the device's native rate
        //    (prefer 48 kHz, then 44.1, 32, 16) and decimate in software
        //    using a LowPassFilter as the anti-alias stage. Falls back to
        //    the requested [sampleRate] if no USB device is available.
        // -----------------------------------------------------------------
        val usbDevice = findUsbInputDevice()
        val useUsb = usbDevice != null
        val captureRate = pickCaptureRate(usbDevice, sampleRate)

        // 2) When the jbox is plugged in, do NOT engage Bluetooth SCO. That
        //    call invokes setCommunicationDevice(BLUETOOTH_SCO) which can
        //    yank routing away from USB and force the system into the
        //    voice-call DSP path (AGC/NS/AEC tuned for the built-in mic).
        if (!useUsb) {
            enableBluetoothSco()
        }

        // 3) Audio source: prefer UNPROCESSED on a real USB ADC so Android
        //    cannot apply its built-in mic AGC/NS/AEC pipeline. Fall back to
        //    the user-configured source for the built-in mic case.
        val audioSource = if (useUsb && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        } else {
            SharedPreferencesUtil.getAIAudioSource(context)
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            captureRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(minBuffer > 0) { "Unsupported sample rate or format ($captureRate Hz)" }

        // Buffer at least ~250 ms at the capture rate to absorb USB jitter.
        val recordBufferSize =
            maxOf((minBuffer * 2), captureRate * channels * bytesPerSample / 4)

        val audioRecord = AudioRecord(
            audioSource,
            captureRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException(
                "AudioRecord initialization failed (rate=$captureRate, source=$audioSource)"
            )
        }

        // 4) Disable platform audio effects on this session. Some OEMs leave
        //    AGC / NS / AEC enabled by default even on UNPROCESSED, and they
        //    will still touch USB capture if the session is associated with
        //    a comm-style usage.
        //    The returned effects MUST be kept alive (otherwise the GC may
        //    finalize their native handles mid-recording) and released in
        //    the finally block below.
        val ownedEffects = configurePlatformAudioEffects(audioRecord.audioSessionId)

        // 5) Now bind the AudioRecord to the USB device explicitly so the
        //    framework cannot silently route to the built-in mic.
        if (useUsb) {
            val ok = audioRecord.setPreferredDevice(usbDevice)
            Log.i(
                "AudioRecorder",
                "Routed AudioRecord to USB input: '${usbDevice?.productName}' " +
                    "(type=${usbDevice?.type}, success=$ok, " +
                    "rates=${usbDevice?.sampleRates?.toList()})"
            )
        } else {
            Log.i("AudioRecorder", "No USB input device available; using default mic")
        }

        val deviceTag = usbDevice?.let { sanitizeDeviceName(it.productName?.toString()) }
            ?: "default-mic"

        // 6) Anti-alias / decimation setup. If captureRate == sampleRate the
        //    decimator becomes a no-op pass-through.
        val decimationFactor = if (captureRate > sampleRate && captureRate % sampleRate == 0) {
            captureRate / sampleRate
        } else {
            1
        }
        val effectiveSampleRate = captureRate / decimationFactor

        // Cutoff at ~45% of the *target* Nyquist to leave guard band for the
        // simple decimator. 24 kHz -> ~10.8 kHz, 8 kHz -> ~3.6 kHz.
        val antiAliasLpf = if (decimationFactor > 1) {
            LowPassFilter(
                sampleRateHz = captureRate,
                cutoffHz = (effectiveSampleRate * 0.45f),
                rollOffDbPerOctave = 24f,
            )
        } else {
            null
        }

        Log.i(
            "AudioRecorder",
            "Capture: rate=$captureRate, target=$sampleRate, decim=$decimationFactor, " +
                "src=$audioSource, useUsb=$useUsb"
        )

        // 7) Comprehensive AI-relevant stream logger (unchanged).
        val streamStats = StreamingAudioStatsLogger(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
        ).also {
            it.onStart(
                deviceName = usbDevice?.productName?.toString(),
                deviceType = usbDevice?.type ?: -1,
                deviceRates = usbDevice?.sampleRates?.toList() ?: emptyList(),
            )
        }

        // Force AudioRecord to unblock when coroutine is cancelled.
        coroutineContext.job.invokeOnCompletion {
            try { audioRecord.stop() } catch (_: Exception) {}
        }

        // Read ~20 ms at the *capture* rate. Larger than 10 ms to reduce
        // syscall overhead which on some devices was contributing pops.
        val readChunkSamples = (captureRate / 50).coerceAtLeast(decimationFactor * 16)
        val shortBuffer = ShortArray(readChunkSamples)

        // Decimated samples land here, then accumulate into chunkSamples.
        val decimatedScratch = ShortArray(readChunkSamples / decimationFactor + 1)

        val chunkSamples = ShortArray(samplesPerChunk)
        var chunkSampleIndex = 0
        var chunkIndex = 1

        audioRecord.startRecording()

        // Save bit-exact PCM straight from the ADC, BEFORE gain / decimation,
        // tagged with the *real* capture rate so the file is meaningful.
        val debugRawWriter = DebugRawWavWriter().also {
            it.start(
                context = context,
                sampleRate = captureRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                fileNamePrefix = "pcm_raw_${deviceTag}_${captureRate}",
            )
        }

        try {
            while (isActive) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) continue

                // RAW capture — must happen BEFORE filtering / gain.
                debugRawWriter.append(shortBuffer, read)

                // Anti-alias filter the captured block (in place).
                antiAliasLpf?.processInPlace(shortBuffer, read)

                // Decimate (or pass through).
                val produced = if (decimationFactor == 1) {
                    System.arraycopy(shortBuffer, 0, decimatedScratch, 0, read)
                    read
                } else {
                    var w = 0
                    var r = 0
                    while (r < read) {
                        decimatedScratch[w++] = shortBuffer[r]
                        r += decimationFactor
                    }
                    w
                }

                // Per-buffer stats on the decimated stream (matches sampleRate).
                streamStats.onBufferRead(decimatedScratch, produced)

                var consumed = 0
                while (consumed < produced && isActive) {
                    val remaining = samplesPerChunk - chunkSampleIndex
                    val toCopy = minOf(remaining, produced - consumed)

                    System.arraycopy(
                        decimatedScratch,
                        consumed,
                        chunkSamples,
                        chunkSampleIndex,
                        toCopy
                    )

                    chunkSampleIndex += toCopy
                    consumed += toCopy

                    if (chunkSampleIndex == samplesPerChunk) {
                        val processed = processSamples(chunkSamples, gain)
                        onChunkReady?.invoke(processed, chunkIndex)
                        streamStats.onChunkCompleted(chunkIndex)
                        chunkIndex++
                        chunkSampleIndex = 0
                    }
                }
            }
        } finally {
            try {
                if (chunkSampleIndex > 0) {
                    val partial = processSamples(
                        chunkSamples.copyOf(chunkSampleIndex),
                        gain
                    )
                    onPartialFinalChunk?.invoke(partial, chunkIndex)
                }
            } catch (_: Exception) {}

            try { streamStats.onStop() } catch (_: Throwable) {}
            try { debugRawWriter.stop() } catch (_: Throwable) {}

            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            // Release native AudioEffect handles attached to the session.
            for (fx in ownedEffects) {
                try { fx.release() } catch (_: Throwable) {}
            }
            if (!useUsb) disableBluetoothSco()
        }
    }

    /**
     * Returns the first USB-class input device exposed by [AudioManager], or
     * null if none is present. Logs every input it sees so that "is the jbox
     * actually visible?" is answerable from a single logcat line.
     */
    @SuppressLint("NewApi")
    private fun findUsbInputDevice(): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val inputs = try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (t: Throwable) {
            Log.w("AudioRecorder", "getDevices(INPUTS) failed", t)
            return null
        }
        for (d in inputs) {
            Log.d(
                "AudioRecorder",
                "input device: name='${d.productName}' type=${d.type} " +
                    "rates=${d.sampleRates.toList()} chans=${d.channelCounts.toList()}"
            )
        }
        return inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
    }

    /**
     * Choose a capture sample rate that the USB ADC supports natively, in
     * order of preference: 48000, 44100, 32000, 16000. If the device does
     * not advertise rates (some OEM HALs return an empty array), default to
     * 48 kHz which is what the PCM2900C produces. Falls back to
     * [requestedRate] when there is no USB device at all.
     */
    private fun pickCaptureRate(device: AudioDeviceInfo?, requestedRate: Int): Int {
        if (device == null) return requestedRate
        val advertised = device.sampleRates.toList()
        val preferred = listOf(48_000, 44_100, 32_000, 16_000)
        val match = preferred.firstOrNull { it in advertised }
        if (match != null) return match
        // Some HALs report an empty rate list — assume 48 kHz, which is the
        // PCM2900C's input rate and the most common USB-audio default.
        return if (advertised.isEmpty()) 48_000 else advertised.max()
    }

    /**
     * Configure platform pre-effects on this AudioRecord session and return
     * the live [AudioEffect] instances so the caller can keep them alive
     * for the lifetime of the recording and release them on stop.
     *
     * Pipeline / precedence:
     *  1. NS + AEC are *always* disabled — they damage USB capture even on
     *     [MediaRecorder.AudioSource.UNPROCESSED] on some OEM ROMs.
     *  2. If [SharedPreferencesUtil.getDynamicsProcessingEnabled] is true and
     *     the platform supports it (API 28+, effect available), attach a
     *     `DynamicsProcessing` chain (input gain → 3-band MBC → limiter)
     *     and DO NOT enable platform AGC. This is the configurable
     *     "AGC-equivalent" path with tunable attack/release/threshold/ratio.
     *  3. Otherwise, honour [SharedPreferencesUtil.getAutoGainControl] and
     *     fall back to the platform's wideband AGC effect.
     *
     * The returned list MUST be released by the caller in the recorder
     * `finally` block — `AudioEffect` instances are native-resource handles
     * and are not safe to leave to the GC.
     */
    private fun configurePlatformAudioEffects(sessionId: Int): List<AudioEffect> {
        val owned = mutableListOf<AudioEffect>()

        // 1) Always disable NS / AEC — these are the actual culprits for
        //    pumping / breathing on USB audio capture.
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = false
                    owned += it
                }
            }
        } catch (t: Throwable) {
            Log.w("AudioRecorder", "Disable NS failed", t)
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = false
                    owned += it
                }
            }
        } catch (t: Throwable) {
            Log.w("AudioRecorder", "Disable AEC failed", t)
        }

        // 2) DynamicsProcessing path — preferred when the user enabled it
        //    and the platform actually supports it.
        val dpWanted = try {
            SharedPreferencesUtil.getDynamicsProcessingEnabled(context)
        } catch (_: Throwable) { false }

        var dpAttached = false
        if (dpWanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dp = tryAttachDynamicsProcessing(sessionId)
            if (dp != null) {
                owned += dp
                dpAttached = true
                Log.i("AudioRecorder", "DynamicsProcessing attached to session=$sessionId")
            } else {
                Log.w(
                    "AudioRecorder",
                    "DynamicsProcessing requested but not available; falling back to platform AGC"
                )
            }
        }

        // 3) Platform AGC fallback. Disabled when DP is in place because
        //    stacking two gain-altering effects on the same session leads
        //    to fight-club gain wars.
        val agcWanted = try {
            SharedPreferencesUtil.getAutoGainControl(context)
        } catch (_: Throwable) { false }
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.also {
                    it.enabled = agcWanted && !dpAttached
                    owned += it
                    Log.i(
                        "AudioRecorder",
                        "Platform AGC enabled=${it.enabled} (wanted=$agcWanted, dp=$dpAttached)"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("AudioRecorder", "Configure AGC failed", t)
        }

        return owned
    }

    /**
     * Build and attach a 3-band multiband-compressor + limiter
     * `DynamicsProcessing` chain to [sessionId].
     *
     * Defaults are tuned for jbox / PCM2900C speech capture at 48 kHz mono:
     *  - Input gain: configurable via
     *    [SharedPreferencesUtil.getDynamicsProcessingInputGainDb] (default +6 dB).
     *  - Band 0 (sub-bass, 0–200 Hz): −6 dB pre-gain → kill USB rumble /
     *    handling noise without affecting speech.
     *  - Band 1 (speech, 200–4000 Hz): 3:1 compression above −24 dBFS,
     *    5 ms attack, 80 ms release, +6 dB make-up. This is the speech
     *    band the codec / WavTokenizer cares about.
     *  - Band 2 (highs, 4000+ Hz): −3 dB pre-gain to suppress USB hiss,
     *    expander gating below −60 dBFS.
     *  - Limiter: −1 dBFS ceiling, 1 ms attack, 50 ms release, 20:1 — final
     *    safety net so we never clip downstream code.
     *
     * Returns null if the device's audio HAL does not implement
     * DynamicsProcessing (some low-end chipsets / older ROMs).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun tryAttachDynamicsProcessing(sessionId: Int): DynamicsProcessing? {
        return try {
            val inputGainDb = try {
                SharedPreferencesUtil.getDynamicsProcessingInputGainDb(context)
                    .coerceIn(-24f, 24f)
            } catch (_: Throwable) { 6f }

            val cfg = DynamicsProcessing.Config.Builder(
                /* variant       = */ DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount  = */ 1,
                /* preEqInUse    = */ false, /* preEqBandCount    = */ 0,
                /* mbcInUse      = */ true,  /* mbcBandCount      = */ 3,
                /* postEqInUse   = */ false, /* postEqBandCount   = */ 0,
                /* limiterInUse  = */ true
            )
                .setInputGainAllChannelsTo(inputGainDb)
                .build()

            val dp = DynamicsProcessing(/* priority */ 0, sessionId, cfg)

            // ---------- Multiband compressor ----------
            // Band 0 — sub-bass kill, light gating. ratio 1:1 = pass-through
            // dynamics; we just attenuate it via preGain.
            val band0 = DynamicsProcessing.MbcBand(
                /* enabled            */ true,
                /* cutoffFrequency    */ 200f,
                /* attackTime         */ 10f,
                /* releaseTime        */ 100f,
                /* ratio              */ 1f,
                /* threshold          */ 0f,
                /* kneeWidth          */ 0f,
                /* noiseGateThreshold */ -100f,
                /* expanderRatio      */ 1f,
                /* preGain            */ -6f,
                /* postGain           */ 0f,
            )
            // Band 1 — speech band compressor with make-up gain.
            val band1 = DynamicsProcessing.MbcBand(
                /* enabled            */ true,
                /* cutoffFrequency    */ 4000f,
                /* attackTime         */ 5f,
                /* releaseTime        */ 80f,
                /* ratio              */ 3f,
                /* threshold          */ -24f,
                /* kneeWidth          */ 6f,
                /* noiseGateThreshold */ -100f,
                /* expanderRatio      */ 1f,
                /* preGain            */ 0f,
                /* postGain           */ 6f,
            )
            // Band 2 — highs: small attenuation + downward expander
            // (gate-ish) to suppress USB hiss when there's no signal.
            val band2 = DynamicsProcessing.MbcBand(
                /* enabled            */ true,
                /* cutoffFrequency    */ 20000f, // upper edge; limited by Nyquist
                /* attackTime         */ 5f,
                /* releaseTime        */ 80f,
                /* ratio              */ 1f,
                /* threshold          */ 0f,
                /* kneeWidth          */ 0f,
                /* noiseGateThreshold */ -60f,
                /* expanderRatio      */ 2f,
                /* preGain            */ -3f,
                /* postGain           */ 0f,
            )
            dp.setMbcBandAllChannelsTo(0, band0)
            dp.setMbcBandAllChannelsTo(1, band1)
            dp.setMbcBandAllChannelsTo(2, band2)

            // ---------- Brick-wall limiter ----------
            val limiter = DynamicsProcessing.Limiter(
                /* enabled     */ true,
                /* inUse       */ true,
                /* linkGroup   */ 0,
                /* attackTime  */ 1f,
                /* releaseTime */ 50f,
                /* ratio       */ 20f,
                /* threshold   */ -1f,
                /* postGain    */ 0f,
            )
            dp.setLimiterAllChannelsTo(limiter)

            dp.enabled = true
            Log.i(
                "AudioRecorder",
                "DynamicsProcessing config: inputGain=${inputGainDb}dB, " +
                    "bands=[<200Hz -6dB, 200-4k 3:1@-24+6, >4k -3dB gate@-60], " +
                    "limiter=-1dBFS 20:1"
            )
            dp
        } catch (t: Throwable) {
            Log.w("AudioRecorder", "DynamicsProcessing attach failed", t)
            null
        }
    }

    private fun sanitizeDeviceName(name: String?): String {
        if (name.isNullOrBlank()) return "usb-device"
        return name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(32)
    }


    /**
     * Apply digital gain followed by a soft-knee limiter, so it is safe to
     * call this with [gain] >> 1.0 (e.g. 2.0 / 4.0 to make up for the
     * PCM2900C's fixed analog gain) without producing the harsh square-wave
     * clipping you would get from the previous `coerceIn(MIN, MAX)`.
     *
     * Behaviour:
     *  - Below ~50% of full scale the response is exactly linear, so quiet
     *    detail is untouched.
     *  - Above that the curve is tanh-shaped and asymptotes smoothly to
     *    ±32767, so transients are rounded rather than chopped.
     *
     * If you want a louder signal: bump
     * [SharedPreferencesUtil.setAIGain] to e.g. 200f (2.0×) or 400f (4.0×).
     */
    private fun processSamples(samples: ShortArray, gain: Float): ShortArray {
        if (gain == 1f) return samples.copyOf()
        val out = ShortArray(samples.size)
        val knee = 0.5f                  // linear region: |x| <= 0.5 * FS
        val fs = Short.MAX_VALUE.toFloat()
        for (i in samples.indices) {
            val x = samples[i] * gain        // pre-gain, in 16-bit units
            val n = x / fs                   // normalise to [-many, +many]
            val absN = if (n < 0f) -n else n
            val shaped = if (absN <= knee) {
                n
            } else {
                // Soft-clip: 0.5 + tanh((|n|-0.5)/0.5)*0.5 in the upper half.
                val sign = if (n < 0f) -1f else 1f
                val over = (absN - knee) / (1f - knee)         // 0..∞
                sign * (knee + (1f - knee) * kotlin.math.tanh(over))
            }
            val iv = (shaped * fs).toInt()
            out[i] = when {
                iv > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                iv < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> iv.toShort()
            }
        }
        return out
    }

    // In your BleManager or recording activity
//    @SuppressLint("ServiceCast")
    @SuppressLint("NewApi")
    private fun enableBluetoothSco() {
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
            }
        }
    }

    @SuppressLint("NewApi")
    private fun disableBluetoothSco() {
        val audioManager: AudioManager =
            context.getSystemService<AudioManager?>(AudioManager::class.java)
        audioManager.clearCommunicationDevice()
        audioManager.isBluetoothScoOn = false
    }

}