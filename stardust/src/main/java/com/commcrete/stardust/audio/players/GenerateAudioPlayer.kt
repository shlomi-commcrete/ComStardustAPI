package com.commcrete.stardust.audio.players

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import com.commcrete.aiaudio.media.PcmStreamPlayer
import com.commcrete.stardust.util.audio.PlayerUtils

class GenerateAudioPlayer {

    fun generateAudioPlayer(): AudioTrack? {
        val sampleRate = 8000
        val bufferingDelay = 0.5f
        var audioTrack: AudioTrack? = null
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
        audioTrack?.audioSessionId?.let {
//                Equalizer().getEq(it, DataManager.context)
            PlayerUtils.enhancer = LoudnessEnhancer(it)
            val audioPct = 5.4
            val gainmB = Math.round(Math.log10(audioPct) * 2000).toInt()
            PlayerUtils.enhancer?.setTargetGain(gainmB)
            PlayerUtils.enhancer?.setEnabled(true)
        }
        return audioTrack
    }

    fun generateAudioPlayerAI(): AudioTrack? {
        val sampleRate = 24000
        val bufferingDelay = 0.5f
        var audioTrack: AudioTrack? = null
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
        return audioTrack
    }
}