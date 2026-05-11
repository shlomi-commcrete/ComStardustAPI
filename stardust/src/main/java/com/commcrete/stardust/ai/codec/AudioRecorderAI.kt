package com.example.chunkrecorder

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
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

    fun release() {
        stop()
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordLoop() = withContext(Dispatchers.IO) {
        Log.d("AudioRecorder", "recordLoop")

        val gain = SharedPreferencesUtil.getAIGain() / 100f
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
            SharedPreferencesUtil.getAIAudioSource(),
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord initialization failed")
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
                        val processed = processSamples(chunkSamples, gain)
                        onChunkReady?.invoke(processed, chunkIndex++)
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

            try {
                audioRecord.stop()
            } catch (_: Exception) {}

            audioRecord.release()
            disableBluetoothSco()
        }
    }

    private fun processSamples(samples: ShortArray, gain: Float) = samples.map { sample ->
        (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }.toShortArray()


    @SuppressLint("NewApi")
    private fun enableBluetoothSco() {
        // Get an AudioManager instance
        val audioManager = DataManager.appContext.getSystemService(AudioManager::class.java) ?: return
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
        val audioManager = DataManager.appContext.getSystemService(AudioManager::class.java) ?: return
        audioManager.clearCommunicationDevice()
        audioManager.isBluetoothScoOn = false
    }

}