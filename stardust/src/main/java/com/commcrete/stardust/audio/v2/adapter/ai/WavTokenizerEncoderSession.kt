package com.commcrete.stardust.audio.v2.adapter.ai

import com.commcrete.stardust.ai.codec.AIModuleInitializer
import com.commcrete.stardust.audio.v2.application.port.EncoderSession
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.SequenceNumber

/**
 * Adapter ring — WavTokenizer (neural) encoder for one recording.
 *
 * The encoder model is stateless per 500 ms window (all continuity lives in the DECODER), so isolation
 * is trivial — the only shared thing is the PyTorch module, and the native `forward()` is serialized by
 * `CodecRegistry.withCodec("wavtokenizer")` (applied by the caller, [RecordingSession]).
 *
 * [encode] accumulates 24 kHz PCM into 12000-sample windows, runs [com.commcrete.stardust.ai.codec.WavTokenizerEncoder.encode]
 * (which pads/trims + returns tokens, never throws — falls back to zero tokens), packs them, and emits
 * one [EncodedFrame] per window. [drain] encodes the remainder as the terminal frame.
 */
class WavTokenizerEncoderSession(
    private val id: RecordingId,
    private val codecId: CodecId,
) : EncoderSession {

    // Shared model instance (assumes AIModuleInitializer.initModules ran — same assumption as legacy PttSendManager).
    private val encoder = AIModuleInitializer.wavTokenizerEncoder

    private val pending = ArrayList<Short>(AiFramePacker.EXPECTED_SAMPLES * 2)
    private var seq = 0

    override suspend fun encode(chunk: PcmChunk): List<EncodedFrame> {
        val out = ArrayList<EncodedFrame>()
        for (s in chunk.samples) pending.add(s)
        while (pending.size >= AiFramePacker.EXPECTED_SAMPLES) {
            val window = ShortArray(AiFramePacker.EXPECTED_SAMPLES) { pending[it] }
            pending.subList(0, AiFramePacker.EXPECTED_SAMPLES).clear()
            out += frameOf(encoder.encode(window), terminal = false)
        }
        return out
    }

    override suspend fun drain(): List<EncodedFrame> {
        if (pending.isEmpty()) return listOf(frameOf(LongArray(0), terminal = true))
        val window = ShortArray(pending.size) { pending[it] }
        pending.clear()
        return listOf(frameOf(encoder.encode(window), terminal = true))
    }

    override fun close() {
        pending.clear()
    }

    private fun frameOf(tokens: LongArray, terminal: Boolean) = EncodedFrame(
        codecId = codecId,
        payload = AiFramePacker.toPayload(tokens),
        isTerminal = terminal,
        owner = id,
        seq = SequenceNumber(seq++),
    )
}
