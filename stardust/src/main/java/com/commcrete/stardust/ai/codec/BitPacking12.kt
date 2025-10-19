package com.commcrete.aiaudio.codecs

/**
 * Utilities to pack (compress) and unpack a list of 12‑bit unsigned values into / from a minimal ByteArray.
 *
 * Representation chosen:
 *  - Values are written sequentially, least–significant bits first (little‑endian at the bit level).
 *  - Every value contributes exactly 12 bits.
 *  - The resulting byte array length is ceil(n * 12 / 8).
 *  - If the final 12-bit group does not align to a byte boundary, the remaining (high) unused bits
 *    of the last byte are left as zeros.
 *
 * You MUST know (or store separately) how many 12-bit values were packed in order to correctly unpack.
 *
 * Range:
 *  - Each input value must be in 0..0xFFF (0..4095). Larger / negative values throw an exception.
 *
 * Complexity:
 *  - Time: O(n)
 *  - Space: O(1) extra (aside from output)
 */
object BitPacking12 {

    /**
     * Packs a list of Long values (each expected 0..4095) into a compact ByteArray using 12 bits per value.
     */
    fun pack12(values: List<Long>): ByteArray {
        val outSize = (values.size * 12 + 7) / 8  // ceil(bits / 8)
        val out = ByteArray(outSize)

        var bitBuffer = 0          // Accumulates bits not yet flushed to the output
        var bitCount = 0           // Number of valid bits currently in bitBuffer
        var byteIndex = 0

        for (vLong in values) {
            val v = vLong.toInt()
            require(v in 0..0xFFF) { "Value $vLong out of 12-bit range (0..4095)" }

            // Insert the 12 bits at the current free position (little-endian within the bit stream)
            bitBuffer = bitBuffer or (v shl bitCount)
            bitCount += 12

            // While we have at least a full byte, flush bytes
            while (bitCount >= 8) {
                out[byteIndex++] = (bitBuffer and 0xFF).toByte()
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        // Flush any remaining partial byte
        if (bitCount > 0) {
            out[byteIndex] = (bitBuffer and 0xFF).toByte()
        }

        return out
    }

    /**
     * Unpacks exactly [count] 12-bit values from [bytes] that were produced by [pack12].
     * @throws IllegalArgumentException if there are not enough bytes to reconstruct the requested count.
     */
    fun unpack12(bytes: ByteArray): List<Long> {
        val count = bytes.size * 2 / 3
        val result = ArrayList<Long>(count)

        var bitBuffer = 0
        var bitCount = 0
        var byteIndex = 0

        repeat(count) {
            // Ensure we have at least 12 bits available
            while (bitCount < 12) {
                if (byteIndex >= bytes.size) {
                    throw IllegalArgumentException("Not enough bytes to decode $count values (stopped at ${result.size})")
                }
                val b = bytes[byteIndex++].toInt() and 0xFF
                bitBuffer = bitBuffer or (b shl bitCount)
                bitCount += 8
            }

            val value = bitBuffer and 0xFFF
            result.add(value.toLong())

            bitBuffer = bitBuffer ushr 12
            bitCount -= 12
        }

        return result
    }

    /**
     * Convenience: packs values and prefixes the count as a 4-byte (Int, little-endian) header so the
     * result is self-describing. Use [unpackWithCount] to decode.
     */
//    fun packWithCount(values: List<Long>): ByteArray {
//        val body = pack12(values)
//        val out = ByteArray(4 + body.size)
//        val n = values.size
//        out[0] = (n and 0xFF).toByte()
//        out[1] = ((n ushr 8) and 0xFF).toByte()
//        out[2] = ((n ushr 16) and 0xFF).toByte()
//        out[3] = ((n ushr 24) and 0xFF).toByte()
//        System.arraycopy(body, 0, out, 4, body.size)
//        return out
//    }
//
//    /**
//     * Decodes an array produced by [packWithCount].
//     */
//    fun unpackWithCount(bytes: ByteArray): List<Long> {
//        require(bytes.size >= 4) { "Byte array too short to contain count header" }
//        val count = (bytes[0].toInt() and 0xFF) or
//                ((bytes[1].toInt() and 0xFF) shl 8) or
//                ((bytes[2].toInt() and 0xFF) shl 16) or
//                ((bytes[3].toInt() and 0xFF) shl 24)
//        val body = bytes.copyOfRange(4, bytes.size)
//        return unpack12(body, count)
//    }
}