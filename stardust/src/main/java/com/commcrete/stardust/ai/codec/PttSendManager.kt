package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.commcrete.stardust.util.audio.AudioRecordingKeepAlive
import com.commcrete.stardust.util.audio.PttAudioProcessor
import com.commcrete.stardust.util.audio.SoundPlayer

/**
 * Immutable snapshot of one recorder chunk awaiting pre-encode DSP processing —
 * queued by [PttSendManager.addRawFrame], consumed by
 * [PttSendManager.launchDspProcessingLoop].
 */
internal data class RawAiChunk(
    val pcmArray: ShortArray,
    val nativeRate: Int,
    val enableNoiseCancellation: Boolean,
    val isFinal: Boolean,
)

/**
 * Per-recording session handle returned by [PttSendManager.restart].
 *
 * Each session owns its own encode queue, frame buffer, decoder continuity
 * state and target file, so a new recording can begin before the previous
 * one has finished encoding/saving without losing or corrupting either.
 *
 * Pass the handle back into [PttSendManager.finish] to ensure the correct
 * session is finalized even if [PttSendManager.restart] has since been
 * called again for a newer recording.
 */
class PttSession internal constructor(
    val id: Long,
    internal val context: Context,
) {
    /**
     * Raw (not yet DSP-processed) chunks handed off by the live recorder via
     * [PttSendManager.addRawFrame]. Drained by this session's own [dspJob]
     * ([PttSendManager.launchDspProcessingLoop]), which runs the full
     * [com.commcrete.stardust.util.audio.PttAudioProcessor.process] chain
     * (HPF/Notch/RNNoise/AGC/Dynamics/resample) off the recorder's own
     * capture thread — so a slow RNNoise forward pass delays only this
     * session's own encode+send pipeline, never the next `AudioRecord.read()`
     * on the live mic. Feeds [queue] once processed.
     */
    internal val rawQueue = Channel<RawAiChunk>(Channel.UNLIMITED)
    internal var dspJob: Job? = null

    /** Fed by [dspJob] (live recording) or directly by [PttSendManager.addNewFrame]
     * (the [com.commcrete.stardust.util.audio.AudioFeederEngine] test-feeder path,
     * which already runs [com.commcrete.stardust.util.audio.PttAudioProcessor.process]
     * itself off the live mic path). */
    internal val queue = Channel<ShortArray>(Channel.UNLIMITED)
    internal val frameBuffer = mutableListOf<ShortArray>()
    @Volatile internal var file: File? = null
    @Volatile internal var carrier: Carrier? = null
    @Volatile internal var chatId: String? = null
    @Volatile internal var receiverId: String? = null
    internal var lastTokens: List<Long>? = null
    internal var lastPCM: ShortArray? = null
    internal var recordingTs: Long = 0L

    /**
     * Packed chunks awaiting mirror-decode, drained by [PttSendManager]'s
     * per-session mirror job. Kept off the main encode/send loop so a slow
     * decoder forward pass can't delay the next chunk's transmission — see
     * [PttSendManager.launchMirrorDecodeLoop].
     */
    internal val mirrorQueue = Channel<ByteArray>(Channel.UNLIMITED)
    internal var mirrorJob: Job? = null

    /**
     * This session's own snapshot of [WavTokenizerDecoder]'s internal
     * state (`index`/`cutTokens`/`loop`), saved/restored around every
     * mirror-decode call so it can multiplex over the shared decoder
     * singleton alongside concurrent [PttReceiveManager] streams and the
     * encode loop, the same way [PttReceiveManager]'s per-stream state
     * does for incoming audio.
     */
    internal var decoderState: WavTokenizerDecoder.InternalState = WavTokenizerDecoder.InternalState.INITIAL


    /**
     * Atomic so concurrent calls to [PttSendManager.finish] (e.g. the
     * delayed `finishAIRecording` coroutine racing the legacy
     * `finish(Context)` path) cannot both pass the guard and
     * double-close the queue / log the "queue closed" line twice.
     */
    internal val finishRequested = AtomicBoolean(false)

    /**
     * Atomic guard for the file-write path. Even though only one job
     * runs per session today, an atomic CAS makes finalization
     * unambiguously single-shot under any future code path or
     * cancellation order.
     */
    internal val finalized = AtomicBoolean(false)

    /**
     * Number of packets transmitted so far in this session. Used by
     * the per-session max-PTT-timeout check in
     * [PttSendManager.handleTokenizerChunk] — direct analogue of
     * `AudioRecorderCodec2.numOfPackage`.
     */
    internal var numPacketsSent: Int = 0

    /**
     * Wall-clock time the session's encode loop started consuming chunks.
     * Used by [PttSendManager.handleTokenizerChunk] to compute how far
     * real-time processing has drifted behind the audio it's processing
     * (diagnostic only — see PTT_LATENCY_TRACE_TAG logs).
     */
    internal var loopStartMs: Long = 0L

    /**
     * Single-shot hook fired when the session's accumulated wall-clock
     * audio exceeds [SharedPreferencesUtil.getPTTTimeout]. Set by the
     * host (currently
     * [com.commcrete.stardust.util.audio.RecorderUtils.setupAIRecorder])
     * to stop the AudioRecorderAI from outside [PttSendManager], which
     * doesn't own the recorder instance directly.
     *
     * `AudioRecorderCodec2` solves the same problem in-class by calling
     * its own `stopRecording(...)` from inside `onPipelinePacketSent`;
     * the AI path needs an explicit hook because the recorder lives in
     * a different object.
     *
     * Cleared by the timeout check after firing so subsequent chunks
     * already in the queue don't re-trigger the host callbacks.
     */
    @Volatile internal var onMaxTimeoutReached: (() -> Unit)? = null

    internal var job: Job? = null
}

object PttSendManager {


    @Volatile
    var onDecodedChunk: ((ShortArray) -> Unit)? = null


    private val TAG = "PttManager"
    private val TAG_DECODE = "PttManager_Decode"
    private val TAG_ENCODE = "PttManager_Encode"
    private val TAG_LATENCY = "PttManager_Latency"

    private const val AI_PACKET_DURATION_MS = 500

    /**
     * Deadlock-breaker for the single-session barrier in [launchSessionJob]:
     * the longest a new session will wait for the previous one to finalize
     * before proceeding anyway. The orphan's rawQueue is always closed by
     * [restart] before the wait begins, so a properly-behaving session
     * drains in well under this bound — it exists only so a pathological
     * finalize hang can't permanently wedge recording.
     */
    private const val PREVIOUS_SESSION_DRAIN_TIMEOUT_MS = 10_000L

    /** Coroutine scope owning every per-session encoding job. */
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val codecMutex = Mutex()

    private var wavTokenizerEncoder: WavTokenizerEncoder = AIModuleInitializer.wavTokenizerEncoder
    @SuppressLint("StaticFieldLeak")
    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder
    private var viewModel : PttInterface? = null
    var aiEnabled = false

    // ── Session management ────────────────────────────────────────────
    private val sessionsLock = Any()

    @SuppressLint("StaticFieldLeak")
    @Volatile private var currentSession: PttSession? = null
    private val sessionIdGen = AtomicLong(0)

    fun init(viewModel : PttInterface? = null) {
        this.viewModel = viewModel
        aiEnabled = true
    }

    fun addNewFrame(
        pcmArray: ShortArray,
        file: File,
        carrier: Carrier? = null,
        receiverId: String,
        chatId: String
    ) {
        val session = synchronized(sessionsLock) { currentSession } ?: run {
            Log.w(TAG, "addNewFrame: no active session — dropping ${pcmArray.size}-sample chunk")
            return
        }

        session.file = file
        session.carrier = carrier
        session.receiverId = receiverId
        session.chatId = chatId
        if (!session.queue.trySend(pcmArray).isSuccess) {
            Log.w(TAG, "Session ${session.id}: queue trySend failed (queue closed?)")
        }
    }

    /**
     * Queue a RAW (not yet DSP-processed) chunk from the live recorder for
     * asynchronous pre-encode processing + send. [RecorderUtils.forwardAiChunk]
     * uses this so a slow RNNoise/filter/resample pass runs on this session's
     * own [PttSession.dspJob] instead of blocking the recorder's own capture
     * thread — see [launchDspProcessingLoop].
     *
     * The [com.commcrete.stardust.util.audio.AudioFeederEngine] test-feeder
     * path does NOT use this: it already runs
     * [com.commcrete.stardust.util.audio.PttAudioProcessor.process] itself,
     * synchronously, off the live mic path, and calls [addNewFrame] directly
     * with the already-processed chunk.
     */
    fun addRawFrame(
        pcmArray: ShortArray,
        nativeRate: Int,
        enableNoiseCancellation: Boolean,
        isFinal: Boolean,
        file: File,
        carrier: Carrier? = null,
        chatId: String? = null,
        receiverId: String? = null,
    ) {
        val session = synchronized(sessionsLock) { currentSession } ?: run {
            Log.w(TAG, "addRawFrame: no active session — dropping ${pcmArray.size}-sample chunk")
            return
        }

        session.file = file
        session.carrier = carrier
        session.chatId = chatId
        session.receiverId = receiverId
        val chunk = RawAiChunk(pcmArray, nativeRate, enableNoiseCancellation, isFinal)
        if (!session.rawQueue.trySend(chunk).isSuccess) {
            Log.w(TAG, "Session ${session.id}: raw queue trySend failed (queue closed?)")
        }
    }



    /**
     * Finish the **current** session (the one most recently started via
     * [restart]). Idempotent — extra calls are silently ignored.
     *
     * Use the [finish] overload that takes a [PttSession] when you need to
     * guarantee finalization of a specific session even if a newer
     * recording has since started ([restart] called again before this
     * finish fires).
     */
    fun finish(context: Context) {
        val session = synchronized(sessionsLock) {
            val s = currentSession
            if (s != null) currentSession = null
            s
        } ?: return
        finishSession(session)
    }

    /**
     * Finish the specific [session]. Idempotent. Safe to call even after a
     * newer session has been started — only [session] will be finalized.
     */
    fun finish(session: PttSession) {
        synchronized(sessionsLock) {
            if (currentSession === session) currentSession = null
        }
        finishSession(session)
    }

    /**
     * Suspend until [session]'s encode/finalize job has fully completed —
     * i.e. the queue has been drained, [finalizeSession] has written the
     * WAV file (and any mirror copy under [RecorderUtils.dirToSaveFile]),
     * and the per-session resources have been released.
     *
     * Safe to call before, during, or after [finish]: if the queue isn't
     * closed yet this will hang, so callers that don't control the
     * lifecycle (e.g. a "wait then time out" path) should wrap in
     * `withTimeoutOrNull(...)`.
     *
     * Returns immediately if the session has no job yet (never started)
     * or has already completed.
     */
    suspend fun awaitFinalized(session: PttSession) {
        session.job?.join()
    }

    private fun finishSession(session: PttSession) {
        if (!session.finishRequested.compareAndSet(false, true)) {
            Log.d(TAG, "finish(${session.id}): already finished — ignoring")
            return
        }
        // Closing the raw queue lets [launchDspProcessingLoop] drain whatever raw chunks are
        // still pending (still running the full DSP chain on each), then close the encode
        // queue itself once done — which in turn ends the per-session for-loop in
        // launchSessionJob. The job then runs finalizeSession exactly once and releases
        // [codecMutex] so any newer session can start using the shared codec.
        session.rawQueue.close()
        Log.d(TAG, "finish(${session.id}): raw queue closed — awaiting DSP + encode drain")
    }

    fun restart(onMaxTimeoutReached: (() -> Unit)? = null): PttSession {
        val newSession = PttSession(
            id = sessionIdGen.incrementAndGet(),
            context = DataManager.appContext,
        )
        newSession.recordingTs = RecorderUtils.ts
        newSession.onMaxTimeoutReached = onMaxTimeoutReached

        // Atomic swap: detach previous session (if any) and install the
        // new one in a single critical section so concurrent observers
        // see a consistent view of `currentSession`.
        val orphan = synchronized(sessionsLock) {
            val prev = currentSession
            currentSession = newSession
            prev
        }

        // Safety net: if a previous session was never explicitly finished
        // (e.g. recorder was killed without firing `onStateChanged(false)`,
        // a crash skipped the finishAIRecording path, or the SDK consumer
        // simply forgot to call stopRecording), close its queue NOW.
        // Without this, the orphan's `for (chunk in queue)` consumer
        // would suspend forever, holding the shared codec mutex and
        // blocking every future session — including this one.
        if (orphan != null && !orphan.finishRequested.get()) {
            Log.w(TAG, "restart(): previous session ${orphan.id} was never finished — auto-finalizing")
            finishSession(orphan)
        }

        // NOTE: PttAudioProcessor.reset() is NOT called here — it runs
        // inside launchSessionJob, which first *joins* the previous
        // session's job (see the `previous` parameter). Only one send
        // session may be active at a time: the new session blocks on the
        // orphan's full drain + finalize before it touches the shared
        // PttAudioProcessor / codec, so the old tail's filter+encode
        // processing can never overlap this session's reset() or its own
        // processing. `finishSession(orphan)` above closes the orphan's
        // rawQueue so that join is guaranteed to complete rather than hang.
        newSession.job = launchSessionJob(newSession, previous = orphan)
        Log.d(TAG, "restart() -> new session ${newSession.id}")
        return newSession
    }


    private fun launchSessionJob(session: PttSession, previous: PttSession?): Job {
        return coroutineScope.launch {
            // Hold a wake lock for the duration of encode + finalize so
            // screen-off / background does not suspend the codec coroutine
            // mid-session. The recorder also holds one of these; refcount
            // makes both safe.
            AudioRecordingKeepAlive.acquire(session.context)
            try {
                // Single-session-at-a-time barrier: wait for the previous
                // session to fully finalize before this one touches any
                // shared state (PttAudioProcessor, the PyTorch encoder
                // Module, the shared decoder). Without this, a rapid
                // stop→re-press lets the orphan's still-draining DSP/encode
                // tail run concurrently with this session's reset() +
                // processing on the singleton PttAudioProcessor — a data
                // race + use-after-free on the native RNNoise state.
                //
                // [restart] already closed the orphan's rawQueue via
                // finishSession(), so its job is guaranteed to drain and
                // complete; the timeout is a pure deadlock-breaker for a
                // pathological finalize hang, never expected to fire in
                // normal operation. Meanwhile the recorder keeps buffering
                // raw frames into this session's UNLIMITED rawQueue, so no
                // audio is lost while we wait.
                previous?.job?.let { prevJob ->
                    Log.d(TAG, "Session ${session.id}: awaiting previous session ${previous.id} to finalize")
                    val drained = withTimeoutOrNull(PREVIOUS_SESSION_DRAIN_TIMEOUT_MS) {
                        prevJob.join()
                        true
                    }
                    if (drained == null) {
                        Log.w(
                            TAG,
                            "Session ${session.id}: previous session ${previous.id} did not " +
                                "finalize within ${PREVIOUS_SESSION_DRAIN_TIMEOUT_MS}ms — proceeding anyway"
                        )
                    }
                }
                // OUTER try-finally: guarantees [finalizeSession] runs no
                // matter how the inner block exits — including when the
                // codec mutex acquisition itself was cancelled (in which
                // case the inner try-finally inside withLock never gets
                // a chance to run). Without this layering, a job
                // cancelled while waiting on [codecMutex] would silently
                // skip finalization, leaving its file unwritten.
                try {
                    // Brief exclusive section: reset cross-session filter
                    // state and seed this session's own decode continuity.
                    // The encode loop below never touches the shared
                    // decoder, so it doesn't hold this lock — only
                    // [mirrorDecodeChunk] does, per chunk, via its own
                    // mirror job, so a slow decode pass can never delay
                    // this loop's next encode+send.
                    codecMutex.withLock {
                        Log.d(TAG, "Session ${session.id}: codec acquired")
                        PttAudioProcessor.reset()
                        session.lastTokens = null
                        session.lastPCM = null
                        session.decoderState = WavTokenizerDecoder.InternalState.INITIAL
                        session.loopStartMs = System.currentTimeMillis()
                    }

                    session.mirrorJob = coroutineScope.launch {
                        launchMirrorDecodeLoop(session)
                    }
                    // Dispatchers.IO, NOT the scope's default Dispatchers.Default: the encode
                    // loop above and launchMirrorDecodeLoop both run PyTorch Module.forward()
                    // calls on Default's CPU-core-bounded pool, which is shared app-wide (this
                    // runs inside a much larger host app). Launching the DSP/RNNoise job on
                    // that same pool adds a third steady CPU-bound consumer and measurably
                    // slows down the other two (observed: WavTokenizerEncoder/Decoder calls
                    // that used to take <400ms climbing past 800ms once this job was added).
                    // IO's much larger, less-contended thread pool avoids that competition,
                    // matching where this processing already ran before it was decoupled from
                    // AudioRecorderAI's capture loop (that loop is Dispatchers.IO too).
                    session.dspJob = coroutineScope.launch(Dispatchers.IO) {
                        launchDspProcessingLoop(session)
                    }

                    for (pcmArray in session.queue) {
                        if (!isActive) break
                        try {
                            handleTokenizerChunk(session, pcmArray)
                        } catch (e: MediaCodec.CodecException) {
                            Log.e(TAG, "Session ${session.id}: codec exception", e)
                        } catch (e: Exception) {
                            Log.e(TAG, "Session ${session.id}: chunk error", e)
                        }
                    }
                    Log.d(TAG, "Session ${session.id}: encode loop done — draining mirror decode")
                } finally {
                    // Atomic CAS guarantees finalize runs at most once
                    // per session even if some future code path adds
                    // another finalize call site.
                    if (session.finalized.compareAndSet(false, true)) {
                        // [NonCancellable] so the WAV write + message save
                        // completes even when the surrounding job is
                        // cancelling. Otherwise a partial recording's
                        // frames — or its message row — would be lost on
                        // app shutdown / scope cancel.
                        withContext(NonCancellable) {
                            try {
                                // The encode loop above only ends once launchDspProcessingLoop
                                // closes session.queue (after draining rawQueue), so dspJob is
                                // normally already complete here — this join is a defensive
                                // no-op in that case, and a real wait if the for-loop above
                                // instead exited via the `!isActive` break.
                                session.dspJob?.join()
                            } catch (e: Exception) {
                                Log.e(TAG, "Session ${session.id}: DSP loop drain failed", e)
                            }
                            try {
                                // Every packedData chunk was pushed to
                                // mirrorQueue before the encode loop above
                                // finished, so closing it now and joining
                                // is safe: the mirror job will drain
                                // whatever's buffered and stop.
                                session.mirrorQueue.close()
                                session.mirrorJob?.join()
                            } catch (e: Exception) {
                                Log.e(TAG, "Session ${session.id}: mirror decode drain failed", e)
                            }
                            try {
                                finalizeSession(session)
                            } catch (e: Exception) {
                                Log.e(TAG, "Session ${session.id}: finalize failed", e)
                            }
                        }
                    }
                    // Idempotent: ensures any producer that races a
                    // finished session sees a fast-failing trySend
                    // instead of silently buffering into a dead queue.
                    session.rawQueue.close()
                    session.queue.close()
                }
            } catch (t: Throwable) {
                // Cancellation is expected (scope.cancel / job.cancel);
                // don't pollute logs with stack traces for it. Other
                // throwables are real and worth recording.
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Session ${session.id}: job failed", t)
                }
            } finally {
                AudioRecordingKeepAlive.release()
            }
        }
    }

    /**
     * Per-chunk orchestrator inside the session's encode loop.
     *
     *  1. [encodeAndSendChunk] — encode the captured PCM (with sliding-
     *     window context) and ship the packed payload over the wire.
     *  2. [enforceMaxPttTimeout] — count this packet against the
     *     per-session max-PTT-timeout and fire the host callbacks once
     *     when the threshold is crossed.
     *  3. Hand the packed payload to [PttSession.mirrorQueue] for
     *     [launchMirrorDecodeLoop] to self-decode asynchronously. This
     *     loop only ever waits on steps 1-2 — mirror-decode (a second ML
     *     forward pass, needed only for the optional local WAV / debug
     *     hook) runs on its own consumer so it can never delay the next
     *     chunk's encode+send.
     */
    private fun handleTokenizerChunk(session: PttSession, pcmArray: ShortArray) {
        val chunkStartMs = System.currentTimeMillis()
        val packedData = encodeAndSendChunk(session, pcmArray)
        enforceMaxPttTimeout(session)

        if (!session.mirrorQueue.trySend(packedData).isSuccess) {
            Log.w(TAG_DECODE, "Session ${session.id}: mirror queue trySend failed (queue closed?)")
        }

        // Diagnostic: this loop iteration must finish inside
        // AI_PACKET_DURATION_MS or the consumer falls behind the recorder,
        // which queues (Channel.UNLIMITED) rather than blocks — so a chunk
        // that took too long doesn't error, it just makes every later
        // chunk in this session sit longer before being sent. `backlogMs`
        // is the running drift: how far real elapsed time has pulled ahead
        // of the audio-time this session has actually processed.
        val chunkTotalMs = System.currentTimeMillis() - chunkStartMs
        val expectedElapsedMs = session.numPacketsSent.toLong() * AI_PACKET_DURATION_MS
        val actualElapsedMs = System.currentTimeMillis() - session.loopStartMs
        val backlogMs = actualElapsedMs - expectedElapsedMs
        Log.d(
            TAG_LATENCY,
            "Session ${session.id}: chunk #${session.numPacketsSent} loop took ${chunkTotalMs}ms " +
                "(budget ${AI_PACKET_DURATION_MS}ms), backlog vs real-time = ${backlogMs}ms"
        )
    }

    private fun encodeAndSendChunk(session: PttSession, pcmArray: ShortArray): ByteArray {
        Log.d(TAG_ENCODE, "Session ${session.id}: encoding ${pcmArray.size} samples")
        val chunkCodes = wavTokenizerEncoder.encode(pcmArray)
        Log.d(TAG_ENCODE, "Session ${session.id}: encoded chunk size ${chunkCodes.size}")

        val packedData = BitPacking12.pack12(chunkCodes.toList())
        sendData(session, packedData)

        return packedData
    }

    /**
     * Phase 2: max-PTT-timeout enforcement.
     *
     * Direct analogue of [com.commcrete.stardust.util.audio.AudioRecorderCodec2.onPipelinePacketSent].
     * Each successful [encodeAndSendChunk] ships exactly one BLE packet
     * representing [AI_PACKET_DURATION_MS] of captured audio, so
     *
     *   numPacketsSent × AI_PACKET_DURATION_MS
     *
     * is the accumulated wall-clock duration of audio sent on this
     * session. When that exceeds the user-configured timeout (default
     * 45_000 ms via [SharedPreferencesUtil.getPTTTimeout]) the host is
     * notified and the AudioRecorderAI is told to stop via the
     * per-session callback. PttSendManager doesn't own the recorder
     * directly — `AudioRecorderCodec2` solves the same problem inline
     * because the recorder + encoder live in the same class.
     *
     * Single-shot: the [PttSession.onMaxTimeoutReached] hook is cleared
     * before firing, so any chunks already in the queue that drain
     * after this point go through this branch silently without
     * re-triggering the host callbacks.
     */
    private fun enforceMaxPttTimeout(session: PttSession) {
        session.numPacketsSent++
        val maxMs = SharedPreferencesUtil.getPTTTimeout()
        if (session.numPacketsSent.times(AI_PACKET_DURATION_MS) <= maxMs) return

        val onTimeout = session.onMaxTimeoutReached ?: return
        session.onMaxTimeoutReached = null
        Log.w(
            TAG,
            "Session ${session.id}: PTT max timeout reached " +
                "(${session.numPacketsSent} packets × ${AI_PACKET_DURATION_MS}ms > ${maxMs}ms)"
        )
        DataManager.getCallbacks()?.pttMaxTimeoutReached()
        SoundPlayer.play(session.context, com.commcrete.stardust.R.raw.ptt_finished_beep)
        onTimeout.invoke()
        viewModel?.maxPTTTimeoutReached()
    }

    /**
     * Per-session raw-chunk consumer, run as its own coroutine
     * ([PttSession.dspJob]) alongside the encode loop and mirror-decode loop.
     * Runs the full pre-encode DSP chain
     * ([com.commcrete.stardust.util.audio.PttAudioProcessor.process] —
     * HPF/Notch/RNNoise/AGC/Dynamics/resample) off the recorder's own capture
     * thread, so a slow RNNoise forward pass delays only this session's own
     * encode+send pipeline, never the next `AudioRecord.read()` on the live
     * mic. A [Channel] with exactly one consumer preserves FIFO order, so
     * chunks are still processed strictly in the order they were captured.
     *
     * Closes [PttSession.queue] once drained — the head of the "no more
     * data" chain that [finishSession] kicks off by closing
     * [PttSession.rawQueue], ending the encode loop in [launchSessionJob] in
     * turn.
     */
    private suspend fun launchDspProcessingLoop(session: PttSession) {
        for (raw in session.rawQueue) {
            try {
                val processed = PttAudioProcessor.process(
                    pcmArray = raw.pcmArray,
                    nativeRate = raw.nativeRate,
                    targetRate = PttAudioProcessor.AI_TARGET_SAMPLE_RATE,
                    enableNoiseCancellation = raw.enableNoiseCancellation,
                    isFinal = raw.isFinal,
                )
                if (!session.queue.trySend(processed).isSuccess) {
                    Log.w(TAG, "Session ${session.id}: encode queue trySend failed after DSP (queue closed?)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Session ${session.id}: DSP processing failed", t)
            }
        }
        Log.d(TAG, "Session ${session.id}: DSP loop drained — closing encode queue")
        session.queue.close()
    }

    /**
     * Per-session mirror-decode consumer, run as its own coroutine
     * ([PttSession.mirrorJob]) alongside the encode loop. A [Channel] with
     * exactly one consumer preserves FIFO order, so chunks are still
     * decoded strictly in the order they were encoded — just without
     * blocking [handleTokenizerChunk] on a second model forward pass.
     */
    private suspend fun launchMirrorDecodeLoop(session: PttSession) {
        for (packedData in session.mirrorQueue) {
            try {
                mirrorDecodeChunk(session, packedData)
            } catch (t: Throwable) {
                Log.w(TAG_DECODE, "Session ${session.id}: mirror decode loop error", t)
            }
        }
        Log.d(TAG_DECODE, "Session ${session.id}: mirror decode loop drained")
    }

    /**
     * Optional per-chunk self-decode for the local WAV mirror and the
     * [onDecodedChunk] debug hook. Runs on [PttSession.mirrorJob], never
     * on the encode/send loop.
     *
     * [WavTokenizerDecoder] is a shared singleton whose instance state
     * (`cutTokens`, `index`, `loop`) would otherwise be corrupted by
     * concurrent callers — this session's own mirror loop, another
     * session's, and every live [PttReceiveManager] stream all share it.
     * We multiplex the same way [PttReceiveManager] does for incoming
     * audio: save → restore → decode → save this session's own
     * [PttSession.decoderState] under [codecMutex], scoped to just this
     * one decode call rather than the whole session, so it can interleave
     * freely with everything else touching the decoder.
     *
     * No-op when neither consumer is interested — saves a model
     * forward pass on every chunk.
     */
    private suspend fun mirrorDecodeChunk(session: PttSession, packedData: ByteArray) {
        val decodedSink = onDecodedChunk
        val savingToFile = DataManager.getSavePTTFilesRequired()
        if (decodedSink == null && !savingToFile) return

        try {
            val unpack = BitPacking12.unpack12(packedData)
            @Suppress("DEPRECATION")
            val modelTypeSelected = SharedPreferencesUtil.getAudioModelType()
            val pcm = codecMutex.withLock {
                wavTokenizerDecoder.restoreInternalState(session.decoderState)
                val decoded = wavTokenizerDecoder.decode(unpack, session.lastTokens, session.lastPCM, modelTypeSelected)
                session.decoderState = wavTokenizerDecoder.snapshotInternalState()
                decoded
            }
            session.lastTokens = unpack
            session.lastPCM = pcm
            decodedSink?.invoke(pcm)
            if (savingToFile) {
                session.frameBuffer.add(pcm)
            }
        } catch (t: Throwable) {
            Log.w(TAG_DECODE, "Session ${session.id}: per-chunk decode failed", t)
        }
    }

    private fun sendData(session: PttSession, data: ByteArray) {
        Log.d(TAG, "Session ${session.id} send msg: ${data.size} data: ${data.toHexString()}")
        val radio = CarriersUtils.getRadioToSend(session.carrier, functionalityType = FunctionalityType.PTT) ?: return

        Scopes.getDefaultCoroutine().launch {
            val bittelPackage = viewModel?.let {
                val fullData = ByteArray(data.size + 1)
                fullData[0] = 0x00
                System.arraycopy(data, 0, fullData, 1, data.size)
                val audioIntArray = StardustPackageUtils.byteArrayToIntArray(fullData)
                StardustPackageUtils.getStardustPackage(
                    source = it.getSource(),
                    destination = it.getDestination(),
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_PTT_AI,
                    data = audioIntArray)
            }

            val isLast = data.size != 30


            bittelPackage?.let { bittelPackage ->
                bittelPackage.stardustControlByte.stardustPartType = if(isLast) StardustControlByte.StardustPartType.LAST else StardustControlByte.StardustPartType.MESSAGE
                bittelPackage.stardustControlByte.stardustDeliveryType = radio.second
                bittelPackage.checkXor = StardustPackageUtils.getCheckXor(bittelPackage.getStardustPackageToCheckXor())
                DataManager.sendDataToBle(bittelPackage)
            }

        }

    }

    /**
     * Persist the "PTT sent" message row for [session] once its WAV has
     * been written to [path]. Called from [finalizeSession], already
     * inside `withContext(NonCancellable)` — awaiting the save here
     * (rather than firing a detached coroutine) guarantees the message
     * row lands even if the surrounding job is being cancelled.
     */
    private suspend fun savePttMessage(session: PttSession, path: String) {
        val chatId = session.chatId ?: run {
            Log.w(TAG, "Session ${session.id}: no chatId — skipping message save")
            return
        }
        val receiverId = session.receiverId ?: run {
            Log.w(TAG, "Session ${session.id}: no receiverId — skipping message save")
            return
        }
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: run {
            Log.w(TAG, "Session ${session.id}: no logged-in user — skipping message save")
            return
        }
        DataManager.getAppRepo().saveMessage(
            MessageEntity(
                chatId = chatId,
                senderID = appId,
                receiverID = receiverId,
                state = MessageState.SENT,
                epochTimeMs = session.recordingTs,
                extraData = MessageExtraData.PTT(
                    path = path,
                    encoderType = EncoderType.AI
                )
            )
        )
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }

    /**
     * Write the session's accumulated decoded PCM to its target WAV file
     * and persist a PTT message row. Runs at most once per session
     * (guarded by [PttSession.finalized] in the caller).
     */
    private suspend fun finalizeSession(session: PttSession) {
        Log.d(TAG, "Session ${session.id}: finalize (${session.frameBuffer.size} frame(s))")
        if (!DataManager.getSavePTTFilesRequired()) {
            Log.d(TAG, "Session ${session.id}: save not required — skipping file")
            return
        }
        val file = session.file ?: run {
            Log.d(TAG, "Session ${session.id}: file is null — nothing to save")
            return
        }
        if (session.frameBuffer.isEmpty()) {
            Log.d(TAG, "Session ${session.id}: no decoded frames — skipping file save")
            return
        }
        try {
            val sampleArray = session.frameBuffer.flatMap { it.asIterable() }.toShortArray()
            WavHelper.createWavFile(sampleArray, 24000, file)

            Log.d(TAG, "Session ${session.id}: WAV created -> ${file.absolutePath}")
            runCatching {

                val artifactDir = RecorderUtils.dirToSaveFile
                val srcParent = file.parentFile?.canonicalFile
                val dstParent = artifactDir.canonicalFile
                if (srcParent != dstParent) {
                    if (!artifactDir.exists()) artifactDir.mkdirs()
                    // Force a `.wav` extension on the mirror — the
                    // production sinkFile uses `.pcm` (createSinkFile in
                    // AudioFeederEngine) even though the bytes are a real
                    // RIFF/WAVE container, which confuses Audacity / ffmpeg
                    // auto-detect.
                    val mirrorName = "${file.nameWithoutExtension}-ptt_finish.wav"
                    val mirror = File(artifactDir, mirrorName)
                    file.copyTo(mirror, overwrite = true)
                    Log.d(TAG, "Session ${session.id}: WAV mirror -> ${mirror.absolutePath}")
                }
            }.onFailure { Log.w(TAG, "Session ${session.id}: mirror copy failed", it) }

            savePttMessage(session, file.absolutePath)
            RecorderUtils.ts = 0
        } catch (e: Exception) {
            Log.e(TAG, "Session ${session.id}: WAV save failed", e)
        }
    }
}
