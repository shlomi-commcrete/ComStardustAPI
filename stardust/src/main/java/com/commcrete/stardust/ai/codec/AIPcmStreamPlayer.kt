package com.commcrete.stardust.ai.codec


import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.util.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.File

/**
 * Streaming PCM16 mono player for the AI tokenizer path.
 * Feed it ShortArray frames (any size). It buffers and writes them to an AudioTrack.
 * If sample rate changes, the track is recreated automatically.
 */
object AIPcmStreamPlayer {
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null

    // Small bounded buffer; if producer is faster, oldest frames are dropped to avoid OOM.
    private var frameChannel = Channel<ShortArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private const val bufferingDelay = 0.5f // seconds of audio to buffer

    private var measureLastFrameTime = 0L
    private var measureTotalSilenceTime = 0L
    private var measureCounterSilence = 0L
    private var measureMinTime = 0L
    private var measureMaxTime = 0L

    private val frameBuffer = mutableListOf<ShortArray>()

    var isFileInit = false
    private var fileToWrite: File? = null
    private var ts = ""
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val runnable: Runnable = Runnable {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d("PcmStreamPlayer", "Stopping file write due to inactivity.")
            val file = fileToWrite
            Log.d("PcmStreamPlayer", "File to write: ${file?.absolutePath}")
            file?.let { saveFramesToWav(it) }

            isFileInit = false
            fileToWrite = null
            ts = ""
            isRecoded = false
            isFirst = true
            startRecored = 0L
        }
    }

    @Synchronized
    private fun ensureTrack(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate) return
        releaseInternal()
        currentSampleRate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val samplesForBuffering = (sampleRate * bufferingDelay).toInt()
        val bytesForBuffering = samplesForBuffering * 2 // 2 bytes per sample (16-bit)
        val bufferSize = maxOf(minBuf, bytesForBuffering)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
        startPlaybackLoop()
    }

    private fun startPlaybackLoop() {
        audioTrack?.play()
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                val frame = frameChannel.tryReceive().getOrNull()
                if (frame != null) {
                    measureTime()
                    frameBuffer.add(frame)

                    val track = audioTrack ?: break
                    var offset = 0
                    val total = frame.size
                    while (offset < total) {
                        val written = track.write(frame, offset, total - offset)
                        if (written <= 0) break else offset += written
                    }
                }
                delay(1)
            }
        }
    }

    private fun measureTime() {
        val currentTime = System.currentTimeMillis()

        if (measureLastFrameTime > 0) {
            val actualGap = currentTime - measureLastFrameTime
            if (actualGap < 950) { // 50ms tolerance
                measureTotalSilenceTime += actualGap
                measureCounterSilence++
                if (measureMinTime == 0L || actualGap < measureMinTime) measureMinTime = actualGap
                if (actualGap > measureMaxTime) measureMaxTime = actualGap
                Log.d("PcmStreamPlayer", "actualGap: $actualGap, average: ${measureTotalSilenceTime / measureCounterSilence}, minTime: $measureMinTime, maxTime: $measureMaxTime")
            } else if (actualGap < 1200) { // 1.2 seconds
                Log.d("PcmStreamPlayer", "Frame dropped")
            }
        }

        measureLastFrameTime = currentTime
    }

    /** Enqueue a PCM16 mono frame. Recreates track if sample rate has changed. */
    fun enqueue(frame: ShortArray, sampleRate: Int) {
        resetTimer()
        ensureTrack(sampleRate)
        frameChannel.trySend(frame)
    }

    /** Stop and release resources. */
    fun stop() { releaseInternal() }

    @Synchronized
    private fun releaseInternal() {
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.run {
            try { stop() } catch (_: Exception) {}
            try { flush() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioTrack = null
        currentSampleRate = -1
        frameChannel.close()
        frameChannel = Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        measureLastFrameTime = 0L
        measureTotalSilenceTime = 0L
        measureCounterSilence = 0L
        measureMinTime = 0L
        measureMaxTime = 0L
        isRecoded = false
        isFirst = true
    }

    private var isFirst = true
    private var isRecoded = false
    private var startRecored = 0L

    private suspend fun saveFramesToWav(file: File) {
        Log.d("PcmStreamPlayer", "saveFramesToWav called.")
        Log.d("PcmStreamPlayer", "Number of buffered frames: ${frameBuffer.size}")
        Log.d("PcmStreamPlayer", "isRecoded: $isRecoded")
        if (frameBuffer.isEmpty() || isRecoded) return

        if (isFirst) {
            Log.d("PcmStreamPlayer", "Starting recording timer.")
            isFirst = false
            startRecored = System.currentTimeMillis()
        }

        delay(1500)
        if (System.currentTimeMillis() - startRecored > 1500) {

        }
        Log.d("PcmStreamPlayer", "Saving buffered frames to WAV file after 1.5 seconds.")
        val sampleArray = frameBuffer.flatMap { it.asIterable() }.toShortArray()
        WavHelper.createWavFile(sampleArray, currentSampleRate, file)
        frameBuffer.clear()
        isRecoded = false
    }

    fun initPttInputFile(ids: StardustAPIPackage): File? {
        setTs()
        val source = ids.groupId ?: ids.senderId
        val dir = File(DataManager.appContext.filesDir, source)

        if (!dir.exists() && !dir.mkdirs()) {
            Log.e("PcmStreamPlayer", "Failed to create directory: ${dir.absolutePath}")
            return null
        }

        val file = fileToWrite ?: File(dir, "$ts-${ids.senderId}.pcm")

        if (!file.exists()) {
            file.createNewFile()
            fileToWrite = file
            isFileInit = true
        }

        return file
    }

    private fun setTs() {
        if (ts.isEmpty()) ts = System.currentTimeMillis().toString()
    }

    private fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 2000)
    }
}
