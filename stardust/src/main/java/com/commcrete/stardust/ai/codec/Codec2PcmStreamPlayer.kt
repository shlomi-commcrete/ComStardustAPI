package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context
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
import android.util.Log
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

    @SuppressLint("NewApi")
    fun ensureTrack(bufferSizeInBytes: Int, speedFactor: Float) {
        try {
            if (track == null) {
                track = buildTrack(bufferSizeInBytes, speedFactor)
                syncBleDevice(DataManager.appContext)
                Log.d(TAG, "ensureTrack created track=${track?.hashCode()} buffer=$bufferSizeInBytes")
            }
            attachEnhancer(track)
            Handler(Looper.getMainLooper()).postDelayed({ track?.play() }, 150)
        } catch (e: Exception) {
            Log.e(TAG, "ensureTrack failed", e)
        }
    }

    fun playStream(audioData: ByteArray, bufferSizeInBytes: Int, receivedPkgs: Int, playFromSdk: Boolean) {
        val current = track
        Log.d(TAG, "playStream track=${current?.hashCode()} dataSize=${audioData.size} buffer=$bufferSizeInBytes receivedPkgs=$receivedPkgs flag=$playFromSdk")
        if (current == null) {
            Log.d(TAG, "playStream skipped because track is null")
            return
        }

        try {
            current.notificationMarkerPosition = bufferSizeInBytes / 2
            scope.launch {
                if (!playFromSdk) {
                    Log.d(TAG, "playStream skipped because isPlayPttFromSdk=false track=${current.hashCode()}")
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
        try {
            track?.flush()
            track?.release()
            enhancer?.release()
            equalizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "releaseTrack failed", e)
        } finally {
            track = null
            enhancer = null
            equalizer = null
        }
    }

    fun writeMinimumSilenceAndPlay(byteArray: ByteArray, onPlaybackComplete: () -> Unit) {
        val tempTrack = buildTempTrack()
        Log.d(TAG, "writeMinimumSilenceAndPlay size=${byteArray.size} tempTrack=${tempTrack.hashCode()}")
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
        synchronized(track) {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    track.play()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Failed to start playback: ${e.message}")
                    return
                }
            }

            if (audioData.isEmpty() || audioData.size < CODEC2_MIN_WRITE_SIZE) return

            try {
                track.write(audioData, 0, audioData.size)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack write failed: ${e.message}")
            }
        }
    }

    @SuppressLint("NewApi")
    private fun syncBleDevice(context: Context) {
        val audioManager = DataManager.appContext.getSystemService(AudioManager::class.java) ?: return
        val bleDevice = getPreferredDevice(audioManager, AudioManager.GET_DEVICES_OUTPUTS) ?: return

        track?.setPreferredDevice(bleDevice)
        audioManager.setCommunicationDevice(bleDevice)
        audioManager.setBluetoothScoOn(true)
        if (bleDevice.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
            try {
                routeAudioToMediaRouter(context)
            } catch (e: Exception) {
                Log.e(TAG, "syncBleDevice routeAudioToMediaRouter failed", e)
            }
        }
    }

    private fun routeAudioToMediaRouter(context: Context) {
        val mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    }
}

