package com.commcrete.stardust.util.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Short synthesized tones for PTT feedback, generated rather than shipped as a raw resource: the
 * tone's pitch, length and level are the thing being tuned, and at these lengths an asset would be
 * mostly container overhead. [SoundPlayer] stays the right tool for real recorded sounds.
 *
 * [play] SUSPENDS until the tone has finished, which is what lets the caller sequence it *before*
 * opening the microphone — see [playPttStartTone].
 */
object TonePlayer {

    private const val TAG = "TonePlayer"

    private const val SAMPLE_RATE = 44_100
    private const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** Linear ramp at each end. Starting a sine at full amplitude is heard as a click, not a beep. */
    private const val FADE_MS = 5

    /** Margin on the wait so the last samples are actually out of the mixer before `stop()`. */
    private const val DRAIN_MARGIN_MS = 40L

    // ── PTT start tone ────────────────────────────────────────────────────────────────────────────
    // Deliberately short: the microphone does not open until this has finished, so every millisecond
    // here is dead time on a PTT press. 1 kHz sits in the middle of every speaker's usable range and
    // is clearly distinct from the longer, lower end-of-PTT beep (R.raw.ptt_finished_beep).
    private const val PTT_START_FREQ_HZ = 1_000
    private const val PTT_START_DURATION_MS = 150
    private const val PTT_START_VOLUME = 0.7f

    /**
     * The "you may speak" tone. Suspends for roughly [PTT_START_DURATION_MS] plus a short drain.
     *
     * Callers play this before opening the microphone, so it is never captured, encoded, transmitted
     * or written into the local mirror WAV.
     */
    suspend fun playPttStartTone() {
        play(
            frequencyHz = PTT_START_FREQ_HZ,
            durationMs = PTT_START_DURATION_MS,
            volume = PTT_START_VOLUME,
        )
    }

    /**
     * Play a [frequencyHz] sine for [durationMs] at [volume] (linear, 0..1) and suspend until it has
     * finished. Never throws: a device that will not give up an [AudioTrack] costs the tone, not the
     * recording it precedes.
     */
    suspend fun play(frequencyHz: Int, durationMs: Int, volume: Float) {
        if (durationMs <= 0 || frequencyHz <= 0 || volume <= 0f) return

        val samples = runCatching { sine(frequencyHz, durationMs, volume.coerceAtMost(1f)) }
            .getOrElse {
                Timber.tag(TAG).w(it, "tone synthesis failed (${frequencyHz}Hz, ${durationMs}ms)")
                return
            }

        val track = runCatching { buildTrack(samples.size * 2) }
            .getOrElse {
                Timber.tag(TAG).w(it, "AudioTrack unavailable — skipping tone")
                return
            }

        try {
            // MODE_STATIC: the whole tone is handed over in one write before playback starts, so there
            // is no streaming loop to keep fed and no underrun to hear.
            val written = track.write(samples, 0, samples.size)
            if (written < samples.size) {
                Timber.tag(TAG).w("short tone write ($written of ${samples.size}) — playing anyway")
            }
            track.play()
            delay(durationMs + DRAIN_MARGIN_MS)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "tone playback failed")
        } finally {
            // Ordered stop-then-release, and each guarded: release() on a track that refused to stop
            // still has to happen or the audio flinger client leaks for the life of the process.
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /**
     * One mono PCM16 sine, faded in and out over [FADE_MS] at each end. The fade is clamped to half
     * the tone so a very short request still ramps symmetrically instead of overlapping itself.
     */
    private fun sine(frequencyHz: Int, durationMs: Int, volume: Float): ShortArray {
        val total = (SAMPLE_RATE.toLong() * durationMs / 1000L).toInt().coerceAtLeast(1)
        val fade = ((SAMPLE_RATE.toLong() * FADE_MS / 1000L).toInt())
            .coerceIn(1, (total / 2).coerceAtLeast(1))
        val step = 2.0 * PI * frequencyHz / SAMPLE_RATE
        val peak = Short.MAX_VALUE * volume

        return ShortArray(total) { i ->
            val envelope = when {
                i < fade -> i.toDouble() / fade
                i >= total - fade -> (total - 1 - i).toDouble() / fade
                else -> 1.0
            }
            (sin(step * i) * peak * envelope)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    /**
     * SONIFICATION attributes, not MEDIA: this is a UI feedback sound, so it should follow the
     * notification/system routing and volume rather than duck as if it were content.
     */
    private fun buildTrack(bufferBytes: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bufferBytes)
            .build()
}