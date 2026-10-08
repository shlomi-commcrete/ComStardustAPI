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
 * Framework ring — one AudioTrack per WavTokenizer receive stream (R6). Matches legacy
 * `AIPcmStreamPlayer`'s track config (24 kHz, USAGE_MEDIA + CONTENT_TYPE_SPEECH, no LoudnessEnhancer),
 * but per-instance so each incoming AI PTT has its own independently-controllable volume.
 *
 * Volume via `AudioTrack.setVolume` off the decode path; a level set before [open] applies at creation.
 *
 * A gain above unity is a boost: the track stays at 1 and a [LoudnessEnhancer] adds
 * `2000·log10(gain)` mB. Unlike [Codec2PlaybackSink] there is no base lift, so the enhancer is
 * enabled only while a boost is asked for — at unity this stream sounds exactly as before.
 */
class AiPlaybackSink : PlaybackSink {

    private var track: AudioTrack? = null
    /** Volatile: built on the receive path in [open], retuned from the UI thread in [setGain]. */
    @Volatile private var enhancer: LoudnessEnhancer? = null
    private val gainRef = AtomicReference(Gain.UNITY)
    private var sampleRateHz = 0
    /** Frames handed to the track so far (mono 16-bit: one sample per frame) — what [drain] waits for. */
    @Volatile private var framesWritten = 0L

    @SuppressLint("NewApi")
    override fun open(sampleRateHz: Int) {
        if (track != null) return
        this.sampleRateHz = sampleRateHz
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
        // Here, not on the first boost: attaching an effect is a call into the audio server, and
        // setGain runs on the host's UI thread. Disabled until a gain above unity asks for it.
        enhancer = runCatching { LoudnessEnhancer(built.audioSessionId) }
            .onFailure { Log.w(TAG, "LoudnessEnhancer unavailable", it) }
            .getOrNull()
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
        framesWritten += offset
    }

    override suspend fun drain() {
        track?.playOutAndStop(framesWritten, sampleRateHz)
    }

    override fun setGain(gain: Gain) {
        gainRef.set(gain)
        track?.let { applyGain(it, gain) }
    }

    override val underrunCount: Int
        get() = track?.let { runCatching { it.underrunCount }.getOrNull() } ?: -1

    override fun close() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { enhancer?.release() }
        track = null
        enhancer = null
    }

    private fun applyGain(t: AudioTrack, gain: Gain) {
        runCatching { t.setVolume(gain.value.coerceIn(0f, 1f)) }
        enhancer?.let { fx ->
            val boost = gain.boostMb()
            runCatching {
                fx.setTargetGain(boost)
                fx.enabled = boost > 0
            }.onFailure { Log.w(TAG, "LoudnessEnhancer gain not applied", it) }
        }
    }

    private companion object {
        const val TAG = "AiPlaybackSink"
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
