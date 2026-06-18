package com.commcrete.stardust.audio.v2.codec.codec2

import com.commcrete.stardust.audio.v2.codec.DecoderSession
import com.commcrete.stardust.util.audio.RecorderUtils
import com.ustadmobile.codec2.Codec2Decoder

/**
 * Per-stream CODEC2 decoder.
 *
 * Holds its own [Codec2Decoder] instance — unlike the AI decoder
 * (which is a heavyweight ML singleton multiplexed via state
 * snapshot/restore), `Codec2Decoder` is cheap to construct and has
 * no shared cross-stream state, so per-stream instances are the
 * cleanest design.
 *
 * The legacy implementation in
 * [com.commcrete.stardust.util.audio.PlayerUtils] kept a single
 * `mCodec2Decoder` field that was hit by every incoming PTT packet
 * without any synchronization, AND was concurrently touched by the
 * CODEC2 send-side self-decode for the WAV mirror. v2 fixes both
 * bugs by:
 *  - giving each stream its own decoder (no cross-stream interference),
 *  - putting every [decode] call under
 *    [com.commcrete.stardust.audio.v2.codec.CodecRegistry.withCodec]
 *    (no interference with the send-side self-decoder).
 *
 * # Decode contract
 *
 * Incoming packets are 77-byte BLE payloads. They contain eleven
 * 7-byte packed pairs (each pair = two 4-byte codec2 frames). We
 * unpack the pairs, feed each 4-byte frame through
 * [Codec2Decoder.readFrame], and concatenate the resulting 320-sample
 * PCM blocks into one `ShortArray`.
 *
 * For partial-final packets (sent during stream end via
 * [Codec2EncoderSession.flush]) the trailing zero-padded bytes
 * decode to silence, which is the correct end-of-stream behavior.
 */
internal class Codec2DecoderSession : DecoderSession {

    private var decoder: Codec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)

    override fun reset() {
        // Codec2Decoder per-frame state is reset by destroying and
        // recreating. The cost is microseconds — far cheaper than
        // the AI decoder so a fresh instance is the simplest reset.
        runCatching { decoder.destroy() }
        decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
    }

    override fun decode(payload: ByteArray): ShortArray {
        if (payload.isEmpty()) return ShortArray(0)
        // 77-byte packet → 11 packed-pairs → 22 codec2 frames →
        // 22 * 320 = 7040 PCM samples (when packet is full). Partial
        // packets produce proportionally fewer samples.
        val pairs = payload.size / PACK_BYTES
        val totalSamples = pairs * 2 * CODEC2_FRAME_SAMPLES
        val out = ShortArray(totalSamples)
        var outIdx = 0
        var inIdx = 0
        while (inIdx + PACK_BYTES <= payload.size) {
            val (a, b) = unpackTwoCodec2Frames(payload, inIdx)
            inIdx += PACK_BYTES
            // First half of the pair.
            val frameOutA = decoder.readFrame(a).array()
            System.arraycopy(
                shortBufferFromBytes(frameOutA), 0,
                out, outIdx, CODEC2_FRAME_SAMPLES,
            )
            outIdx += CODEC2_FRAME_SAMPLES
            // Second half of the pair.
            val frameOutB = decoder.readFrame(b).array()
            System.arraycopy(
                shortBufferFromBytes(frameOutB), 0,
                out, outIdx, CODEC2_FRAME_SAMPLES,
            )
            outIdx += CODEC2_FRAME_SAMPLES
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    override fun close() {
        runCatching { decoder.destroy() }
    }

    // ── internals ────────────────────────────────────────────────────

    /**
     * Inverse of [Codec2EncoderSession.packTwoCodec2Frames]:
     * unpack one 7-byte chunk in [src] starting at [offset] into two
     * 4-byte codec2 frames.
     */
    private fun unpackTwoCodec2Frames(src: ByteArray, offset: Int): Pair<ByteArray, ByteArray> {
        val a = ByteArray(CODEC2_FRAME_BYTES)
        val b = ByteArray(CODEC2_FRAME_BYTES)
        a[0] = src[offset]
        a[1] = src[offset + 1]
        a[2] = src[offset + 2]
        // a[3] = high nibble of byte 3 (top 4 bits). Low nibble is b's tail.
        a[3] = (src[offset + 3].toInt() and 0xF0).toByte()
        // b[0] = (low nibble of byte 3, high nibble of byte 4)
        b[0] = (((src[offset + 3].toInt() and 0x0F) shl 4) or
                ((src[offset + 4].toInt() and 0xFF) ushr 4)).toByte()
        b[1] = (((src[offset + 4].toInt() and 0x0F) shl 4) or
                ((src[offset + 5].toInt() and 0xFF) ushr 4)).toByte()
        b[2] = (((src[offset + 5].toInt() and 0x0F) shl 4) or
                ((src[offset + 6].toInt() and 0xFF) ushr 4)).toByte()
        // b[3] is the bottom nibble of byte 6 shifted up — original encoder
        // discards it (it was zero padding), so we leave it zero.
        b[3] = ((src[offset + 6].toInt() and 0x0F) shl 4).toByte()
        return a to b
    }

    /**
     * `Codec2Decoder.readFrame` returns a ByteBuffer holding 16-bit
     * little-endian PCM. Convert to ShortArray for downstream use.
     */
    private fun shortBufferFromBytes(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        var i = 0
        var j = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            out[j] = ((hi shl 8) or lo).toShort()
            i += 2
            j++
        }
        return out
    }

    companion object {
        private const val CODEC2_FRAME_SAMPLES = 320
        private const val CODEC2_FRAME_BYTES = 4

        /** Bytes per packed-pair (two 4-byte frames packed to 7 bytes). */
        private const val PACK_BYTES = 7
    }
}

