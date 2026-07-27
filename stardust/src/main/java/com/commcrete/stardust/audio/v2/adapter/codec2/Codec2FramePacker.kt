package com.commcrete.stardust.audio.v2.adapter.codec2

/**
 * Adapter ring — the CODEC2 700C wire bit-packing, ported byte-for-byte from the legacy
 * `Codec2SendPipeline.packTwoCodec2Frames` (pack) and `PlayerUtils.splitByteArray2` (unpack) so v2
 * stays bit-compatible with legacy peers on the air.
 *
 * A 700C frame is 28 bits packed into 4 bytes (the low nibble of byte 3 is unused). Two frames pack
 * into a 7-byte (56-bit) chunk; eleven 7-byte chunks make a 77-byte BLE payload (= 22 frames = 880 ms).
 * Pure functions, no state — safe to share.
 */
object Codec2FramePacker {

    /** 700C operates on 320-sample (40 ms @ 8 kHz) frames. */
    const val FRAME_SAMPLES = 320

    /** One 700C frame is 28 bits carried in 4 bytes. */
    const val FRAME_BYTES = 4

    /** BLE payload size: eleven 7-byte chunks. */
    const val PACKET_BYTES = 77

    /** All-zero frame — emitted as padding and skipped on decode (matches legacy `embpyByte`). */
    val EMPTY_FRAME = ByteArray(FRAME_BYTES)

    /** Pack two 4-byte frames into a 7-byte chunk (frame A's tail nibble preserved, B shifted in). */
    fun packTwo(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(7)
        out[0] = a[0]
        out[1] = a[1]
        out[2] = a[2]
        out[3] = ((a[3].toInt() and 0xF0) or ((b[0].toInt() and 0xFF) ushr 4)).toByte()
        out[4] = (((b[0].toInt() and 0xFF) shl 4) or ((b[1].toInt() and 0xFF) ushr 4)).toByte()
        out[5] = (((b[1].toInt() and 0xFF) shl 4) or ((b[2].toInt() and 0xFF) ushr 4)).toByte()
        out[6] = (((b[2].toInt() and 0xFF) shl 4) or ((b[3].toInt() and 0xFF) ushr 4)).toByte()
        return out
    }

    /** Split a payload (a multiple of 7 bytes) back into 4-byte 700C frames. A trailing partial chunk is ignored. */
    fun toFrames(payload: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>((payload.size / 7) * 2)
        var i = 0
        while (i + 7 <= payload.size) {
            unpackSevenInto(payload, i, frames)
            i += 7
        }
        return frames
    }

    private fun unpackSevenInto(src: ByteArray, off: Int, out: MutableList<ByteArray>) {
        val a = ByteArray(4)
        val b = ByteArray(4)
        a[0] = src[off]
        a[1] = src[off + 1]
        a[2] = src[off + 2]
        a[3] = (src[off + 3].toInt() and 0xF0).toByte()
        b[0] = ((src[off + 3].toUByte().toInt() shl 4) or (src[off + 4].toUByte().toInt() shr 4)).toByte()
        b[1] = ((src[off + 4].toUByte().toInt() shl 4) or (src[off + 5].toUByte().toInt() shr 4)).toByte()
        b[2] = ((src[off + 5].toUByte().toInt() shl 4) or (src[off + 6].toUByte().toInt() shr 4)).toByte()
        b[3] = (src[off + 6].toUByte().toInt() shl 4).toByte()
        out.add(a)
        out.add(b)
    }
}
