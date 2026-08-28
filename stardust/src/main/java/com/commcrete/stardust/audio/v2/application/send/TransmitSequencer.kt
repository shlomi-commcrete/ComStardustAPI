package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.Clock
import com.commcrete.stardust.audio.v2.application.port.SendTransport
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import com.commcrete.stardust.audio.v2.domain.TransmitTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Application layer — the R4 transmit-ordering gate: a SINGLE-WRITER actor that commits recordings
 * to the wire strictly in capture-start order, while their encode pipelines run concurrently.
 *
 * [reserve] is called synchronously from [PttSendCoordinator.restart] under that coordinator's mutex,
 * so ticket order == capture-start order == on-the-wire order. Each recording offers into its OWN
 * [OutboundBuffer]; the gate drains buffers one at a time in reserve order and never blocks on a
 * not-yet-head recording.
 *
 * Why it cannot deadlock or stall the wire forever:
 *  - the only suspension point is the head buffer's frame `receive`, and the head buffer is always
 *    [OutboundBuffer.seal]ed in [RecordingSession]'s `finally` → the head's channel always closes;
 *  - every commit is wrapped in `withTimeoutOrNull(policy.deadlineMs)`, so a lost CODEC2 ACK / stuck
 *    write can never suspend the gate past that ceiling;
 *  - a per-head wall-clock watchdog force-seals the head and DISCARDS its remaining tail, so a stalled
 *    recording N can neither hold the wire nor interleave its late frames with recording N+1.
 *
 * The drain loop is a SINGLE coroutine, so it is the sole writer to [transport] and processes
 * buffers strictly sequentially regardless of how [scope]'s dispatcher schedules threads — that is
 * what makes "single writer, FIFO wire order" true. A plain `Dispatchers.IO` scope is fine.
 */
class TransmitSequencer(
    private val transport: SendTransport,
    private val clock: Clock,
    private val headTimeoutMs: Long,
    scope: CoroutineScope,
) {
    private val ticketSeq = AtomicLong(0L)
    private val queue = Channel<OutboundBuffer>(Channel.UNLIMITED)

    private val loop: Job = scope.launch {
        for (buffer in queue) {
            drainHead(buffer)
        }
    }

    /** Reserve the next start-order slot and its private buffer. Call under the coordinator's restart mutex. */
    fun reserve(id: RecordingId): OutboundBuffer {
        val buffer = OutboundBuffer(TransmitTicket(ticketSeq.incrementAndGet()), id)
        // UNLIMITED — only fails after shutdown, in which case nothing will ever drain this buffer, so
        // release its waiters here rather than leaving awaitDrained() suspended forever.
        if (queue.trySend(buffer).isFailure) buffer.markDrained()
        return buffer
    }

    /** Stop the actor. In-flight head drain is cancelled; no further buffers are accepted. */
    fun shutdown() {
        queue.close()
        loop.cancel()
        // Release anything still queued so awaitDrained() callers can never hang on a dead actor.
        while (true) {
            val buffer = queue.tryReceive().getOrNull() ?: break
            buffer.markDrained()
        }
    }

    private suspend fun drainHead(buffer: OutboundBuffer) {
        val headStart = clock.nowMs()
        try {
            for (frame in buffer.channel()) {
                if (clock.nowMs() - headStart > headTimeoutMs) {
                    // Abort-fence: force-close (unblocks the producer) and discard the rest of this
                    // recording's tail so its late frames can never interleave with the next recording.
                    buffer.seal(TerminalReason.TIMEOUT)
                    break
                }
                val policy = CodecRegistry.byCodecId(frame.codecId).completionPolicy()
                // Hard ceiling: a stuck ACK/write resolves as committed-with-loss instead of hanging the gate.
                withTimeoutOrNull(policy.deadlineMs) {
                    policy.awaitCommitted(frame, transport)
                }
            }
            // Channel closed (LAST / ERROR / TIMEOUT / CANCELLED) or watchdog break → advance to next buffer.
        } finally {
            // Signals "this recording's frames are all on the transport" — per-recording send state
            // (routing) may only be torn down now, NOT at the terminal seal. In a `finally` so a
            // cancelled/aborted head still releases its waiters.
            buffer.markDrained()
        }
    }
}
