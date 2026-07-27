package com.commcrete.stardust.audio.v2.application.port

import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import kotlinx.coroutines.flow.Flow

/**
 * Application layer — PORTS. The edges of the send pipeline: where PCM comes from and where encoded
 * frames go. Implemented by the framework ring (AudioRecord, BLE/USB). Coroutines are allowed in the
 * application ring; Android is not.
 */

/**
 * Source of raw PCM for one recording. The mic adapter is the single physical [android.media.AudioRecord];
 * [stop] releases the device at key-up (NOT at finalize) — the single fact that lets recording N+1 start
 * capturing while recording N is still encoding/draining (R3). The file adapter reuses this port for the feeder.
 */
interface CaptureSource {

    /** Begin capture for [id]; emits PCM chunks until [stop] or end-of-file. */
    fun start(id: RecordingId): Flow<PcmChunk>

    /** Release the capture device. Any already-emitted chunks continue downstream. */
    suspend fun stop()
}

/**
 * Puts packed bytes on the wire. Called ONLY by the transmit gate, so there is exactly one writer and
 * FIFO order on the link is preserved (R4).
 */
interface SendTransport {
    suspend fun send(frame: EncodedFrame)
}

/**
 * Codec-specific answer to "when is this frame actually transmitted?" — the completion criterion the
 * R4 gate waits on. [deadlineMs] is load-bearing, not advisory: [awaitCommitted] MUST always resolve
 * within it (a lost CODEC2 ACK resolves as committed-with-loss) so the single-writer gate can never
 * hang the whole wire on one recording.
 */
interface TransmitCompletionPolicy {

    /** Hard per-frame ceiling. The gate wraps [awaitCommitted] in a timeout of this length. */
    val deadlineMs: Long

    /** Suspend until [frame] counts as on-the-wire per this codec's [com.commcrete.stardust.audio.v2.domain.TransmitSemantics]. */
    suspend fun awaitCommitted(frame: EncodedFrame, transport: SendTransport)
}
