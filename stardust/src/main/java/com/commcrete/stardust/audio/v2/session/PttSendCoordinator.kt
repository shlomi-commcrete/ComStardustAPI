package com.commcrete.stardust.audio.v2.session

import android.content.Context
import com.commcrete.stardust.audio.v2.capture.CaptureSource
import com.commcrete.stardust.audio.v2.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.dsp.AiRecorderProfileV2
import com.commcrete.stardust.audio.v2.mirror.LocalMirror
import com.commcrete.stardust.audio.v2.mirror.NoOpLocalMirror
import com.commcrete.stardust.audio.v2.transport.SendTransport
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.RecorderUtils
import timber.log.Timber
import java.io.File

/**
 * Codec-agnostic replacement for [com.commcrete.stardust.ai.codec.PttSendManager]
 * AND the implicit single-instance lifecycle of
 * [com.commcrete.stardust.util.audio.AudioRecorderCodec2].
 *
 * # Mental model
 *
 *  - One [RecordingSession] per PTT key-down.
 *  - The coordinator tracks the **current** session so a new
 *    [restart] auto-finalizes the previous one (preserves the
 *    safety net at `PttSendManager.restart`:251 that catches "user
 *    forgot to call stopRecording").
 *  - [finish] / [awaitFinalized] forward to the current session.
 *  - All actual work happens inside the session — the coordinator
 *    is just a slot.
 *
 * # Concurrency
 *
 *  - `sessionsLock` is a plain `Any()` monitor — atomic swap of
 *    `currentSession` is the only critical section.
 *  - Multiple sessions can be in different stages of finalization
 *    simultaneously (e.g. orphan session draining while a new one is
 *    capturing). They're per-codec-mutex-serialized inside
 *    [RecordingSession.runEncodeLoop], not at this layer.
 */
object PttSendCoordinator {

    private const val TAG = "PttSendCoordinator"

    private val sessionsLock = Any()

    /**
     * The currently active session. Held statically because PTT is
     * inherently single-active-recording at a time. The lint warning
     * about "static reference to Context" is acknowledged — the
     * legacy [com.commcrete.stardust.ai.codec.PttSendManager] has the
     * same shape, and the session releases its context reference as
     * soon as [RecordingSession.finalizeNow] runs. Callers SHOULD use
     * `applicationContext` to be safe.
     */
    @Suppress("StaticFieldLeak")
    @Volatile private var currentSession: RecordingSession? = null

    /**
     * Start a new recording session.
     *
     *  - Any previously-current session is detached from
     *    [currentSession] and (if not already finishing) auto-finalized
     *    via [finish] — mirrors the orphan-cleanup behavior of
     *    `PttSendManager.restart`.
     *  - The new session's [RecordingSession.start] is invoked
     *    synchronously so capture begins before this call returns.
     *
     * @return the new session handle. Hand it to [finish] / [awaitFinalized]
     *         when you want guaranteed finalization of THIS recording
     *         even after a newer one has begun.
     */
    fun restart(
        context: Context,
        codeType: RecorderUtils.CODE_TYPE,
        capture: CaptureSource,
        transport: SendTransport,
        profile: AiRecorderProfileV2,
        carrier: Carrier?,
        source: String,
        destination: String?,
        targetFile: File?,
        chatId: String?,
        chunkDurationMs: Int,
        mirror: LocalMirror = NoOpLocalMirror,
        onTimeoutReached: () -> Unit = {},
        onSavePtt: suspend (chatId: String, file: File?) -> Unit = { _, _ -> },
    ): RecordingSession {
        val codec = CodecRegistry.get(codeType)
        val session = RecordingSession(
            id = RecordingSession.idGen.incrementAndGet(),
            codec = codec,
            context = context,
            capture = capture,
            transport = transport,
            mirror = mirror,
            profile = profile,
            carrier = carrier,
            source = source,
            destination = destination,
            targetFile = targetFile,
            chatId = chatId,
            chunkDurationMs = chunkDurationMs,
            // Wall-clock max recording duration — same setting both codecs honor.
            // Default 45 000 ms via getPTTTimeout(ctx) which returns sec * 1000.
            maxDurationMs = SharedPreferencesUtil.getPTTTimeout(context).toLong(),
            onTimeoutReached = onTimeoutReached,
            onSavePtt = onSavePtt,
        )

        val orphan = synchronized(sessionsLock) {
            val prev = currentSession
            currentSession = session
            prev
        }
        if (orphan != null && !orphan.isFinishRequested) {
            Timber.tag(TAG).w(
                "restart(): previous session ${orphan.id} was never finished — auto-finalizing"
            )
            orphan.finish()
        }

        session.start()
        Timber.tag(TAG).d("restart() -> new session ${session.id} (codec=${codec.id.name})")
        return session
    }

    /**
     * Finish the **current** session if any. Idempotent — calling
     * twice or when no session is active is a no-op.
     */
    fun finish() {
        val session = synchronized(sessionsLock) {
            val s = currentSession
            if (s != null) currentSession = null
            s
        } ?: return
        Timber.tag(TAG).d("finish() — finalizing session ${session.id}")
        session.finish()
    }

    /**
     * Finish the specific [session] even if a newer one has since
     * become current. Idempotent. Use this when you captured a
     * session handle and need to guarantee finalization of THAT
     * recording (the legacy `PttSendManager.finish(PttSession)`
     * overload covers the same case).
     */
    fun finish(session: RecordingSession) {
        synchronized(sessionsLock) {
            if (currentSession === session) currentSession = null
        }
        session.finish()
    }

    /**
     * Suspend until [session] has fully completed — capture stopped,
     * encoder flushed, mirror file written, message row saved.
     * Returns immediately if it's already finalized.
     */
    suspend fun awaitFinalized(session: RecordingSession) {
        session.awaitFinalized()
    }

    /**
     * The current active session, if any. Mostly for diagnostics —
     * production code should hold a handle from [restart] rather
     * than poll this.
     */
    fun currentSessionOrNull(): RecordingSession? = currentSession
}


