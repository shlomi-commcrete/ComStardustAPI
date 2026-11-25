package com.commcrete.pign

/**
 * Kotlin access to PigN, Commcrete's file-compression library (native code in libpign_jni.so).
 *
 * Sender:   val packed = PigN.compress(fileBytes, "docx")
 *           send packed.data, and packed.method as one byte
 * Receiver: val file = PigN.decompress(receivedBytes, methodByte.toInt() and 0xFF)
 *
 * Both calls can take a while for large files: call them off the main thread,
 * for example in withContext(Dispatchers.Default).
 */
object PigN {
    const val METHOD_GZIP   = 0
    const val METHOD_NONE   = 1
    const val METHOD_ZIP_V1 = 2
    const val METHOD_ZSTD   = 3
    /** Lossless JPEG recompression; exists only in native builds made with STARDUST_JXL_PREFIX. */
    const val METHOD_JPEG_JXL = 4

    const val OK          =  0
    const val ERR_ARG     = -1
    const val ERR_NOMEM   = -2
    const val ERR_METHOD  = -3
    const val ERR_CORRUPT = -4
    const val ERR_LIMIT   = -5

    init {
        System.loadLibrary("pign_jni")
    }

    /**
     * Compresses [data] with the best method for its type. The result is never larger than the input.
     *
     * @param fileExt     extension without the dot ("pdf", "docx"), or null if unknown
     * @param level       1 (fastest) .. 22 (smallest); 0 = default (19)
     * @param forceMethod -1 = let PigN choose; or a METHOD_* constant to force a specific method
     */
    @JvmStatic
    @JvmOverloads
    fun compress(data: ByteArray, fileExt: String?, level: Int = 0, forceMethod: Int = -1): PigNPacked {
        val status = IntArray(2)
        val out = nativeCompress(data, fileExt, level, forceMethod, status)
        if (status[0] != OK || out == null) throw PigNException(status[0])
        return PigNPacked(out, status[1])
    }

    /**
     * Restores the original file from the bytes and method ID that were sent.
     *
     * @param method the method ID sent with the data (for a signed Byte: `b.toInt() and 0xFF`)
     * @throws PigNException ERR_CORRUPT for damaged data, ERR_METHOD if the sender has a newer PigN
     */
    @JvmStatic
    fun decompress(data: ByteArray, method: Int): ByteArray {
        val status = IntArray(2)
        val out = nativeDecompress(data, method, status)
        if (status[0] != OK || out == null) throw PigNException(status[0])
        return out
    }

    @JvmStatic private external fun nativeCompress(
        data: ByteArray, fileExt: String?, level: Int, forceMethod: Int, status: IntArray
    ): ByteArray?

    @JvmStatic private external fun nativeDecompress(data: ByteArray, method: Int, status: IntArray): ByteArray?
}

/** What the sender transmits: [data] bytes and [method] ID (one byte on the wire). */
class PigNPacked(val data: ByteArray, val method: Int)

/** Thrown by [PigN.compress] / [PigN.decompress] on failure. [code] is one of [PigN].ERR_*. */
class PigNException(val code: Int) : Exception("PigN error $code: ${describe(code)}") {
    companion object {
        fun describe(code: Int): String = when (code) {
            PigN.ERR_ARG     -> "invalid argument or option"
            PigN.ERR_NOMEM   -> "out of memory"
            PigN.ERR_METHOD  -> "unknown method (the sender has a newer PigN), or forced method can't handle this file"
            PigN.ERR_CORRUPT -> "the compressed data is damaged or incomplete"
            PigN.ERR_LIMIT   -> "the file is too large"
            else             -> "unknown error ($code)"
        }
    }
}
