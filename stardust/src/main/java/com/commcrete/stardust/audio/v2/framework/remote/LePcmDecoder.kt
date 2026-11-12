package com.commcrete.stardust.audio.v2.framework.remote

/**
 * 16-bit little-endian PCM bytes → samples, for a stream that may split a sample across two reads (a pipe
 * returns whatever is buffered). The odd byte is carried into the next [decode].
 */
internal class LePcmDecoder {
    private var carry = -1

    /** Decodes [count] bytes of [src] into [dst] (room for (count + 1) / 2 samples); returns the samples written. */
    fun decode(src: ByteArray, count: Int, dst: ShortArray): Int {
        var i = 0
        var out = 0
        if (carry >= 0 && count > 0) {
            dst[out++] = ((src[0].toInt() shl 8) or carry).toShort()
            carry = -1
            i = 1
        }
        while (i + 1 < count) {
            dst[out++] = ((src[i + 1].toInt() shl 8) or (src[i].toInt() and 0xFF)).toShort()
            i += 2
        }
        if (i < count) carry = src[i].toInt() and 0xFF
        return out
    }
}
