package com.commcrete.stardust.audio.v2.adapter.ai

import com.commcrete.stardust.audio.v2.application.port.SendTransport
import com.commcrete.stardust.audio.v2.application.port.TransmitCompletionPolicy
import com.commcrete.stardust.audio.v2.domain.EncodedFrame

/**
 * Adapter ring — WavTokenizer is FIRE_AND_FORGET (opcode 0x3A, unacked): a frame counts as committed
 * once handed to the transport's single-writer FIFO queue. [awaitCommitted] returns as soon as
 * `transport.send` does. [deadlineMs] is the gate's safety ceiling.
 */
class AiCompletionPolicy(
    override val deadlineMs: Long = DEFAULT_DEADLINE_MS,
) : TransmitCompletionPolicy {

    override suspend fun awaitCommitted(frame: EncodedFrame, transport: SendTransport) {
        transport.send(frame)
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 1_500L
    }
}
