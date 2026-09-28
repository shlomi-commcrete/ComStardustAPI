package com.commcrete.stardust.util.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.annotation.RawRes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Fire-and-forget one-shot sound player for short UI feedback sounds
 * (beeps, tones, clicks).
 *
 * Intentionally separate from [PlayerUtils.mediaPlayer] which is shared
 * by the PTT receive playback path — using the same instance for beeps
 * would interrupt incoming PTT audio.
 *
 * Each [play] call creates its own [MediaPlayer] instance, starts it,
 * and self-releases on completion or error. Concurrent calls produce
 * independent players that don't interfere with each other.
 *
 * [MediaPlayer.create] must be called on a thread with a [Looper]
 * (typically main). All calls are automatically dispatched to the main
 * thread, so callers may invoke [play] from any thread including
 * coroutines on [kotlinx.coroutines.Dispatchers.Default].
 */
object SoundPlayer {

    private const val TAG = "SoundPlayer"

    /**
     * Ceiling on [playAndAwait]. [play]'s `onDone` covers completion, playback error and a failed
     * create/start, but a [MediaPlayer] that reports none of the three would otherwise suspend the
     * caller forever — and the caller is typically holding something back until the sound ends.
     */
    private const val AWAIT_TIMEOUT_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Play [resId] once and release. No-op if [MediaPlayer.create]
     * fails (e.g. resource missing, audio focus denied).
     *
     * @param context  used only for resource lookup — prefer
     *                 applicationContext to avoid leaking activities.
     * @param resId    raw resource id, e.g. `R.raw.ptt_finished_beep`.
     * @param volume   linear volume in [0, 1]. Defaults to full (1f).
     * @param onDone   optional callback invoked on the main thread when
     *                 playback finishes or fails.
     */
    fun play(
        context: Context,
        @RawRes resId: Int,
        volume: Float = 1f,
        onDone: (() -> Unit)? = null,
    ) {
        val appCtx = context.applicationContext
        mainHandler.post {
            playOnMain(appCtx, resId, volume, onDone)
        }
    }

    /**
     * [play], but suspending until the sound has finished (or failed, or [AWAIT_TIMEOUT_MS] elapsed).
     *
     * For callers that must not proceed while the sound is playing — the PTT start beep waits here so
     * the microphone opens only once it is over and the beep is never part of the recording.
     *
     * Returns rather than throwing on timeout: a sound that will not finish must not hold up whatever
     * was waiting on it.
     */
    suspend fun playAndAwait(
        context: Context,
        @RawRes resId: Int,
        volume: Float = 1f,
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
    ) {
        val finished = CompletableDeferred<Unit>()
        play(context, resId, volume) { finished.complete(Unit) }
        if (withTimeoutOrNull(timeoutMs) { finished.await() } == null) {
            Timber.tag(TAG).w("res=$resId did not finish within ${timeoutMs}ms — continuing")
        }
    }

    private fun playOnMain(
        context: Context,
        @RawRes resId: Int,
        volume: Float,
        onDone: (() -> Unit)?,
    ) {
        val mp = runCatching { MediaPlayer.create(context, resId) }
            .onFailure { Timber.tag(TAG).w(it, "MediaPlayer.create failed for res=$resId") }
            .getOrNull() ?: run {
            onDone?.invoke()
            return
        }

        val clampedVol = volume.coerceIn(0f, 1f)
        mp.setVolume(clampedVol, clampedVol)

        mp.setOnCompletionListener { player ->
            runCatching { player.release() }
            onDone?.invoke()
        }
        mp.setOnErrorListener { player, what, extra ->
            Timber.tag(TAG).w("playback error what=$what extra=$extra res=$resId")
            runCatching { player.release() }
            onDone?.invoke()
            true
        }

        runCatching { mp.start() }
            .onFailure {
                Timber.tag(TAG).w(it, "mp.start() failed for res=$resId")
                runCatching { mp.release() }
                onDone?.invoke()
            }
    }
}

