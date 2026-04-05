package com.commcrete.aiaudio.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRouter
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.audio.BleMediaConnector
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel


object PcmStreamPlayer : BleMediaConnector() {

    // ── Unified AudioTrack (handles both AI and Codec2 streams) ─────────────────────────────────
    private var audioTrack: AudioTrack? = null
    private var enhancer: LoudnessEnhancer? = null
    var currentSampleRate: Int = -1
        private set
    private var currentStreamType: RecorderUtils.CODE_TYPE? = null

    // Stream-specific config
    private data class StreamConfig(
        val streamType: RecorderUtils.CODE_TYPE,
        val contentType: Int,
        val needsEnhancer: Boolean,
        val bufferingDelaySeconds: Float = 0.5f
    )

    // AI stream config
    private val aiConfig = StreamConfig(
        streamType = RecorderUtils.CODE_TYPE.AI,
        contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
        needsEnhancer = false,
        bufferingDelaySeconds = 0.5f
    )

    // Codec2 stream config
    private val codec2Config = StreamConfig(
        streamType = RecorderUtils.CODE_TYPE.CODEC2,
        contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        needsEnhancer = true,
        bufferingDelaySeconds = 0.5f
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null

    // Small bounded buffer; if producer is faster, oldest frames are dropped to avoid OOM.
    private var frameChannel = Channel<ShortArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private const val CODEC2_SAMPLE_RATE = 8000
    private const val CODEC2_MIN_WRITE_SIZE = 40
    private const val PLAYBACK_TRACE_TAG = "PTTPlaybackTrace"

    // ── AI track: create / write / release ──────────────────────────────────────────────────────

    @Synchronized
    private fun ensureAudioTrack(sampleRate: Int, config: StreamConfig, bufferSizeInBytes: Int? = null) {
        val needsRecreate = audioTrack == null || currentSampleRate != sampleRate || currentStreamType != config.streamType
        if (!needsRecreate) return

        releaseAudioTrack()
        currentSampleRate = sampleRate
        currentStreamType = config.streamType

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val calculatedBufSize = bufferSizeInBytes ?: maxOf(minBuf, (sampleRate * config.bufferingDelaySeconds).toInt() * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(config.contentType)
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
            .setBufferSizeInBytes(calculatedBufSize)
            .build()

        if (config.needsEnhancer) {
            audioTrack?.audioSessionId?.let {
                enhancer?.release()
                enhancer = LoudnessEnhancer(it)
                val gainmB = Math.round(Math.log10(5.4) * 2000).toInt()
                enhancer?.setTargetGain(gainmB)
                enhancer?.setEnabled(true)
            }
        }

        if (config.streamType == RecorderUtils.CODE_TYPE.CODEC2) {
            syncBleDevice(DataManager.context)
        }

        startPlaybackLoop()
        Log.d(PLAYBACK_TRACE_TAG, "ensureAudioTrack created track=${audioTrack?.hashCode()} streamType=${config.streamType} sampleRate=$sampleRate buffer=$calculatedBufSize")
    }

    private fun startPlaybackLoop() {
        audioTrack?.play()
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                val frame = frameChannel.tryReceive().getOrNull()
                if (frame != null) {
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

    /** Enqueue a PCM16 mono frame for AI stream. Recreates track if sample rate has changed. */
    fun enqueue(frame: ShortArray, sampleRate: Int) {
        ensureAudioTrack(sampleRate, aiConfig)
        frameChannel.trySend(frame)
    }

    /** Stop and release the AudioTrack. */
    fun stop() { releaseAudioTrack() }

    @Synchronized
    private fun releaseAudioTrack() {
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.run {
            try { stop() } catch (_: Exception) {}
            try { flush() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        enhancer?.release()
        audioTrack = null
        enhancer = null
        currentSampleRate = -1
        currentStreamType = null
        frameChannel.close()
        frameChannel = Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    // ── Legacy (Codec2) track: create / write / release ─────────────────────────────────────────

    fun ensureLegacyTrack(bufferSizeInBytes: Int, speedFactor: Float) {
        try {
            val sampleRate = (CODEC2_SAMPLE_RATE * speedFactor).toInt()
            ensureAudioTrack(sampleRate, codec2Config, bufferSizeInBytes)
        } catch (e: Exception) {
            Log.e(PLAYBACK_TRACE_TAG, "ensureLegacyTrack failed", e)
        }
    }

    fun playLegacyStream(audioData: ByteArray, bufferSizeInBytes: Int, receivedPkgs: Int, playFromSdk: Boolean) {
        val track = audioTrack
        Log.d(PLAYBACK_TRACE_TAG, "playLegacyStream track=${track?.hashCode()} dataSize=${audioData.size} buffer=$bufferSizeInBytes receivedPkgs=$receivedPkgs flag=$playFromSdk")
        if (track == null) {
            Log.d(PLAYBACK_TRACE_TAG, "playLegacyStream skipped because track is null")
            return
        }
        try {
            track.notificationMarkerPosition = bufferSizeInBytes / 2
            scope.launch {
                if (playFromSdk) {
                    synchronized(track) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try {
                                track.play()
                            } catch (e: IllegalStateException) {
                                Log.e("AudioTrack", "Failed to start playback: ${e.message}")
                                return@synchronized
                            }
                        }
                        if (audioData.isNotEmpty() && audioData.size >= CODEC2_MIN_WRITE_SIZE) {
                            try {
                                val bytesWritten = track.write(audioData, 0, audioData.size)
                                Log.d(PLAYBACK_TRACE_TAG, "playLegacyStream write track=${track.hashCode()} bytesWritten=$bytesWritten playState=${track.playState}")
                                if (bytesWritten < 0) Log.e("AudioTrack", "AudioTrack write failed with code $bytesWritten")
                            } catch (e: IllegalStateException) {
                                Log.e("AudioTrack", "AudioTrack write failed: ${e.message}")
                            }
                        }
                    }
                } else {
                    Log.d(PLAYBACK_TRACE_TAG, "playLegacyStream skipped write because isPlayPttFromSdk=false track=${track.hashCode()}")
                }
            }
        } catch (_: IllegalStateException) {
            ensureLegacyTrack(bufferSizeInBytes, 1.0f)
            track.flush()
        }
    }

    fun releaseLegacyTrack() {
        Log.d(PLAYBACK_TRACE_TAG, "releaseLegacyTrack")
        releaseAudioTrack()
    }

    fun writeMinimumSilenceAndPlay(byteArray: ByteArray, onPlaybackComplete: () -> Unit) {
        val tempTrack = getTempTrack()
        Log.d(PLAYBACK_TRACE_TAG, "writeMinimumSilenceAndPlay size=${byteArray.size} tempTrack=${tempTrack.hashCode()}")
        tempTrack.play()
        tempTrack.write(byteArray, 0, byteArray.size)
        Handler(Looper.getMainLooper()).postDelayed({
            tempTrack.flush()
            tempTrack.release()
            onPlaybackComplete()
        }, 880)
    }

    @SuppressLint("NewApi")
    private fun getTempTrack(): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(CODEC2_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(642)
            .build()
        track.audioSessionId.let {
            enhancer?.release()
            enhancer = LoudnessEnhancer(it)
            val gainmB = Math.round(Math.log10(5.4) * 2000).toInt()
            enhancer?.setTargetGain(gainmB)
            enhancer?.setEnabled(true)
        }
        return track
    }

    // ── BLE routing ──────────────────────────────────────────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun syncBleDevice(context: Context) {
        val audioManager = DataManager.context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager, AudioManager.GET_DEVICES_OUTPUTS, context)
        bleDevice?.let {
            audioTrack?.setPreferredDevice(it)
            
            // Use modern API (31+) or fallback to legacy for older Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): Use modern AudioManager.setCommunicationDevice()
                audioManager.setCommunicationDevice(it)
            } else {
                // Android 11 and below: Use legacy BluetoothSco API
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.setBluetoothScoOn(true)
            }
            
            if (it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                try { routeAudioToMediaRouter(context) } catch (e: Exception) {
                    Log.e(PLAYBACK_TRACE_TAG, "syncBleDevice routeAudioToMediaRouter failed", e)
                }
            }
        }
    }

    private fun routeAudioToMediaRouter(context: Context) {
        val mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    }
}
