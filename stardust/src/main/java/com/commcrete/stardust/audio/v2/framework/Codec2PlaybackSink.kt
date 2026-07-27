package com.commcrete.stardust.audio.v2.framework

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.commcrete.stardust.audio.v2.application.port.PlaybackSink
import com.commcrete.stardust.audio.v2.domain.Gain
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import java.util.concurrent.atomic.AtomicReference

/**
 * Framework ring — one AudioTrack per receive stream behind the [PlaybackSink] port (R6). Ported from
 * `Codec2PcmStreamPlayer`, but per-instance (not a singleton) so each incoming PTT has its own track
 * and its own volume; the OS mixer sums them to the output device.
 *
 * Volume is applied via `AudioTrack.setVolume` off the decode path — muting one stream never blocks
 * another. Gain set before [open] is remembered and applied at track creation.
 *
 * TODO(sco): BLE-SCO routing / setCommunicationDevice is intentionally NOT done here — it is a single
 * process-global route (open decision #5) that a future owner should manage once, not per sink.
 */
class Codec2PlaybackSink : PlaybackSink {

    private var track: AudioTrack? = null
    private var enhancer: LoudnessEnhancer? = null
    private val gainRef = AtomicReference(Gain.UNITY)

    @SuppressLint("NewApi")
    override fun open(sampleRateHz: Int) {
        if (track != null) return
        val minBuffer = AudioTrack.getMinBufferSize(sampleRateHz, CHANNEL, ENCODING)
        val bufferBytes = maxOf(minBuffer, sampleRateHz) // ~0.5 s of 16-bit mono
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(CHANNEL)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        track = built
        attachEnhancer(built)
        applyGain(built, gainRef.get())
        built.play()
    }

    override suspend fun write(pcm: PcmChunk) {
        val current = track ?: return
        val samples = pcm.samples
        if (samples.isEmpty()) return
        var offset = 0
        while (offset < samples.size) {
            val written = current.write(samples, offset, samples.size - offset)
            if (written <= 0) break else offset += written
        }
    }

    override fun setGain(gain: Gain) {
        gainRef.set(gain)
        track?.let { applyGain(it, gain) }
    }

    override fun close() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { enhancer?.release() }
        track = null
        enhancer = null
    }

    private fun applyGain(t: AudioTrack, g: Gain) {
        runCatching { t.setVolume(g.value.coerceIn(0f, 1f)) }
    }

    private fun attachEnhancer(t: AudioTrack) {
        runCatching {
            enhancer = LoudnessEnhancer(t.audioSessionId).apply {
                val gainMb = Math.round(Math.log10(LOUDNESS_PCT) * 2000).toInt()
                setTargetGain(gainMb)
                enabled = true
            }
        }.onFailure { Log.w(TAG, "LoudnessEnhancer unavailable", it) }
    }

    private companion object {
        const val TAG = "Codec2PlaybackSink"
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val LOUDNESS_PCT = 5.4
    }
}
