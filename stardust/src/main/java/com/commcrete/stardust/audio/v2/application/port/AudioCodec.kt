package com.commcrete.stardust.audio.v2.application.port

import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TransmitSemantics

/**
 * Application layer — PORTS. Interfaces the outer (adapter/framework) rings implement.
 * This ring depends only on [com.commcrete.stardust.audio.v2.domain]; never on Android, PyTorch,
 * BLE, or the legacy PTT code.
 *
 * ── [AudioCodec] is the single R1 seam ────────────────────────────────────────────────────────
 * A codec self-describes its rate / opcode / ACK semantics and hands back fresh per-recording and
 * per-stream sessions. Registering one AudioCodec is the entire cost of adding a new codec —
 * coordinators, the transmit gate, the receive dispatch, and the UI all depend only on this port.
 *
 * Note (dependency rule): the legacy `RecorderUtils.CODE_TYPE` ↔ [CodecId] mapping deliberately does
 * NOT live here — it belongs in the adapter-ring bootstrap so this port stays free of legacy types.
 */
interface AudioCodec {

    /** Stable registry identity, e.g. `CodecId("codec2")`. */
    val codecId: CodecId

    /** Native PCM rate for both encode input and decode output (CODEC2 8000, WavTokenizer 24000). */
    val sampleRateHz: Int

    /** Wire opcode this codec sends/receives under (CODEC2 0x15, WavTokenizer 0x3A). Populates dispatch tables. */
    val sendOpcode: Int

    /** Whether a sent frame is confirmed by link ACK or fire-and-forget — see [TransmitSemantics]. */
    val transmitSemantics: TransmitSemantics

    /** Fresh encoder owning 100% of this recording's continuity state — the basis of R3 isolation. */
    fun newEncoderSession(id: RecordingId): EncoderSession

    /** Fresh decoder owning its own per-stream continuity state — the basis of R5 multi-stream receive. */
    fun newDecoderSession(key: StreamKey): DecoderSession

    /** Fresh, independently volume-controllable output for one receive stream (R6). */
    fun newPlaybackSink(key: StreamKey): PlaybackSink

    /** Defines when a frame counts as "on the wire" for the R4 ordering gate; carries the hard deadline. */
    fun completionPolicy(): TransmitCompletionPolicy
}
