package com.commcrete.stardust.util

/**
 * Sequential little-endian cursor over a [ByteArray].
 *
 * Replaces the repetitive `cutByteArray(...) ; offset += len` pattern in the packet parsers with
 * readable, self-advancing reads. All multi-byte integers/floats are read little-endian, matching
 * the Stardust wire format (and the C core's `read_uint32_le` / `read_float_le`).
 */
class ByteReader(private val bytes: ByteArray, var offset: Int = 0) {

    val remaining: Int get() = bytes.size - offset

    private fun require(n: Int) {
        if (offset + n > bytes.size) {
            throw IndexOutOfBoundsException("ByteReader: need $n bytes at offset $offset, have $remaining")
        }
    }

    fun skip(n: Int) {
        require(n)
        offset += n
    }

    /** Unsigned byte (0..255). */
    fun u8(): Int {
        require(1)
        return bytes[offset++].toInt() and 0xFF
    }

    /** Signed byte (-128..127), matching the C `(int8_t)` reads. */
    fun i8(): Int {
        require(1)
        return bytes[offset++].toInt()
    }

    /** Unsigned 32-bit little-endian. Returned as Long so the top bit stays positive. */
    fun u32le(): Long {
        require(4)
        val v = (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
        offset += 4
        return v
    }

    /** 32-bit IEEE-754 float, little-endian. */
    fun f32le(): Float = Float.fromBits(u32le().toInt())

    /** Copy [n] raw bytes and advance. */
    fun bytes(n: Int): ByteArray {
        require(n)
        val out = bytes.copyOfRange(offset, offset + n)
        offset += n
        return out
    }

    /** Lowercase hex of [n] bytes in reverse order (matches the parsers' `reversedArray().toHex()`). */
    fun hexReversed(n: Int): String {
        val slice = bytes(n)
        val sb = StringBuilder(n * 2)
        for (i in slice.indices.reversed()) sb.append("%02x".format(slice[i]))
        return sb.toString()
    }

    /** UTF-8 string decoded from [n] bytes. */
    fun utf8(n: Int): String = bytes(n).toString(Charsets.UTF_8)
}
