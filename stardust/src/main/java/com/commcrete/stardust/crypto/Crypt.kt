package com.commcrete.stardust.crypto
/**
 * Kotlin version of your C++ CCRYPT class.
 * By default:
 *  - AES-CBC with zero IV (16 zero bytes), to mirror your legacy
 *  - PKCS7 padding (safe default). Set paddingNone=true to mirror "no padding" behavior.
 *  - No 255-byte limit unless you enable it.
 */
class Crypt(
    private val paddingNone: Boolean = false, // true to mimic "no padding" (requires len % 16 == 0)
    private val enforceLegacy255Limit: Boolean = false // true to keep old uint8_t BufSize behavior
) {
    private val zeroIv = ByteArray(16)

    /**
     * In-place style (to match your signature). Kotlin can’t modify the original array’s length,
     * so we return the result. If you need strict in-place semantics, pass a buffer sized for output.
     */
    fun encryptBuf(key: ByteArray, buf: ByteArray, bufSize: Int = buf.size): ByteArray {
        require(bufSize >= 0 && bufSize <= buf.size) { "Invalid bufSize" }
        if (enforceLegacy255Limit && bufSize > 255) {
            throw IllegalArgumentException("BufSize > 255 (legacy limit).")
        }
        val inSlice = buf.copyOfRange(0, bufSize)
        return if (paddingNone) {
            Aes.encryptCbc(key, inSlice, zeroIv, Aes.Padding.NONE)
        } else {
            Aes.encryptCbc(key, inSlice, zeroIv, Aes.Padding.PKCS7)
        }
    }

    fun decryptBuf(key: ByteArray, buf: ByteArray, bufSize: Int = buf.size): ByteArray {
        require(bufSize >= 0 && bufSize <= buf.size) { "Invalid bufSize" }
        if (enforceLegacy255Limit && bufSize > 255) {
            throw IllegalArgumentException("BufSize > 255 (legacy limit).")
        }
        val inSlice = buf.copyOfRange(0, bufSize)
        return if (paddingNone) {
            Aes.decryptCbc(key, inSlice, zeroIv, Aes.Padding.NONE)
        } else {
            Aes.decryptCbc(key, inSlice, zeroIv, Aes.Padding.PKCS7)
        }
    }
}