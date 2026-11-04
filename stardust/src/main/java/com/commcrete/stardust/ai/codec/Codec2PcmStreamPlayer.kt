package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context.MEDIA_ROUTER_SERVICE
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRouter
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.util.LogOnce
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.StardustLog
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.audio.BleMediaConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Codec2 (8 kHz) PTT playback path.
 *
 * Owns its own AudioTrack + LoudnessEnhancer + BLE audio routing, completely separate
 * from the AI streaming player ([AIPcmStreamPlayer]).
 */
object Codec2PcmStreamPlayer : BleMediaConnector() {

    private const val TAG = "Codec2PcmTrack"
    private const val CODEC2_SAMPLE_RATE = 8000
    private const val CODEC2_MIN_WRITE_SIZE = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var track: AudioTrack? = null
    private var enhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null

    /**
     * Per-packet failures log once per track; reset when [ensureTrack] creates a track and
     * summarised by [releaseTrack].
     */
    private val logOnce = LogOnce()

    @SuppressLint("NewApi")
    fun ensureTrack(bufferSizeInBytes: Int, speedFactor: Float) {
        try {
            if (track == null) {
                track = buildTrack(bufferSizeInBytes, speedFactor)
                syncBleDevice()
                logRepeats()
                StardustLog.i(LogType.PTT, TAG, "Codec2 playback started: track=${track?.hashCode()} buffer=$bufferSizeInBytes")
            }
            attachEnhancer(track)
            Handler(Looper.getMainLooper()).postDelayed({ track?.play() }, 150)
        } catch (e: Exception) {
            StardustLog.e(TAG, "ensureTrack failed", e)
        }
    }

    fun playStream(audioData: ByteArray, bufferSizeInBytes: Int, receivedPkgs: Int, playFromSdk: Boolean) {
        val current = track
        // Per-packet: keep at d.
        StardustLog.d(LogType.PTT, TAG) {
            "playStream track=${current?.hashCode()} dataSize=${audioData.size} buffer=$bufferSizeInBytes receivedPkgs=$receivedPkgs flag=$playFromSdk"
        }
        if (current == null) {
            // An inbound packet dropped: logs once per track, summarised by releaseTrack().
            if (logOnce.first("inbound packet dropped (no track)")) {
                StardustLog.e(TAG, "playStream: no track — inbound packet dropped")
            }
            return
        }

        try {
            current.notificationMarkerPosition = bufferSizeInBytes / 2
            scope.launch {
                if (!playFromSdk) {
                    // Per-packet: keep at d.
                    StardustLog.d(LogType.PTT, TAG) { "playStream skipped because isPlayPttFromSdk=false track=${current.hashCode()}" }
                    return@launch
                }
                writeFrame(current, audioData)
            }
        } catch (e: IllegalStateException) {
            ensureTrack(bufferSizeInBytes, 1.0f)
            current.flush()
        }
    }

    fun releaseTrack() {
        val hadTrack = track != null
        try {
            track?.flush()
            track?.release()
            enhancer?.release()
            equalizer?.release()
        } catch (e: Exception) {
            StardustLog.e(TAG, "releaseTrack failed", e)
        } finally {
            track = null
            enhancer = null
            equalizer = null
        }
        if (hadTrack) StardustLog.i(LogType.PTT, TAG, "Codec2 playback stopped")
        logRepeats()
    }

    /** Summarises the once-logged per-packet failures; every one of them is an e. */
    private fun logRepeats() {
        for ((key, repeats) in logOnce.reset()) {
            StardustLog.e(TAG, "$key x$repeats more")
        }
    }

    fun writeMinimumSilenceAndPlay(byteArray: ByteArray, onPlaybackComplete: () -> Unit) {
        val tempTrack = buildTempTrack()
        StardustLog.d(LogType.PTT, TAG, "writeMinimumSilenceAndPlay size=${byteArray.size} tempTrack=${tempTrack.hashCode()}")
        tempTrack.play()
        tempTrack.write(byteArray, 0, byteArray.size)

        Handler(Looper.getMainLooper()).postDelayed({
            tempTrack.flush()
            tempTrack.release()
            onPlaybackComplete()
        }, 880)
    }

    @SuppressLint("NewApi")
    private fun buildTrack(bufferSizeInBytes: Int, speedFactor: Float): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate((CODEC2_SAMPLE_RATE * speedFactor).toInt())
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSizeInBytes)
            .build()

    @SuppressLint("NewApi")
    private fun buildTempTrack(): AudioTrack {
        val audioTrack = AudioTrack.Builder()
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
        attachEnhancer(audioTrack)
        return audioTrack
    }

    private fun attachEnhancer(audioTrack: AudioTrack?) {
        val sessionId = audioTrack?.audioSessionId ?: return
        enhancer?.release()
        enhancer = LoudnessEnhancer(sessionId).apply {
            val audioPct = 5.4
            val gainmB = Math.round(Math.log10(audioPct) * 2000).toInt()
            setTargetGain(gainmB)
            setEnabled(true)
        }
    }

    private fun writeFrame(track: AudioTrack, audioData: ByteArray) {
        synchronized<Unit>(track) {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    track.play()
                } catch (e: IllegalStateException) {
                    // Logs once per track; summarised by releaseTrack().
                    if (logOnce.first("play() failed")) {
                        StardustLog.e(TAG, "Failed to start playback: ${e.message}")
                    }
                    return
                }
            }

            if (audioData.isEmpty() || audioData.size < CODEC2_MIN_WRITE_SIZE) return

            try {
                track.write(audioData, 0, audioData.size)
            } catch (e: IllegalStateException) {
                // Logs once per track; summarised by releaseTrack().
                if (logOnce.first("AudioTrack write failed")) {
                    StardustLog.e(TAG, "AudioTrack write failed: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun syncBleDevice() {
        val audioManager = DataManager.appContext.getSystemService(AudioManager::class.java) ?: return
        val bleDevice = getPreferredDevice(audioManager, AudioManager.GET_DEVICES_OUTPUTS) ?: return

        track?.setPreferredDevice(bleDevice)
        audioManager.setCommunicationDevice(bleDevice)
        audioManager.setBluetoothScoOn(true)
        if (bleDevice.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
            try {
                routeAudioToMediaRouter()
            } catch (e: Exception) {
                StardustLog.e(TAG, "syncBleDevice routeAudioToMediaRouter failed", e)
            }
        }
    }

    private fun routeAudioToMediaRouter() {
        val mediaRouter = DataManager.appContext.getSystemService(MEDIA_ROUTER_SERVICE) as MediaRouter
        mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    }
}

