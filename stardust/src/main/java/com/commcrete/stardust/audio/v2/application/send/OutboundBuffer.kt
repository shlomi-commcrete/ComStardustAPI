package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import com.commcrete.stardust.audio.v2.domain.TransmitTicket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Application layer — one recording's private, bounded, sealed FIFO of encoded frames (R4).
 *
 * The recording's encode pipeline is the ONLY producer ([offer]); the [TransmitSequencer] is the ONLY
 * consumer. Bounded capacity means backpressure stays *intra-recording* (a slow wire suspends this
 * recording's own encode, never another's, and never the gate). [seal] is the linchpin of the
 * deadlock-freedom argument: it is called from [RecordingSession]'s `finally`, so the channel closes
 * on LAST **or** ERROR/TIMEOUT/CANCELLED — the gate's head therefore always terminates.
 */
class OutboundBuffer(
    val ticket: TransmitTicket,
    val owner: RecordingId,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val frames = Channel<EncodedFrame>(capacity)

    /**
     * Completed by the [TransmitSequencer] once it has finished with this buffer — i.e. every frame it
     * is going to commit has been handed to the transport. [seal] alone does NOT mean "transmitted":
     * sealing only closes the channel, and up to [capacity] already-produced frames may still be queued
     * for the gate. Anything that tears down per-recording send state (transport routing, in
     * particular) must await this, not just the terminal seal — otherwise the tail of the recording is
     * dropped on the floor by the transport.
     */
    private val drained = CompletableDeferred<Unit>()

    @Volatile
    var reason: TerminalReason? = null
        private set

    /** Enqueue a frame; suspends under backpressure. No-op if already [seal]ed (frame dropped, tail fenced). */
    suspend fun offer(frame: EncodedFrame) {
        try {
            frames.send(frame)
        } catch (_: ClosedSendChannelException) {
            // Sealed by the watchdog/abort-fence while we were producing — discard, don't crash.
        }
    }

    /** Close the FIFO with the first-seen [reason]. Idempotent; unblocks any suspended [offer]. */
    fun seal(reason: TerminalReason) {
        if (this.reason == null) this.reason = reason
        frames.close()
    }

    /** The sequencer's read side. Iterating it drains in FIFO order until the buffer is sealed and empty. */
    fun channel(): ReceiveChannel<EncodedFrame> = frames

    /** Called by the [TransmitSequencer] when it is done with this buffer. Idempotent. */
    fun markDrained() {
        drained.complete(Unit)
    }

    /** Suspends until the sequencer has finished committing this recording's frames. */
    suspend fun awaitDrained() = drained.await()

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
