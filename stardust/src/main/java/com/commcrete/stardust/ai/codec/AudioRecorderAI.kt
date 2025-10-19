package com.example.chunkrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext


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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // Callbacks
    var onChunkReady: ((pcmArray: ShortArray, chunkIndex: Int) -> Unit)? = null
    var onPartialFinalChunk: ((pcmArray: ShortArray, chunkIndex: Int) -> Unit)? = null
    var onStateChanged: ((recording: Boolean) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // Public state
    val isRecording: Boolean get() = running.get()

    // Internal
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    private val bytesPerSample = bitsPerSample / 8
    private val samplesPerChunk = (sampleRate * chunkDurationMs / 1000.0).toInt() // 24,000
    private val bytesPerChunk = samplesPerChunk * bytesPerSample * channels

    fun start() {
        if (running.getAndSet(true)) return
        job = CoroutineScope(ioDispatcher).launch {
            onStateChanged?.invoke(true)
            try {
                recordLoop()
            } catch (t: Throwable) {
                onError?.invoke(t)
            } finally {
                running.set(false)
                onStateChanged?.invoke(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()

        disableBluetoothSco()
    }

    fun release() {
        stop()
    }

    private suspend fun recordLoop() {
        enableBluetoothSco()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Unsupported sample rate or format")
        }

        val recordBufferSize = (minBuffer * 1.5).toInt().coerceAtLeast(bytesPerChunk)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        val shortBuffer = ShortArray(sampleRate / 100)
        val chunkSamples = ShortArray(samplesPerChunk)
        var chunkSampleIndex = 0
        var chunkIndex = 1

        audioRecord.startRecording()

        try {
            while (coroutineContext.isActive && running.get()) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) continue

                var consumed = 0
                while (consumed < read) {
                    val remainingInChunk = samplesPerChunk - chunkSampleIndex
                    val toCopy = minOf(remainingInChunk, read - consumed)
                    System.arraycopy(shortBuffer, consumed, chunkSamples, chunkSampleIndex, toCopy)
                    chunkSampleIndex += toCopy
                    consumed += toCopy

                    if (chunkSampleIndex == samplesPerChunk) {
                        onChunkReady?.invoke(chunkSamples, chunkIndex)
                        chunkIndex++
                        chunkSampleIndex = 0
                    }
                }
            }
        } finally {
            try {
                audioRecord.stop()
                // Optionally flush partial chunk
                if (chunkSampleIndex > 0) {
                    val partial = chunkSamples.copyOf(chunkSampleIndex)
                    onPartialFinalChunk?.invoke(partial, chunkIndex)
                }
            } catch (_: Exception) {}
            audioRecord.release()
        }
    }

    // In your BleManager or recording activity
//    @SuppressLint("ServiceCast")
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