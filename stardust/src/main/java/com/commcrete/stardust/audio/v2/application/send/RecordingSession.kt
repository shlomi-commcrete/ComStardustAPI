package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.AudioCodec
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.application.port.Clock
import com.commcrete.stardust.audio.v2.application.port.DecoderSession
import com.commcrete.stardust.audio.v2.application.port.EncoderSession
import com.commcrete.stardust.audio.v2.application.port.KeepAlive
import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.application.port.MaxPttTimeoutNotifier
import com.commcrete.stardust.audio.v2.application.port.MessageStore
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import com.commcrete.stardust.audio.v2.domain.TransmitTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Application layer — one key-down..key-up recording (R3 isolation + R4 producer side).
 *
 * Isolation: this session owns its OWN [encoder] and [dsp] instances — recording N+1 is a different
 * session with different instances, so it physically cannot mutate this recording's continuity /
 * resampler / biquad state. There is no shared singleton and no global `reset()`.
 *
 * The pipeline runs: capture → [PttAudioProcessorV2.process] → `CodecRegistry.withEncode { encode }`
 * → [OutboundBuffer.offer]. The [outbound] buffer is drained by the [TransmitSequencer], never here.
 *
 * Local mirror: when [mirror] is active, every frame handed to [outbound] is ALSO self-decoded — by
 * this session's own [DecoderSession], on its own [mirrorJob] — so the saved file holds what the
 * receiver reconstructs rather than the encoder's input. The decode runs off the capture/encode path
 * (it is a second model forward pass for AI), on [decodeContext] — the same decode thread the receive
 * path uses, never the encode one — and is skipped entirely for a no-op mirror.
 *
 * Terminal guarantee: [outbound] is sealed in `finally` under [NonCancellable], so a terminal marker
 * is emitted on EVERY exit — normal key-up (LAST), error (ERROR), timeout (TIMEOUT), or cancel
 * (CANCELLED). That is what lets the gate's head always advance. [encoder] is closed only after the
 * tail [EncoderSession.drain] has returned, so a pooled native module is never reclaimed mid-forward.
 *
 * Liveness: the two deadlines below are what make that terminal guarantee hold even when the capture
 * device misbehaves, so a session can never outlive its recording and stack up.
 *  - the max-PTT watchdog is a TIMER on its own child coroutine, not a check inside the collect: a mic
 *    that emits nothing (stolen by a call, USB audio yanked, driver wedge) is exactly the case that
 *    needs stopping, and it is the one case a per-chunk check can never see;
 *  - [stopCapture] arms a [captureStopGraceMs] deadline after which this session's [job] is cancelled.
 *    Asking the device to stop is not the same as it having stopped — a thread blocked in a native read
 *    may never return — and without the deadline that session never seals, never releases the wake
 *    lock, and never lets the host release its routing.
 */
class RecordingSession(
    val id: RecordingId,
    private val peer: StreamKey,
    private val codec: AudioCodec,
    private val capture: CaptureSource,
    private val dsp: PttAudioProcessorV2,
    private val encoder: EncoderSession,
    private val outbound: OutboundBuffer,
    private val mirror: LocalMirror,
    private val store: MessageStore,
    private val keepAlive: KeepAlive,
    private val clock: Clock,
    private val watchdogMs: Long,
    private val captureStopGraceMs: Long,
    private val maxTimeout: MaxPttTimeoutNotifier,
    private val scope: CoroutineScope,
    /** Where the mirror's self-decode runs. Empty inherits [scope]'s dispatcher. */
    private val decodeContext: CoroutineContext = EmptyCoroutineContext,
) {
    val ticket: TransmitTicket get() = outbound.ticket

    private val finalized = CompletableDeferred<TerminalReason>()
    private val sealed = AtomicBoolean(false)

    /** Read by [armCaptureStopDeadline] from another coroutine, so it must be published. */
    @Volatile
    private var job: Job? = null

    /**
     * Completed once the capture flow has actually returned — the device is released and no further
     * chunk can arrive. [armCaptureStopDeadline] waits on THIS rather than on [job], because the job
     * legitimately outlives key-up to flush the DSP/encoder tail and push it through wire backpressure;
     * only the capture half is on a deadline.
     */
    private val captureEnded = CompletableDeferred<Unit>()
    private val stopDeadlineArmed = AtomicBoolean(false)

    /**
     * Frames queued for the self-decode mirror. UNLIMITED and fed with `trySend` BEFORE
     * [OutboundBuffer.offer] (which suspends under wire backpressure), so mirroring never costs the
     * encode loop a suspension and a slow decode can never delay a frame reaching the transport.
     */
    private val mirrorFrames = Channel<EncodedFrame>(Channel.UNLIMITED)
    private var mirrorJob: Job? = null
    private val mirroring = mirror.isActive

    /** Guards [announceMaxTimeoutOnce] — one announcement per recording, see there. */
    private val maxTimeoutAnnounced = AtomicBoolean(false)

    /** Launch the capture→encode pipeline. Idempotent. */
    fun start() {
        if (job != null) return
        if (mirroring) mirrorJob = scope.launch(decodeContext) { runMirrorDecode() }
        job = scope.launch {
            var frameCount = 0
            var reason = TerminalReason.LAST
            keepAlive.acquire() // held until the finally below — screen-off must not suspend encode/drain
            // A child, so it dies with this job; explicitly cancelled in the finally because a parent
            // does not complete while a child is still sitting in its delay.
            val watchdog = launch { runMaxPttWatchdog() }
            try {
                store.onRecordingStarted(id, codec.codecId, peer)
                capture.start(id).collect { raw ->
                    val processed = dsp.process(raw)
                    val frames = CodecRegistry.withEncode(codec.codecId) { encoder.encode(processed) }
                    frames.forEach { dispatchFrame(it); frameCount++ }
                }
                captureEnded.complete(Unit) // the device is genuinely released → stop deadline stands down
                // The ceiling is on how long the mic is open, so it is spent here, not in the finally:
                // the tail flush below can take a while (an AI drain is a model forward pass), and a
                // key-up just under the ceiling must not beep at the host part-way through it.
                watchdog.cancel()
                // Flow completed (key-up / watchdog stop / EOF): flush resampler tail, then encoder tail.
                val dspTail = dsp.flush()
                if (dspTail.samples.isNotEmpty()) {
                    CodecRegistry.withEncode(codec.codecId) { encoder.encode(dspTail) }
                        .forEach { dispatchFrame(it); frameCount++ }
                }
                CodecRegistry.withEncode(codec.codecId) { encoder.drain() }
                    .forEach { dispatchFrame(it); frameCount++ }
            } catch (e: CancellationException) {
                reason = TerminalReason.CANCELLED
                throw e
            } catch (_: Throwable) {
                reason = TerminalReason.ERROR
            } finally {
                watchdog.cancel()
                withContext(NonCancellable) {
                    captureEnded.complete(Unit)            // idempotent; disarms the deadline on every exit
                    sealOnce(reason)                       // ALWAYS emits a terminal marker → gate advances
                    runCatching { encoder.close() }        // only now, after drain() returned
                    runCatching { dsp.close() }
                    // Every transmitted frame is already queued, so closing + joining drains the
                    // mirror's decode backlog. MUST precede finalizeMirror(), which writes the WAV
                    // from what the mirror has accepted so far.
                    mirrorFrames.close()
                    runCatching { mirrorJob?.join() }
                    runCatching { mirror.finalizeMirror() }
                    runCatching { store.onRecordingFinalized(id, frameCount, reason) }
                    runCatching { keepAlive.release() } // balances the acquire above, exactly once
                    // Resolved LAST, after the mirror WAV is on disk: awaitFinalized() is what the host
                    // uses to persist a history row pointing at that file, so completing it earlier
                    // (next to the seal) loses the race and the row is silently skipped.
                    finalized.complete(reason)
                }
            }
        }
    }

    /**
     * Key-up: release the mic. Capture flow completes → tail flush → seal(LAST). Encoding/sending continues.
     *
     * Arms the capture-stop deadline as well, because [CaptureSource.stop] only *asks* the device to
     * stop, and no adapter can ask harder than the driver underneath it allows. See
     * [armCaptureStopDeadline] for what happens when the device does not come back.
     */
    suspend fun stopCapture() {
        runCatching { capture.stop() }
        armCaptureStopDeadline()
    }

    /**
     * Give up on this recording now: release the device and cancel the pipeline, which seals CANCELLED
     * through the same `finally` as every other exit, so the gate advances and nothing is left held.
     *
     * Unlike [stopCapture] this does not let the tail out — whatever is still queued is lost, which is
     * the price of the caller's reason for asking (the concurrency cap, or the whole pipeline shutting
     * down). Idempotent, and harmless on a recording that has already finalized.
     */
    suspend fun abort() {
        runCatching { capture.stop() }
        job?.cancel(CancellationException("recording aborted"))
    }

    /** Whether this recording has sealed and finished its cleanup. False while it is still encoding. */
    val isFinalized: Boolean get() = finalized.isCompleted

    /** Suspends until this recording has fully finalized, returning why it sealed. */
    suspend fun awaitFinalized(): TerminalReason = finalized.await()

    /**
     * Suspends until the transmit gate has committed every frame of this recording to the transport.
     * Strictly later than [awaitFinalized]: sealing only closes the outbound channel, so frames already
     * buffered are still in flight. Tear down per-recording send state (routing) only after this.
     */
    suspend fun awaitTransmitted() = outbound.awaitDrained()

    /**
     * Max-PTT ceiling, as a timer on its own coroutine rather than a check inside the collect — see the
     * class KDoc for why a mic that emits nothing is precisely the case that needs it.
     *
     * [clock] stays the source of truth and `delay` is only the scheduler, so the two cannot drift into
     * disagreement: the loop re-derives the remaining time from the wall clock, and a `delay` that
     * under-sleeps (or a host that steps the clock) costs another lap instead of ending the recording
     * early. A non-positive [watchdogMs] means no ceiling — firing at once is never what that configures.
     */
    private suspend fun runMaxPttWatchdog() {
        if (watchdogMs <= 0L) return
        val startMs = clock.nowMs()
        var remainingMs = watchdogMs
        while (remainingMs > 0L) {
            delay(remainingMs)
            remainingMs = watchdogMs - (clock.nowMs() - startMs)
        }
        // [start] cancels this coroutine the moment capture ends, but not instantaneously, so re-check:
        // a key-up that landed in the last instant before the ceiling has nothing to announce, and the
        // host would otherwise beep for a PTT the user released in time.
        if (captureEnded.isCompleted) return
        // Announced before the stop, because stopping is what ends this coroutine.
        announceMaxTimeoutOnce()
        stopCapture()
    }

    /**
     * The teardown deadline: if the capture flow has not completed [captureStopGraceMs] after the device
     * was asked to stop, cancel [job]. Cancellation unblocks the collect, runs the `finally` and seals as
     * CANCELLED — a bounded loss of the tail instead of a session that lives forever holding the wake
     * lock, its encoder and DSP, and the host's routing entry for a mic that is never coming back.
     *
     * The grace period must comfortably cover an ordinary key-up teardown (finish the in-flight read,
     * release the `AudioRecord`, undo the communication-device route), since firing early would turn a
     * healthy recording's LAST into CANCELLED and drop its tail.
     *
     * Launched in [scope], deliberately NOT as a child of [job]: it has to outlive a job that is stuck,
     * and as a child it would also keep an already-finished job from completing for the whole grace period.
     */
    private fun armCaptureStopDeadline() {
        if (!stopDeadlineArmed.compareAndSet(false, true)) return
        scope.launch {
            if (withTimeoutOrNull(captureStopGraceMs) { captureEnded.await() } == null) {
                job?.cancel(
                    CancellationException("capture did not stop within ${captureStopGraceMs}ms")
                )
            }
        }
    }

    /**
     * Hand one encoded frame to the transport, and to the mirror when one is active. The mirror queue
     * is fed first, and never suspends: a frame fenced off by a seal (watchdog / abort) is still worth
     * mirroring, and the local file must not be shortened by wire backpressure.
     */
    private suspend fun dispatchFrame(frame: EncodedFrame) {
        if (mirroring) mirrorFrames.trySend(frame)
        outbound.offer(frame)
    }

    /**
     * Self-decode loop for the local mirror — the v2 equivalent of the legacy AI
     * `PttSendManager.mirrorDecodeChunk` job and of `AudioRecorderCodec2`'s `onEncodedFrame` decode.
     *
     * Decodes the very bytes that went on the wire, through this codec's own [DecoderSession] — the
     * same class the receiver runs — so the saved WAV is the received audio, not the encoder input.
     * The session owns this decoder outright (R3/R5 isolation): it carries this recording's decode
     * continuity and nothing else's. Decode runs under `CodecRegistry.withDecode`, which is what keeps
     * these mutations of the shared decoder serialized against every receive-stream decode.
     *
     * Per-frame [runCatching]: one bad frame must not abandon the rest of the recording's mirror.
     */
    private suspend fun runMirrorDecode() {
        val decoder: DecoderSession = codec.newDecoderSession(peer)
        try {
            for (frame in mirrorFrames) {
                runCatching {
                    val pcm = CodecRegistry.withDecode(codec.codecId) { decoder.decode(frame) }
                    if (pcm.samples.isNotEmpty()) mirror.accept(pcm)
                }
            }
        } finally {
            runCatching { decoder.close() }
        }
    }

    /**
     * Tell the host the max-PTT timeout was hit — the beep + callbacks legacy fired from
     * `PttSendManager.enforceMaxPttTimeout` and `AudioRecorderCodec2.onPipelinePacketSent`.
     *
     * Single-shot — legacy solved the same problem by clearing its `onMaxTimeoutReached` hook before
     * firing. [runMaxPttWatchdog] reaches this once per recording by construction, so the guard is here
     * to keep that true for any later caller rather than to fix a known repeat.
     *
     * [runCatching] because a throwing host callback must not kill the very recording it is being told
     * about — the announcement is an aside to the teardown, never a step in it.
     */
    private fun announceMaxTimeoutOnce() {
        if (!maxTimeoutAnnounced.compareAndSet(false, true)) return
        runCatching { maxTimeout.onMaxTimeoutReached(id) }
    }

    private fun sealOnce(reason: TerminalReason) {
        if (sealed.compareAndSet(false, true)) {
            outbound.seal(reason)
        }
    }
}
