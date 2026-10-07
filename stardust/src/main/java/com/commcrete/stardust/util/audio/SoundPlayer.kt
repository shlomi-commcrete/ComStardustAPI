package com.commcrete.stardust.util.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
     * @param volume   linear volume in [0, 1], relative to [channel]'s
     *                 phone volume. Defaults to full (1f).
     * @param channel  which phone volume slider the sound follows. PTT
     *                 tones always pass [SoundChannel.SYSTEM].
     * @param onDone   optional callback invoked on the main thread when
     *                 playback finishes or fails.
     */
    fun play(
        context: Context,
        @RawRes resId: Int,
        volume: Float = 1f,
        channel: SoundChannel = SoundChannel.MEDIA,
        onDone: (() -> Unit)? = null,
    ) {
        val appCtx = context.applicationContext
        mainHandler.post {
            playOnMain(appCtx, resId, volume, channel, onDone)
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
        channel: SoundChannel = SoundChannel.MEDIA,
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
    ) {
        val finished = CompletableDeferred<Unit>()
        play(context, resId, volume, channel) { finished.complete(Unit) }
        if (withTimeoutOrNull(timeoutMs) { finished.await() } == null) {
            Log.w(TAG, "res=$resId did not finish within ${timeoutMs}ms — continuing")
        }
    }

    private fun playOnMain(
        context: Context,
        @RawRes resId: Int,
        volume: Float,
        channel: SoundChannel,
        onDone: (() -> Unit)?,
    ) {
        val attributes = channel.audioAttributes()
        val mp = createPlayer(context, resId, attributes) ?: run {
            onDone?.invoke()
            return
        }

        val clampedVol = volume.coerceIn(0f, 1f)
        mp.setVolume(clampedVol, clampedVol)

        // Briefly duck whatever else is playing so the tone is not lost under it. Denied focus (e.g.
        // during a call) does not stop the tone — it only means nothing is ducked.
        val focus = requestFocus(context, attributes)
        val finish = {
            focus?.let { abandonFocus(context, it) }
            onDone?.invoke()
        }

        mp.setOnCompletionListener { player ->
            runCatching { player.release() }
            finish()
        }
        mp.setOnErrorListener { player, what, extra ->
            Log.w(TAG, "playback error what=$what extra=$extra res=$resId")
            runCatching { player.release() }
            finish()
            true
        }

        runCatching { mp.start() }
            .onFailure {
                Log.w(TAG, "mp.start() failed for res=$resId", it)
                runCatching { mp.release() }
                finish()
            }
    }

    private fun requestFocus(context: Context, attributes: AudioAttributes): AudioFocusRequest? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        val result = runCatching { audioManager.requestAudioFocus(request) }
            .onFailure { Log.w(TAG, "requestAudioFocus threw", it) }
            .getOrNull()
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "audio focus not granted (result=$result) — playing without ducking")
            return null
        }
        return request
    }

    private fun abandonFocus(context: Context, request: AudioFocusRequest) {
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
            .onFailure { Log.w(TAG, "abandonAudioFocusRequest threw", it) }
    }

    /**
     * Resolve [resId] against the plugin's resource table first, then the caller's context.
     *
     * Both are attempted because the two build shapes disagree about which one owns the id: inside an
     * ATAK plugin only [DataManager.pluginContext] has it, while in a standalone app the plugin context
     * may be uninitialized (its accessor throws) or simply be the same context. A create returning null
     * is not an error worth failing on — it is the normal "this context cannot see that resource".
     */
    private fun createPlayer(fallback: Context, @RawRes resId: Int, attributes: AudioAttributes): MediaPlayer? {
        val plugin = runCatching { DataManager.pluginContext }.getOrNull()
        if (plugin != null && plugin !== fallback) {
            create(plugin, resId, attributes)?.let { return it }
            Log.w(TAG, "res=$resId not playable from the plugin context — trying the caller's")
        }
        return create(fallback, resId, attributes) ?: run {
            Log.w(TAG, "could not prepare res=$resId — nothing will play")
            null
        }
    }

    /**
     * Built by hand instead of [MediaPlayer.create], which prepares the player before attributes can be
     * set — and attributes set after prepare are ignored, leaving every sound on the media stream.
     */
    private fun create(context: Context, @RawRes resId: Int, attributes: AudioAttributes): MediaPlayer? {
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(attributes)
            // Null when the resource is stored compressed in the APK; raw mp3s are not.
            val afd = context.resources.openRawResourceFd(resId)
                ?: error("openRawResourceFd returned null")
            afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.prepare()
            mp
        }.onFailure {
            Log.w(TAG, "MediaPlayer setup failed for res=$resId on $context", it)
            runCatching { mp.release() }
        }.getOrNull()
    }
}

