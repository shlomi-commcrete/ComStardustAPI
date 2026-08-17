package com.commcrete.stardust.audio.v2.framework

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.commcrete.stardust.audio.v2.application.port.PlaybackSink
import com.commcrete.stardust.audio.v2.domain.Gain
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import java.util.concurrent.atomic.AtomicReference

/**
 * Framework ring — one AudioTrack per WavTokenizer receive stream (R6). Matches legacy
 * `AIPcmStreamPlayer`'s track config (24 kHz, USAGE_MEDIA + CONTENT_TYPE_SPEECH, no LoudnessEnhancer),
 * but per-instance so each incoming AI PTT has its own independently-controllable volume.
 *
 * Volume via `AudioTrack.setVolume` off the decode path; a level set before [open] applies at creation.
 */
class AiPlaybackSink : PlaybackSink {

    private var track: AudioTrack? = null
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
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(CHANNEL)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        track = built
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
        track = null
    }

    private fun applyGain(t: AudioTrack, gain: Gain) {
        runCatching { t.setVolume(gain.value.coerceIn(0f, 1f)) }
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
