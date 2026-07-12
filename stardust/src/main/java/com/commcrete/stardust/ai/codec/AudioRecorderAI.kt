package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.AudioRecordingKeepAlive
import com.commcrete.stardust.util.audio.filters.configs.AudioCaptureConfig
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
    private val sampleRate: Int = RECORDER_SAMPLE_RATE,
    private val bitsPerSample: Int = 16,
    private val channels: Int = 1,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope : CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private var recordingThread: Thread? = null
) {

    // Callbacks
    var onChunkReady: ((pcmArray: ShortArray, chunkIndex: Int, captureRate: Int, deviceType: Int?) -> Unit)? = null
    var onPartialFinalChunk: ((pcmArray: ShortArray, chunkIndex: Int, captureRate: Int, deviceType: Int?) -> Unit)? = null
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
            AudioRecordingKeepAlive.acquire(context)

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
        AudioCaptureConfig.clearInputRoute(context)
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

        // Input gain: profile setting takes precedence, then SharedPreferences.
        val gain = SharedPreferencesUtil.getAudioGain(context) / 100f

        val capturePlan = AudioCaptureConfig.buildCapturePlan(
            context = context,
            requestedRate = sampleRate,
            defaultAudioSource = SharedPreferencesUtil.getAIAudioSource(context)
        )
        val captureRate = capturePlan.captureRate
        val audioSource = capturePlan.audioSource

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

        AudioCaptureConfig.applyInputRoute(context, audioRecord, capturePlan.preferredInputDevice)

        val deviceTag = capturePlan.preferredInputDevice?.let { sanitizeDeviceName(it.productName?.toString()) }
            ?: "default-mic"

        // Emit chunks at native capture rate (no pre-callback decimation).
        val captureSamplesPerChunk = (captureRate * chunkDurationMs / 1000.0).toInt()


        // 7) Comprehensive AI-relevant stream logger (unchanged).
//        val streamStats = StreamingAudioStatsLogger(
//            sampleRate = captureRate,
//            channels = channels,
//            bitsPerSample = bitsPerSample,
//        ).also {
//            it.onStart(
//                deviceName = capturePlan.preferredInputDevice?.productName?.toString(),
//                deviceType = capturePlan.preferredInputDevice?.type ?: -1,
//                deviceRates = capturePlan.preferredInputDevice?.sampleRates?.toList() ?: emptyList(),
//            )
//        }

        // Force AudioRecord to unblock when coroutine is cancelled.
        coroutineContext.job.invokeOnCompletion {
            try { audioRecord.stop() } catch (_: Exception) {}
        }

        // Read ~20 ms at the *capture* rate. Larger than 10 ms to reduce
        // syscall overhead which on some devices was contributing pops.
        val readChunkSamples = (captureRate / 50).coerceAtLeast(16)
        val shortBuffer = ShortArray(readChunkSamples)

        val chunkSamples = ShortArray(captureSamplesPerChunk)
        var chunkSampleIndex = 0
        var chunkIndex = 1

        audioRecord.startRecording()

        // Report the actual route chosen by AudioRecord when available.
        val resolvedInputType = audioRecord.routedDevice?.type ?: capturePlan.preferredInputDevice?.type

        // Save bit-exact PCM straight from the ADC, BEFORE gain / decimation,
        // tagged with the *real* capture rate so the file is meaningful.
//        val debugRawWriter = DebugRawWavWriter().also {
//            it.start(
//                context = context,
//                sampleRate = captureRate,
//                channels = channels,
//                bitsPerSample = bitsPerSample,
//                fileNamePrefix = "pcm_raw_${deviceTag}_${captureRate}",
//            )
//        }

        try {
            while (isActive) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) continue

                // RAW capture — must happen BEFORE filtering / gain.
                //debugRawWriter.append(shortBuffer, read)

                // Per-buffer stats on the native-rate stream.
                //streamStats.onBufferRead(shortBuffer, read)

                var consumed = 0
                while (consumed < read && isActive) {
                    val remaining = captureSamplesPerChunk - chunkSampleIndex
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

                    if (chunkSampleIndex == captureSamplesPerChunk) {
                        val processed = processSamples(chunkSamples, gain)
                        onChunkReady?.invoke(processed, chunkIndex, captureRate, resolvedInputType)
                        //streamStats.onChunkCompleted(chunkIndex)
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
                    onPartialFinalChunk?.invoke(partial, chunkIndex, captureRate, resolvedInputType)
                }
            } catch (_: Exception) {}

            //try { streamStats.onStop() } catch (_: Throwable) {}
            //try { debugRawWriter.stop() } catch (_: Throwable) {}

            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            AudioCaptureConfig.clearInputRoute(context)
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
     * [SharedPreferencesUtil.setAudioGain] to e.g. 200f (2.0×) or 400f (4.0×).
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

    companion object {
        const val RECORDER_SAMPLE_RATE = 24_000
    }


}