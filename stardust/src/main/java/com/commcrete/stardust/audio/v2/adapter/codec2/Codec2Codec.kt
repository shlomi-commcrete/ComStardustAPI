package com.commcrete.stardust.audio.v2.adapter.codec2

import com.commcrete.stardust.audio.v2.application.port.AudioCodec
import com.commcrete.stardust.audio.v2.application.port.DecoderSession
import com.commcrete.stardust.audio.v2.application.port.EncoderSession
import com.commcrete.stardust.audio.v2.application.port.PlaybackSink
import com.commcrete.stardust.audio.v2.application.port.TransmitCompletionPolicy
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TransmitSemantics

/**
 * Adapter ring — the classic CODEC2 700C codec as a v2 plugin (8 kHz, opcode 0x15, ACK-tracked).
 *
 * Registering an instance via `CodecBootstrap.bootstrap(...)` is the entire cost of enabling CODEC2 on
 * the new pipeline (R1). The AudioTrack-backed playback sink is Android framework, so it is INJECTED
 * as [playbackSinkFactory] rather than constructed here — that keeps this codec adapter free of a
 * direct Android dependency and lets the host wire the real `Codec2PlaybackSink` (LoudnessEnhancer +
 * BLE-SCO routing) when it exists.
 */
class Codec2Codec(
    private val playbackSinkFactory: (StreamKey) -> PlaybackSink,
    private val completionDeadlineMs: Long = Codec2CompletionPolicy.DEFAULT_DEADLINE_MS,
) : AudioCodec {

    override val codecId = CodecId("codec2")
    override val sampleRateHz = 8_000
    override val sendOpcode = 0x15
    override val transmitSemantics = TransmitSemantics.ACK_TRACKED

    override fun newEncoderSession(id: RecordingId): EncoderSession =
        Codec2EncoderSession(id, codecId)

    override fun newDecoderSession(key: StreamKey): DecoderSession =
        Codec2DecoderSession()

    override fun newPlaybackSink(key: StreamKey): PlaybackSink =
        playbackSinkFactory(key)

    override fun completionPolicy(): TransmitCompletionPolicy =
        Codec2CompletionPolicy(completionDeadlineMs)
}
