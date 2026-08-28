package com.commcrete.stardust.audio.v2.adapter.ai

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
 * Adapter ring — the WavTokenizer neural codec as a v2 plugin (24 kHz, opcode 0x3A, fire-and-forget).
 *
 * Registering an instance via `CodecBootstrap.bootstrap(...)` enables AI PTT on the new pipeline (R1).
 * The playback sink (Android AudioTrack) is injected, keeping this adapter Android-free.
 *
 * Isolation note (decision #1): the encoder is stateless per window; the decoder relocates continuity
 * into the per-stream [WavTokenizerDecoderSession] and save/restores under the codec mutex. This is
 * the (c) "serialized + save/restore" strategy — SAFE, but it does NOT permit concurrent AI decode.
 * Run `Codec2IsolationGoldenTest`'s sibling for this codec on a device before adopting a module pool.
 */
class WavTokenizerCodec(
    private val playbackSinkFactory: (StreamKey) -> PlaybackSink,
    private val completionDeadlineMs: Long = AiCompletionPolicy.DEFAULT_DEADLINE_MS,
) : AudioCodec {

    override val codecId = CodecId.WAVTOKENIZER
    override val sampleRateHz = 24_000
    override val sendOpcode = 0x3A
    override val transmitSemantics = TransmitSemantics.FIRE_AND_FORGET

    override fun newEncoderSession(id: RecordingId): EncoderSession =
        WavTokenizerEncoderSession(id, codecId)

    override fun newDecoderSession(key: StreamKey): DecoderSession =
        WavTokenizerDecoderSession()

    override fun newPlaybackSink(key: StreamKey): PlaybackSink =
        playbackSinkFactory(key)

    override fun completionPolicy(): TransmitCompletionPolicy =
        AiCompletionPolicy(completionDeadlineMs)
}
