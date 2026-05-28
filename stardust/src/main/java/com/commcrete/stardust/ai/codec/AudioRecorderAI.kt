package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.example.chunkrecorder.DebugRawWavWriter
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
        enableBluetoothSco()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(minBuffer > 0) { "Unsupported sample rate or format" }

        val recordBufferSize =
            (minBuffer * 1.5).toInt().coerceAtLeast(bytesPerChunk)

        val audioRecord = AudioRecord(
            SharedPreferencesUtil.getAIAudioSource(context),
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        // 🔌 Try to route capture to the USB audio device (PCM2900C / jbox).
        // If a USB input is present, we prefer it so the recording is bit-exact
        // from the jbox ADC rather than the phone's built-in mic.
        val preferredDevice = preferUsbInputDevice(audioRecord)
        val deviceTag = preferredDevice?.let { sanitizeDeviceName(it.productName?.toString()) }
            ?: "default-mic"

        // 📊 Comprehensive AI-relevant stream logger. Logs source device,
        // per-chunk loudness/clip/silence, periodic spectral snapshots, and
        // a full summary + WavTokenizer-suitability verdict on stop.
        // Tagged "AudioStream" — filter logcat with `adb logcat -s AudioStream`.
        val streamStats = StreamingAudioStatsLogger(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
        ).also {
            it.onStart(
                deviceName = preferredDevice?.productName?.toString(),
                deviceType = preferredDevice?.type ?: -1,
                deviceRates = preferredDevice?.sampleRates?.toList() ?: emptyList(),
            )
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

        audioRecord.startRecording()

        // Save bit-exact PCM coming out of AudioRecord — i.e. straight from the
        // PCM2900C ADC via Android's USB Audio HAL — to Downloads/Stardust/
        // BEFORE any gain / chunking. Failure is logged and recording continues.
        val debugRawWriter = DebugRawWavWriter().also {
            it.start(
                context = context,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                fileNamePrefix = "pcm_raw_${deviceTag}",
            )
        }

        try {
            while (isActive) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) continue

                // RAW capture — must happen BEFORE processSamples() so the
                // file reflects exactly what the ADC delivered (no gain).
                debugRawWriter.append(shortBuffer, read)

                // Per-buffer stats (light: counters + ring-buffer write).
                streamStats.onBufferRead(shortBuffer, read)

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
                        val processed = processSamples(chunkSamples, gain)
                        onChunkReady?.invoke(processed, chunkIndex)
                        // Per-chunk diagnostic line + periodic spectral snapshot.
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

            // Emit the full stream summary BEFORE we tear down — uses ring
            // buffer + cumulative counters that the recorder filled in above.
            try { streamStats.onStop() } catch (_: Throwable) {}

            // Finalize raw WAV (patches header sizes, publishes to MediaStore).
            try { debugRawWriter.stop() } catch (_: Throwable) {}

            try {
                audioRecord.stop()
            } catch (_: Exception) {}

            audioRecord.release()
            disableBluetoothSco()
        }
    }

    /**
     * Picks the first USB-class input device (USB DAC/ADC, USB headset, USB
     * accessory) advertised by [AudioManager] and asks [AudioRecord] to capture
     * from it. Returns the device that was selected, or null if none was found.
     *
     * Without this call, when both the phone's internal mic and a USB audio
     * device (like the jbox's PCM2900C) are connected, Android's routing rules
     * decide which mic the `AudioSource.MIC` actually maps to — and the default
     * is *not* always the USB device. Explicit routing removes that ambiguity.
     */
    @SuppressLint("NewApi")
    private fun preferUsbInputDevice(audioRecord: AudioRecord): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val inputs = try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (t: Throwable) {
            Log.w("AudioRecorder", "getDevices(INPUTS) failed", t)
            return null
        }
        // Log everything we see, so it's easy to diagnose "is the jbox actually visible?"
        for (d in inputs) {
            Log.d(
                "AudioRecorder",
                "input device: name='${d.productName}' type=${d.type} " +
                    "rates=${d.sampleRates.toList()} chans=${d.channelCounts.toList()}"
            )
        }
        val usb = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (usb == null) {
            Log.i("AudioRecorder", "No USB input device available; falling back to default mic")
            return null
        }
        val ok = audioRecord.setPreferredDevice(usb)
        Log.i(
            "AudioRecorder",
            "Routed AudioRecord to USB input: '${usb.productName}' " +
                "(type=${usb.type}, success=$ok, rates=${usb.sampleRates.toList()})"
        )
        return usb
    }

    private fun sanitizeDeviceName(name: String?): String {
        if (name.isNullOrBlank()) return "usb-device"
        return name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(32)
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

    private fun processSamples(samples: ShortArray, gain: Float) = samples.map { sample ->
        (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }.toShortArray()

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