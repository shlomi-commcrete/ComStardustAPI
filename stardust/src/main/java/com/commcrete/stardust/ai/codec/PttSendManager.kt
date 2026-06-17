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

    internal var job: Job? = null
}

object PttSendManager {

    /**
     * Optional per-chunk hook for capturing the decoded (post-WavTokenizer)
     * PCM as it is produced — i.e. the AI **output** that corresponds 1:1
     * to each [addNewFrame] input chunk.
     *
     * Set this to a non-null callback (e.g. from `AudioFeederEngine` during
     * a test-feed run) to receive every decoded chunk in encode order, and
     * clear (set back to null) when capture is finished. Call
     * [resetLiveDecodeState] between sessions to drop residual state.
     *
     * Implementation note: when this hook is registered, [handleTokenizerChunk]
     * runs **one** decode per encoded chunk and shares the resulting PCM
     * with the per-session frame buffer (see [finalizeSession]). Doing it
     * in two independent decode passes corrupts the shared
     * `WavTokenizerDecoder` instance state (`cutTokens`, `index`),
     * because each pass reads what the OTHER pass wrote on the previous
     * chunk — producing garbled output even though each stream's own
     * continuity (`lastTokens` / `lastPCM`) looks fine.
     */
    @Volatile
    var onDecodedChunk: ((ShortArray) -> Unit)? = null

    /**
     * Drop the per-chunk decoder continuity state (`lastTokens` / `lastPCM`)
     * AND any buffered frames for the **currently active session**. Call
     * between sources / sub-streams so the next stream starts with a clean
     * `previousTokens=null` path through `WavTokenizerDecoder.decode` (no
     * stale head-cut from a different stream).
     *
     * Also resets the shared [wavTokenizerDecoder]'s **internal** per-stream
     * state ([WavTokenizerDecoder.reset] — `index`, `cutTokens`, `loop`,
     * `listEnergy`). Without this, `cutTokens` from the last chunk of the
     * previous stream leaks into [WavTokenizerDecoder.handleSmart] for the
     * next stream's first chunk, slicing the wrong amount off the head and
     * producing progressively less intelligible output across feed runs.
     *
     * If you want a fully isolated session (separate file / queue / state),
     * call [restart] instead — that creates a brand-new [PttSession] and
     * leaves any previously-running session untouched.
     */
    fun resetLiveDecodeState() {
        val session = synchronized(sessionsLock) { currentSession }
        session?.let {
            it.lastTokens = null
            it.lastPCM = null
            it.frameBuffer.clear()
        }
        wavTokenizerDecoder.reset()
    }

    private val TAG = "PttManager"
    private val TAG_DECODE = "PttManager_Decode"
    private val TAG_ENCODE = "PttManager_Encode"

    /** Coroutine scope owning every per-session encoding job. */
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Serializes access to the **shared** ML codec instances
     * ([wavTokenizerEncoder] / [wavTokenizerDecoder]). Session jobs queue
     * up on this mutex so a new recording can begin (its [PttSession] is
     * created immediately, chunks are buffered into its queue) but does
     * not actually use the codec until the previous session has finished
     * draining and finalized its file.
     *
     * This mutex is **also held by [PttReceiveManager]** when it decodes
     * incoming PTT packets — the tokenizer decoder is a singleton with
     * mutable per-stream state (`index`, `cutTokens`, `loop`, `listEnergy`)
     * that gets clobbered if Send and Receive call `decode()` interleaved
     * (audible artifacts + progressively worse audio across sessions).
     * Both sides reset the decoder at the start of their own stream so
     * they never inherit each other's continuity.
     */
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

    fun restart(): PttSession {
        val newSession = PttSession(
            id = sessionIdGen.incrementAndGet(),
            context = DataManager.context,
        )
        newSession.recordingTs = RecorderUtils.ts

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

        PttAudioProcessor.reset()
        wavTokenizerDecoder.clearDebugDump()
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
                        // Fresh per-stream codec state for this session.
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

    private fun handleTokenizerChunk(session: PttSession, pcmArray: ShortArray) {
        Log.d(TAG_ENCODE, "Session ${session.id}: encoding ${pcmArray.size} samples")
        val chunkCodes = wavTokenizerEncoder.encode(pcmArray)
        Log.d(TAG_ENCODE, "Session ${session.id}: encoded chunk size ${chunkCodes.size}")

        val packedData = BitPacking12.pack12(chunkCodes.toList())

        sendData(session, packedData)

        // ── Per-chunk decode (single source of truth) ────────────────────
        // Both consumers (onDecodedChunk debug hook AND the per-session
        // saved WAV) need the decoded PCM for THIS encoded chunk. We MUST
        // run that decode at most once per chunk, because
        // WavTokenizerDecoder is a shared singleton that mutates private
        // instance state (`cutTokens`, `index`) on every call.
        val decodedSink = onDecodedChunk
        val savingToFile = DataManager.getSavePTTFilesRequired(session.context)
        if (decodedSink != null || savingToFile) {
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