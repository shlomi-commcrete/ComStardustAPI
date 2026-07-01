package com.commcrete.stardust.ai.codec

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.media.WavHelper
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.commcrete.stardust.util.audio.AudioRecordingKeepAlive
import com.commcrete.stardust.util.audio.PttAudioProcessor
import com.commcrete.stardust.util.audio.SoundPlayer

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
    internal val queue = Channel<ShortArray>(Channel.UNLIMITED)
    internal val frameBuffer = mutableListOf<ShortArray>()
    @Volatile internal var file: File? = null
    @Volatile internal var carrier: Carrier? = null
    @Volatile internal var chatID: String? = null
    internal var lastTokens: List<Long>? = null
    internal var lastPCM: ShortArray? = null
    internal var recordingTs: Long = 0L


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

    private const val AI_PACKET_DURATION_MS = 500

    /** Coroutine scope owning every per-session encoding job. */
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val codecMutex = Mutex()

    private var wavTokenizerEncoder: WavTokenizerEncoder = AIModuleInitializer.wavTokenizerEncoder
    @SuppressLint("StaticFieldLeak")
    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder
    private lateinit var cacheDir: File
    private var viewModel : PttInterface? = null
    var aiEnabled = false

    // ── Session management ────────────────────────────────────────────
    private val sessionsLock = Any()

    @SuppressLint("StaticFieldLeak")
    @Volatile private var currentSession: PttSession? = null
    private val sessionIdGen = AtomicLong(0)

    fun init(context: Context, viewModel : PttInterface? = null) {
        cacheDir = context.cacheDir
        this.viewModel = viewModel
        aiEnabled = true
//        AudioDebugTest(context, wavTokenizerEncoder, wavTokenizerDecoder).runTest()
    }

    fun addNewFrame(
        pcmArray: ShortArray,
        file: File,
        carrier: Carrier? = null,
        chatID: String? = null
    ) {
        val session = synchronized(sessionsLock) { currentSession } ?: run {
            Log.w(TAG, "addNewFrame: no active session — dropping ${pcmArray.size}-sample chunk")
            return
        }

        session.file = file
        session.carrier = carrier
        session.chatID = chatID
        if (!session.queue.trySend(pcmArray).isSuccess) {
            Log.w(TAG, "Session ${session.id}: queue trySend failed (queue closed?)")
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
        // Closing the channel ends the per-session for-loop in
        // launchSessionJob; the job then runs finalizeSession exactly once
        // and releases [codecMutex] so any newer session can start using
        // the shared codec.
        session.queue.close()
        Log.d(TAG, "finish(${session.id}): queue closed — awaiting drain")
    }

    fun restart(onMaxTimeoutReached: (() -> Unit)? = null): PttSession {
        val newSession = PttSession(
            id = sessionIdGen.incrementAndGet(),
            context = DataManager.context,
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
        // inside launchSessionJob, after the codecMutex is acquired. This
        // guarantees the previous session's encode loop has fully drained
        // (all queued chunks processed through filters + encoder) before
        // the filter chain's native state (RNNoise, biquads) is torn down.
        newSession.job = launchSessionJob(newSession)
        Log.d(TAG, "restart() -> new session ${newSession.id}")
        return newSession
    }


    private fun launchSessionJob(session: PttSession): Job {
        return coroutineScope.launch {
            // Hold a wake lock for the duration of encode + finalize so
            // screen-off / background does not suspend the codec coroutine
            // mid-session. The recorder also holds one of these; refcount
            // makes both safe.
            AudioRecordingKeepAlive.acquire(session.context)
            try {
                // OUTER try-finally: guarantees [finalizeSession] runs no
                // matter how the inner block exits — including when the
                // codec mutex acquisition itself was cancelled (in which
                // case the inner try-finally inside withLock never gets
                // a chance to run). Without this layering, a job
                // cancelled while waiting on [codecMutex] would silently
                // skip finalization, leaving its file unwritten.
                try {
                    codecMutex.withLock {
                        Log.d(TAG, "Session ${session.id}: codec acquired")
                        // Previous session's encode loop has now fully
                        // drained (it held this mutex until its queue was
                        // empty). Safe to tear down filter + encoder state.
                        PttAudioProcessor.reset()
                        wavTokenizerDecoder.reset()
                        session.lastTokens = null
                        session.lastPCM = null

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
                        Log.d(TAG, "Session ${session.id}: codec releasing")
                    }
                } finally {
                    // Atomic CAS guarantees finalize runs at most once
                    // per session even if some future code path adds
                    // another finalize call site.
                    if (session.finalized.compareAndSet(false, true)) {
                        // [NonCancellable] so the WAV write completes
                        // even when the surrounding job is cancelling.
                        // Otherwise a partial recording's frames would
                        // be lost on app shutdown / scope cancel.
                        withContext(NonCancellable) {
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
     * Three sequential phases, each delegated to its own helper:
     *  1. [encodeAndSendChunk] — encode the captured PCM (with sliding-
     *     window context) and ship the packed payload over the wire.
     *  2. [enforceMaxPttTimeout] — count this packet against the
     *     per-session max-PTT-timeout and fire the host callbacks once
     *     when the threshold is crossed.
     *  3. [mirrorDecodeChunk] — optionally self-decode the just-shipped
     *     payload to feed the WAV mirror / debug hook.
     *
     * Phases run unconditionally even if step 2 fires the timeout —
     * the chunk was already encoded and transmitted in step 1, so its
     * mirror PCM is still wanted in the local WAV. The timeout only
     * stops *future* chunks (via the recorder-stop hook).
     */
    private fun handleTokenizerChunk(session: PttSession, pcmArray: ShortArray) {
        val packedData = encodeAndSendChunk(session, pcmArray)
        enforceMaxPttTimeout(session)
        mirrorDecodeChunk(session, packedData)
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
        val maxMs = SharedPreferencesUtil.getPTTTimeout(session.context)
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
     * Phase 3: optional per-chunk self-decode for the local WAV mirror
     * and the [onDecodedChunk] debug hook.
     *
     * Both consumers (the `onDecodedChunk` debug hook AND the
     * per-session saved WAV) need the decoded PCM for THIS encoded
     * chunk. We MUST run that decode **at most once** per chunk
     * because [WavTokenizerDecoder] is a shared singleton that mutates
     * private instance state (`cutTokens`, `index`) on every call —
     * doing it twice in two independent passes corrupts the shared
     * decoder state, because each pass reads what the OTHER pass
     * wrote on the previous chunk and produces garbled output even
     * though each stream's own continuity (`lastTokens` / `lastPCM`)
     * looks fine.
     *
     * No-op when neither consumer is interested — saves a model
     * forward pass on every chunk.
     */
    private fun mirrorDecodeChunk(session: PttSession, packedData: ByteArray) {
        val decodedSink = onDecodedChunk
        val savingToFile = DataManager.getSavePTTFilesRequired(session.context)
        if (decodedSink == null && !savingToFile) return

        try {
            val unpack = BitPacking12.unpack12(packedData)
            @Suppress("DEPRECATION")
            val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)
            val pcm = wavTokenizerDecoder.decode(unpack, session.lastTokens, session.lastPCM, modelTypeSelected)
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
                    context = session.context,
                    source = it.getSource(),
                    destenation = it.getDestenation() ?: "" ,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_PTT_AI,
                    data = audioIntArray)
            }
            
            val isLast = data.size != 30
            

            bittelPackage?.let { bittelPackage ->
                bittelPackage.stardustControlByte.stardustPartType = if( isLast) StardustControlByte.StardustPartType.LAST else StardustControlByte.StardustPartType.MESSAGE
                bittelPackage.stardustControlByte.stardustDeliveryType = radio.second
                bittelPackage.checkXor = StardustPackageUtils.getCheckXor(bittelPackage.getStardustPackageToCheckXor())
                DataManager.sendDataToBle(bittelPackage)
            }

        }

    }

    private fun savePtt(session: PttSession, chatID: String, path: String) {
        Scopes.getDefaultCoroutine().launch {
            SharedPreferencesUtil.getAppUser(session.context)?.appId?.let {
                val chatsRepo = DataManager.getChatsRepo(session.context)
                val chatItem = chatsRepo.getChatByBittelID(chatID)
                chatItem?.message = Message(
                    senderID = it,
                    text = "PTT Sent",
                    seen = true
                )
                chatItem?.let { chatsRepo.addChat(it) }
                DataManager.getMessagesRepo(DataManager.context).saveMessage(
                    context = session.context,
                    isPTT = true,
                    messageItem = MessageItem(senderID = it,
                        epochTimeMs = session.recordingTs, senderName = "" ,
                        chatId = chatID, text = "", fileLocation = path,
                        isAudio = true, seen = SeenStatus.SENT, audioType = RecorderUtils.CODE_TYPE.AI.id)
                )
            }
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }

    /**
     * Write the session's accumulated decoded PCM to its target WAV file
     * and persist a PTT message row. Runs at most once per session
     * (guarded by [PttSession.finalized] in the caller).
     */
    private fun finalizeSession(session: PttSession) {
        Log.d(TAG, "Session ${session.id}: finalize (${session.frameBuffer.size} frame(s))")
        if (!DataManager.getSavePTTFilesRequired(session.context)) {
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

            savePtt(session = session, chatID = session.chatID ?: "", path = file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Session ${session.id}: WAV save failed", e)
        }
    }
}