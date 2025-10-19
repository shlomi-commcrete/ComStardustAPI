package com.commcrete.aiaudio.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlin.text.compareTo
import kotlin.times

/**
 * Simplest possible streaming PCM16 mono player.
 * Feed it ShortArray frames (any size). It buffers and writes them to an AudioTrack.
 * If sample rate changes, the track is recreated automatically.
 */
object PcmStreamPlayer {
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

        // Calculate buffer size for buffering
        val samplesForBuffering = (sampleRate * bufferingDelay).toInt()
        val bytesForBuffering = samplesForBuffering * 2 // 2 bytes per sample (16-bit)

        val bufferSize = maxOf(minBuf, bytesForBuffering)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
        }
        startPlaybackLoop()
    }

    private fun startPlaybackLoop() {
        audioTrack?.play()
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while(isActive) {

                val frame = frameChannel.tryReceive().getOrNull()
                if (frame != null) {
                    measureTime()

//                    frameBuffer.add(frame)
//                    saveFramesToWav()

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

        // Calculate silence duration
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
    }

    private var isFirst = true;
    private var isRecoded = false;
    private var startRecored = 0L
    private fun saveFramesToWav() {
        if (frameBuffer.isEmpty() || isRecoded) return

        if (isFirst) {
            isFirst = false
            startRecored = System.currentTimeMillis()
        }

        if (System.currentTimeMillis() - startRecored > 1500) {
//             Save buffered frames to WAV file
            val sampleArray = frameBuffer.flatMap { it.asIterable() }.toShortArray()
            WavHelper.createWavFile(sampleArray, currentSampleRate, File("/data/data/com.commcrete.aiaudio/cache/stream_receive.wav"))
            frameBuffer.clear()
            isRecoded = true
        }
    }

    private fun checkIfBufferLow(track: AudioTrack) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - measureLastFrameTime > 1100) {
            return
        }

        // Get current playback state
        val playState = track.playState
        val bufferSize = track.bufferSizeInFrames
        val headPosition = track.playbackHeadPosition

        // Check if buffer is getting low
        val bufferedSamples = bufferSize - headPosition
        val isBufferLow = bufferedSamples < (currentSampleRate * 0.05) // Less than 50ms buffered

        if (isBufferLow) {
            Log.w("PcmStreamPlayer", "Buffer running low: $bufferedSamples samples remaining, playState: $playState, head position: $headPosition, buffer size: $bufferSize")
        }
    }
}
