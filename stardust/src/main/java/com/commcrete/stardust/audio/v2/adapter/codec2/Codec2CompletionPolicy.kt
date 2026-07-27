package com.commcrete.stardust.audio.v2.adapter.codec2

import com.commcrete.stardust.audio.v2.application.port.SendTransport
import com.commcrete.stardust.audio.v2.application.port.TransmitCompletionPolicy
import com.commcrete.stardust.audio.v2.domain.EncodedFrame

/**
 * Adapter ring — CODEC2 is ACK-tracked (opcode 0x15, the legacy `isNeedAck` path).
 *
 * The ACK correlation + bounded retransmit live inside the framework `BleSendTransport` (which wraps
 * the legacy DataManager/ClientConnection resend), so [awaitCommitted] simply awaits `transport.send`,
 * which suspends until the frame is committed. [deadlineMs] is the outer ceiling the transmit gate
 * enforces via `withTimeout`, so a permanently lost ACK resolves as committed-with-loss instead of
 * stalling the wire.
 */
class Codec2CompletionPolicy(
    override val deadlineMs: Long = DEFAULT_DEADLINE_MS,
) : TransmitCompletionPolicy {

    override suspend fun awaitCommitted(frame: EncodedFrame, transport: SendTransport) {
        transport.send(frame)
    }

    companion object {
        // Generous vs a single 77-byte / 880 ms packet's ACK round-trip; the gate caps the worst case.
        const val DEFAULT_DEADLINE_MS = 2_000L
    }
}
