package com.example.chunkrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.AudioRecordManager
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

    // ─── External mic / device routing ──────────────────────────────────────
    /**
     * Programmatic input-device override for [AudioRecord] (`setPreferredDevice`).
     *
     * Note the resolution order in [recordLoop]: the user's saved preference
     * (`KEY_INPUT_DEFAULT`) is consulted **first** and wins over this property.
     * This is only used when the user has expressed no preference, and before
     * falling back to [pickPreferredExternalInputDevice]. Set it to a device of
     * type `TYPE_BUILTIN_MIC` to force the built-in mic when no user preference
     * is stored.
     *
     * Call [listInputDevices] to enumerate options.
     */
    var preferredInputDevice: AudioDeviceInfo? = null

    /**
     * If true, BT SCO routing is enabled ONLY when the selected input device is
     * a BT_SCO mic. Without this, calling enableBluetoothSco() while using a
     * USB or built-in mic forces the system into 8 kHz narrowband for no
     * benefit and often hurts USB audio quality.
     */
    var enableScoOnlyForBluetoothInput: Boolean = true

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

        val gain = SharedPreferencesUtil.getAIGain(context) / 100f

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
            if (scoEnabledByUs) {
                disableBluetoothSco()
                scoEnabledByUs = false
            }
            // Release any communication-device pin we set above so a
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

    private fun processSamples(samples: ShortArray, gain: Float) = samples.map { sample ->
        (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }.toShortArray()

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
     *   4. Bluetooth LE headset (TYPE_BLE_HEADSET, API 31+)
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