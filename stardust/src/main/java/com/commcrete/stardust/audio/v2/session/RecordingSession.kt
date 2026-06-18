package com.commcrete.stardust.audio.v2.session

import android.content.Context
import com.commcrete.stardust.audio.v2.capture.CaptureChunk
import com.commcrete.stardust.audio.v2.capture.CaptureSource
import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.audio.v2.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.codec.EncoderSession
import com.commcrete.stardust.audio.v2.dsp.AiRecorderProfileV2
import com.commcrete.stardust.audio.v2.dsp.PttAudioProcessorV2
import com.commcrete.stardust.audio.v2.mirror.LocalMirror
import com.commcrete.stardust.audio.v2.transport.SendTransport
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.audio.AudioRecordingKeepAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One PTT key-down's worth of recording.
 *
 * Replaces [com.commcrete.stardust.ai.codec.PttSession] AND the
 * implicit single-instance lifecycle of
 * [com.commcrete.stardust.util.audio.AudioRecorderCodec2] in one
 * codec-agnostic shape.
 *
 * # Lifecycle
 *
 *  1. [PttSendCoordinator.restart] constructs and [start]s a new
 *     session.
 *  2. [start] launches the encode job (which collects capture chunks,
 *     preprocesses, encodes under [CodecRegistry.withCodec], passes
 *     each payload through [mirror] for the self-decode, and ships
 *     it via [transport]) AND arms the wall-clock watchdog.
 *  3. Caller releases PTT → [finish] is invoked, which cancels the
 *     watchdog and signals the capture source to stop.
 *  4. The encode job drains remaining chunks, calls
 *     [EncoderSession.flush], finalizes the [mirror] (writes the
 *     WAV) and saves a message row via [onSavePtt].
 *
 * # Watchdog
 *
 * If the caller forgets to call [finish] (or BLE writes stall and the
 * encode loop is blocked), the watchdog fires after
 * `getPTTTimeout(ctx)` milliseconds (default 45000 = 45 s) and
 * forces [finish], also firing [onTimeoutReached] so the consumer
 * can surface a "max PTT timeout reached" toast.
 *
 * # Atomic guards
 *
 * Preserves the [finishRequested] / [finalized] CAS pattern from
 * `PttSession` so concurrent / repeated calls to [finish] are
 * idempotent and the file write runs **exactly once**, even if a
 * watchdog fire races a normal release.
 */
class RecordingSession internal constructor(
    val id: Long,
    val codec: AudioCodec,
    val context: Context,
    private val capture: CaptureSource,
    private val transport: SendTransport,
    private val mirror: LocalMirror,
    private val profile: AiRecorderProfileV2,
    private val carrier: Carrier?,
    private val source: String,
    private val destination: String?,
    /**
     * File the local WAV mirror is written to on finalize. `null` ⇒
     * no mirror file (regardless of [mirror] impl).
     */
    private val targetFile: File?,
    private val chatId: String?,
    /**
     * Capture-chunk duration in ms. AI legacy = 500, CODEC2 legacy = 40.
     * Threaded through to [CaptureSource.start].
     */
    private val chunkDurationMs: Int,
    /** Wall-clock max recording duration. From `getPTTTimeout(ctx)`. */
    private val maxDurationMs: Long,
    /** Fired when the watchdog forces a finish. */
    private val onTimeoutReached: () -> Unit,
    /**
     * Persistence hook — receives `(chatId, mirrorFile?)` on
     * finalize. The host plugs in `AppRepository.saveMessage` or
     * the legacy `MessagesRepository.saveMessage` here.
     */
    private val onSavePtt: suspend (chatId: String, file: File?) -> Unit,
    /**
     * Scope owning the encode + watchdog jobs. Defaults to
     * `SupervisorJob() + Dispatchers.Default` so a failure in one
     * session doesn't take down the coordinator's other sessions.
     */
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    internal val finishRequested = AtomicBoolean(false)
    internal val finalized = AtomicBoolean(false)

    @Volatile private var encodeJob: Job? = null
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var processor: PttAudioProcessorV2? = null
    @Volatile private var encoder: EncoderSession? = null

    /**
     * Whether [finish] has been called (or the watchdog has fired).
     * Public so [PttSendCoordinator.awaitFinalized] callers can poll
     * without joining the job.
     */
    val isFinishRequested: Boolean get() = finishRequested.get()

    /** Whether the finalize step has actually completed. */
    val isFinalized: Boolean get() = finalized.get()

    /**
     * Launch the encode coroutine and arm the watchdog. Returns
     * immediately — actual capture starts when the coroutine first
     * collects from [CaptureSource.start].
     */
    fun start() {
        AudioRecordingKeepAlive.acquire(context)
        processor = PttAudioProcessorV2(
            nativeSampleRateHz = capture.nativeSampleRateHz,
            targetSampleRateHz = codec.sampleRateHz,
            profile = profile,
            flowKey = codec.id.name,
        )
        encoder = codec.newEncoderSession()
        encodeJob = scope.launch {
            try {
                runEncodeLoop()
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Timber.tag(TAG).e(t, "Session $id encode loop failed")
                }
            } finally {
                // Guarantee finalize-once regardless of cancellation path.
                if (finalized.compareAndSet(false, true)) {
                    withContext(NonCancellable) {
                        runCatching { finalizeNow() }
                            .onFailure { Timber.tag(TAG).e(it, "Session $id finalize failed") }
                    }
                }
                AudioRecordingKeepAlive.release()
            }
        }
        watchdogJob = scope.launch {
            delay(maxDurationMs)
            if (finishRequested.compareAndSet(false, true)) {
                Timber.tag(TAG).w("Session $id: max-duration watchdog fired after ${maxDurationMs}ms")
                runCatching { onTimeoutReached() }
                capture.stop()
            }
        }
        Timber.tag(TAG).d(
            "Session %d started: codec=%s captureRate=%d targetRate=%d maxMs=%d",
            id, codec.id.name, capture.nativeSampleRateHz, codec.sampleRateHz, maxDurationMs,
        )
    }

    /**
     * Signal end-of-recording. Idempotent. The encode coroutine will
     * drain whatever's already captured, run [EncoderSession.flush],
     * and finalize. Use [PttSendCoordinator.awaitFinalized] (or
     * [encodeJob]?.join()) to wait for the write to land.
     */
    fun finish() {
        if (!finishRequested.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Session $id: finish() called again — ignoring")
            return
        }
        watchdogJob?.cancel()
        // Signal the capture source to stop emitting — the Flow
        // completes, the encode loop falls out of the `collect` and
        // hits its `finally` block which runs finalizeNow().
        capture.stop()
    }

    /** Suspend until the encode job finishes. Safe to call multiple times. */
    suspend fun awaitFinalized() {
        encodeJob?.join()
    }

    // ── internals ────────────────────────────────────────────────────

    private suspend fun runEncodeLoop() {
        val enc = encoder ?: error("Session $id: encoder not initialized — start() not called?")
        val proc = processor ?: error("Session $id: processor not initialized — start() not called?")

        // Hold the codec mutex for the duration of the session — the
        // encoder model has cross-chunk state, and we MUST NOT let
        // another session's encode (or the receive side) interleave.
        // The watchdog cancellation interrupts the `collect` regardless,
        // so the mutex can't be held indefinitely past maxDurationMs.
        // The mirror's per-encoded-frame decode runs INSIDE this same
        // critical section — see the comment in `onMirrorEncoded`.
        CodecRegistry.withCodec(codec.id) { codecRef ->
            // Mirror gets its own decoder session, distinct from any
            // receive-side decoder for the same codec. Both still
            // serialize on this codec's mutex (which we hold here),
            // which is what makes the singleton model safe.
            val mirrorDecoder = codecRef.newDecoderSession()
            try {
                capture.start(chunkDurationMs).collect { chunk ->
                    if (!currentCoroutineContext().isActive) return@collect
                    runChunk(chunk, codecRef, enc, proc, mirrorDecoder, isFinalChunk = chunk.isPartial)
                }
                // Flush any encoder-internal buffered samples (CODEC2
                // half-pair, etc.) and emit them as the LAST packets.
                val tail = enc.flush()
                for ((idx, payload) in tail.withIndex()) {
                    val isLast = idx == tail.lastIndex
                    shipPayload(codecRef, enc, mirrorDecoder, payload, flushed = true, isLast = isLast)
                }
            } finally {
                runCatching { mirrorDecoder.close() }
            }
        }
    }

    private suspend fun runChunk(
        chunk: CaptureChunk,
        codecRef: AudioCodec,
        enc: EncoderSession,
        proc: PttAudioProcessorV2,
        mirrorDecoder: com.commcrete.stardust.audio.v2.codec.DecoderSession,
        isFinalChunk: Boolean,
    ) {
        // 1. DSP + resample.
        val prepared = proc.process(chunk.pcm, chunk.index)

        // 2. Encode. May return zero, one, or several payloads.
        val payloads = enc.encode(prepared)

        // 3. Ship each payload. For non-final chunks the isLast flag
        //    stays false; the trailing-partial-chunk path is handled
        //    by the explicit `enc.flush()` at the end of runEncodeLoop.
        for (payload in payloads) {
            shipPayload(
                codecRef = codecRef,
                enc = enc,
                mirrorDecoder = mirrorDecoder,
                payload = payload,
                flushed = false,
                isLast = false,
            )
        }
        if (isFinalChunk) {
            // The capture source signalled this was the last chunk —
            // proactively flush so the encoder's internal buffer
            // drains while we still hold the mutex.
            val tail = enc.flush()
            for ((idx, payload) in tail.withIndex()) {
                shipPayload(
                    codecRef = codecRef,
                    enc = enc,
                    mirrorDecoder = mirrorDecoder,
                    payload = payload,
                    flushed = true,
                    isLast = idx == tail.lastIndex,
                )
            }
        }
    }

    private suspend fun shipPayload(
        codecRef: AudioCodec,
        enc: EncoderSession,
        mirrorDecoder: com.commcrete.stardust.audio.v2.codec.DecoderSession,
        payload: ByteArray,
        flushed: Boolean,
        isLast: Boolean,
    ) {
        if (payload.isEmpty()) return
        // Prepend the codec's payload prefix (AI's `0x00` model-type
        // byte; CODEC2 returns empty). Sanitize (CODEC2's
        // tail-collision randomizer; AI is identity).
        val prefixed = if (codecRef.sendPayloadPrefix.isEmpty()) payload else {
            val out = ByteArray(codecRef.sendPayloadPrefix.size + payload.size)
            System.arraycopy(codecRef.sendPayloadPrefix, 0, out, 0, codecRef.sendPayloadPrefix.size)
            System.arraycopy(payload, 0, out, codecRef.sendPayloadPrefix.size, payload.size)
            out
        }
        val sanitized = codecRef.sanitizePayload(prefixed)
        val computedIsLast = isLast || codecRef.isLastPacket(sanitized.size, flushed)

        // Mirror runs FIRST so the local WAV reflects what we're
        // about to send. Mirror's decode is on the SAME mutex we hold
        // here, so it can't race the receive path.
        runCatching {
            mirror.onEncoded(codecRef, mirrorDecoder, sanitized)
        }.onFailure { Timber.tag(TAG).w(it, "Session $id mirror onEncoded failed") }

        // Transport — async-fire and forget at the BLE layer; if no
        // destination is set this is a silent no-op (artifact runs).
        transport.send(
            codec = codecRef,
            payload = sanitized,
            isLast = computedIsLast,
            carrier = carrier,
            source = source,
            destination = destination,
        )
    }

    private suspend fun finalizeNow() {
        Timber.tag(TAG).d("Session $id: finalizing")
        try {
            encoder?.close()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Session $id: encoder.close() failed")
        } finally {
            encoder = null
        }
        try {
            processor?.close()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Session $id: processor.close() failed")
        } finally {
            processor = null
        }
        val savedFile: File? = targetFile?.let { file ->
            runCatching { mirror.finalize(codec, file) }
                .onFailure { Timber.tag(TAG).w(it, "Session $id mirror.finalize failed") }
                .getOrNull()
        }
        runCatching { mirror.close() }
        chatId?.let { id ->
            runCatching { onSavePtt(id, savedFile) }
                .onFailure { Timber.tag(TAG).w(it, "Session ${this.id} onSavePtt failed") }
        }
        Timber.tag(TAG).d("Session $id: finalized (savedFile=${savedFile?.absolutePath})")
    }

    companion object {
        private const val TAG = "RecordingSessionV2"

        /** Monotonic id generator shared across all sessions. */
        internal val idGen = AtomicLong(0)
    }
}




