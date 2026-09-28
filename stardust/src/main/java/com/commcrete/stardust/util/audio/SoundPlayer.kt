package com.commcrete.stardust.util.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RawRes
import com.commcrete.stardust.util.DataManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * **Resource lookup goes through [DataManager.pluginContext], not the
 * caller's context.** Every sound here is an `R.raw` of THIS library, and
 * inside an ATAK plugin the host's application context resolves ids
 * against the HOST's resource table, where those ids do not exist —
 * `MediaPlayer.create` then just returns null and nothing plays. Same
 * reason `WavTokenizerEncoder`/`Decoder` open their models through
 * `pluginContext.assets`. The caller's context stays as the fallback, so
 * a standalone (non-plugin) build is unaffected.
 *
 * Logging is [android.util.Log], deliberately, not Timber: the SDK never
 * calls `Timber.plant()` (see `UsbDiag`), so a Timber warning here is a
 * no-op — which is exactly why the failure above was silent in logcat as
 * well as in the speaker.
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
     * @param context  fallback for resource lookup only — the plugin
     *                 context is preferred (see the class doc). Prefer
     *                 an application context to avoid leaking activities.
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
            Log.w(TAG, "res=$resId did not finish within ${timeoutMs}ms — continuing")
        }
    }

    private fun playOnMain(
        context: Context,
        @RawRes resId: Int,
        volume: Float,
        onDone: (() -> Unit)?,
    ) {
        val mp = createPlayer(context, resId) ?: run {
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
            Log.w(TAG, "playback error what=$what extra=$extra res=$resId")
            runCatching { player.release() }
            onDone?.invoke()
            true
        }

        runCatching { mp.start() }
            .onFailure {
                Log.w(TAG, "mp.start() failed for res=$resId", it)
                runCatching { mp.release() }
                onDone?.invoke()
            }
    }

    /**
     * Resolve [resId] against the plugin's resource table first, then the caller's context.
     *
     * Both are attempted because the two build shapes disagree about which one owns the id: inside an
     * ATAK plugin only [DataManager.pluginContext] has it, while in a standalone app the plugin context
     * may be uninitialized (its accessor throws) or simply be the same context. A create returning null
     * is not an error worth failing on — it is the normal "this context cannot see that resource".
     */
    private fun createPlayer(fallback: Context, @RawRes resId: Int): MediaPlayer? {
        val plugin = runCatching { DataManager.pluginContext }.getOrNull()
        if (plugin != null && plugin !== fallback) {
            create(plugin, resId)?.let { return it }
            Log.w(TAG, "res=$resId not playable from the plugin context — trying the caller's")
        }
        return create(fallback, resId) ?: run {
            Log.w(TAG, "MediaPlayer.create returned null for res=$resId — nothing will play")
            null
        }
    }

    private fun create(context: Context, @RawRes resId: Int): MediaPlayer? =
        runCatching { MediaPlayer.create(context, resId) }
            .onFailure { Log.w(TAG, "MediaPlayer.create threw for res=$resId on $context", it) }
            .getOrNull()
}

