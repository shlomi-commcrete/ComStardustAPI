package com.commcrete.stardust.audio.v2.adapter.ai

import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.stardust.ai.codec.WavTokenizerDecoder

/**
 * Adapter ring — the WavTokenizer wire format: a 1-byte model selector followed by 12-bit-packed
 * neural codebook tokens (LSB-first). Mirrors the legacy `PlayerUtils.parseAIPackageByFrames` /
 * `PttReceiveManager.handleTokenizerChunk` split and the send-side pack, kept identical for wire compat.
 */
object AiFramePacker {

    /** Model-selector byte for the General model (the only one currently sent). */
    const val MODEL_BYTE_GENERAL = 0x00

    /** 500 ms window the encoder model consumes (24 kHz). */
    const val EXPECTED_SAMPLES = 12_000

    /** [model byte] + pack12(tokens). */
    fun toPayload(tokens: LongArray, modelByte: Int = MODEL_BYTE_GENERAL): ByteArray {
        val packed = BitPacking12.pack12(tokens.toList())
        val out = ByteArray(packed.size + 1)
        out[0] = modelByte.toByte()
        System.arraycopy(packed, 0, out, 1, packed.size)
        return out
    }

    /** The model type carried by byte 0 (defaults to General for a malformed/empty payload). */
    fun modelTypeOf(payload: ByteArray): WavTokenizerDecoder.ModelType {
        if (payload.isEmpty()) return WavTokenizerDecoder.ModelType.General
        return WavTokenizerDecoder.ModelType.fromInt(payload[0].toInt() and 0xFF)
            ?: WavTokenizerDecoder.ModelType.General
    }

    /** The tokens after the model byte (empty for a payload with no token bytes). */
    fun tokensOf(payload: ByteArray): List<Long> =
        if (payload.size <= 1) emptyList()
        else BitPacking12.unpack12(payload.copyOfRange(1, payload.size))
}
