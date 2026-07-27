package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import com.commcrete.stardust.audio.v2.domain.TransmitTicket
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

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
