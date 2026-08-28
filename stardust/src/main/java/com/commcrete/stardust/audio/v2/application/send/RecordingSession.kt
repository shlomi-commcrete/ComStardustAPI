package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.AudioCodec
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.application.port.Clock
import com.commcrete.stardust.audio.v2.application.port.EncoderSession
import com.commcrete.stardust.audio.v2.application.port.KeepAlive
import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.application.port.MessageStore
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import com.commcrete.stardust.audio.v2.domain.TransmitTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application layer — one key-down..key-up recording (R3 isolation + R4 producer side).
 *
 * Isolation: this session owns its OWN [encoder] and [dsp] instances — recording N+1 is a different
 * session with different instances, so it physically cannot mutate this recording's continuity /
 * resampler / biquad state. There is no shared singleton and no global `reset()`.
 *
 * The pipeline runs: capture → [PttAudioProcessorV2.process] → `CodecRegistry.withCodec { encode }`
 * → [OutboundBuffer.offer]. The [outbound] buffer is drained by the [TransmitSequencer], never here.
 *
 * Terminal guarantee: [outbound] is sealed in `finally` under [NonCancellable], so a terminal marker
 * is emitted on EVERY exit — normal key-up (LAST), error (ERROR), timeout (TIMEOUT), or cancel
 * (CANCELLED). That is what lets the gate's head always advance. [encoder] is closed only after the
 * tail [EncoderSession.drain] has returned, so a pooled native module is never reclaimed mid-forward.
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
    private val scope: CoroutineScope,
) {
    val ticket: TransmitTicket get() = outbound.ticket

    private val finalized = CompletableDeferred<TerminalReason>()
    private val sealed = AtomicBoolean(false)
    private var job: Job? = null

    /** Launch the capture→encode pipeline. Idempotent. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            val startMs = clock.nowMs()
            var frameCount = 0
            var reason = TerminalReason.LAST
            keepAlive.acquire() // held until the finally below — screen-off must not suspend encode/drain
            try {
                store.onRecordingStarted(id, codec.codecId, peer)
                capture.start(id).collect { raw ->
                    // Wall-clock max-PTT watchdog: stop the mic; flow completion below flushes the tail.
                    if (clock.nowMs() - startMs > watchdogMs) {
                        capture.stop()
                        return@collect
                    }
                    val processed = dsp.process(raw)
                    mirror.accept(processed)
                    val frames = CodecRegistry.withCodec(codec.codecId) { encoder.encode(processed) }
                    frames.forEach { outbound.offer(it); frameCount++ }
                }
                // Flow completed (key-up / watchdog stop / EOF): flush resampler tail, then encoder tail.
                val dspTail = dsp.flush()
                if (dspTail.samples.isNotEmpty()) {
                    mirror.accept(dspTail)
                    CodecRegistry.withCodec(codec.codecId) { encoder.encode(dspTail) }
                        .forEach { outbound.offer(it); frameCount++ }
                }
                CodecRegistry.withCodec(codec.codecId) { encoder.drain() }
                    .forEach { outbound.offer(it); frameCount++ }
            } catch (e: CancellationException) {
                reason = TerminalReason.CANCELLED
                throw e
            } catch (_: Throwable) {
                reason = TerminalReason.ERROR
            } finally {
                withContext(NonCancellable) {
                    sealOnce(reason)                       // ALWAYS emits a terminal marker → gate advances
                    runCatching { encoder.close() }        // only now, after drain() returned
                    runCatching { dsp.close() }
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

    /** Key-up: release the mic. Capture flow completes → tail flush → seal(LAST). Encoding/sending continues. */
    suspend fun stopCapture() {
        runCatching { capture.stop() }
    }

    /** Suspends until this recording has fully finalized, returning why it sealed. */
    suspend fun awaitFinalized(): TerminalReason = finalized.await()

    /**
     * Suspends until the transmit gate has committed every frame of this recording to the transport.
     * Strictly later than [awaitFinalized]: sealing only closes the outbound channel, so frames already
     * buffered are still in flight. Tear down per-recording send state (routing) only after this.
     */
    suspend fun awaitTransmitted() = outbound.awaitDrained()

    private fun sealOnce(reason: TerminalReason) {
        if (sealed.compareAndSet(false, true)) {
            outbound.seal(reason)
        }
    }
}
