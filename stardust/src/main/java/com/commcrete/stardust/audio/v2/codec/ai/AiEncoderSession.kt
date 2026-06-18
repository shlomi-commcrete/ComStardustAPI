package com.commcrete.stardust.audio.v2.codec.ai

import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.stardust.ai.codec.WavTokenizerEncoder
import com.commcrete.stardust.audio.v2.codec.EncoderSession

/**
 * Per-recording AI encoder.
 *
 * Wraps the shared [WavTokenizerEncoder] singleton. The model's
 * `encode(ShortArray) → LongArray` call has no per-stream state of its
 * own (the heavy lifting is the PyTorch model forward pass), so this
 * session keeps no fields beyond the encoder reference — `flush()` and
 * `close()` are no-ops.
 *
 * **Threading.** Every method MUST be called inside
 * [com.commcrete.stardust.audio.v2.codec.CodecRegistry.withCodec],
 * because the PyTorch model itself is not thread-safe and is also
 * touched by the receive-side decoder (via the same mutex).
 *
 * **Output.** Each call to [encode] runs the encoder → packs the
 * resulting 12-bit tokens via [BitPacking12.pack12] → returns the
 * packed bytes wrapped in a single-element list. The coordinator
 * prepends the `0x00` model-type prefix and ships the result.
 */
internal class AiEncoderSession(
    private val encoder: WavTokenizerEncoder,
) : EncoderSession {

    override fun encode(pcm: ShortArray): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        // Match PttSendManager.handleTokenizerChunk:
        //   val chunkCodes = wavTokenizerEncoder.encode(pcmArray)
        //   val packedData = BitPacking12.pack12(chunkCodes.toList())
        val tokens = encoder.encode(pcm)
        val packed = BitPacking12.pack12(tokens.toList())
        return listOf(packed)
    }

    /**
     * AI has no half-buffered samples to flush — the encoder consumes
     * a full chunk per call and emits a full packet. The coordinator
     * still calls this so the wire format's `isLast` bit gets set on
     * a synthetic final empty payload if needed; we return empty so
     * the coordinator instead marks the LAST chunk it already sent as
     * the last (this preserves the existing AI behavior).
     */
    override fun flush(): List<ByteArray> = emptyList()

    override fun close() {
        // No per-session resources to release — the encoder singleton
        // is owned by AIModuleInitializer for the process lifetime.
    }
}

