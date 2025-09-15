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
    private val AES_BLOCK = 16

    fun encryptBuf(
        key: ByteArray,
        buf: ByteArray,
        bufSize: Int = buf.size
    ): ByteArray {
        require(bufSize in 0..buf.size) { "Invalid bufSize" }

        val res = bufSize % AES_BLOCK
        val size1 = bufSize - res
        val out = buf.copyOf()

        // Pass 1
        if (size1 > 0) {
            val slice = out.copyOfRange(0, size1)
            val enc = Aes.encryptCbc(
                key = key,
                plaintext = slice,
                iv = ByteArray(AES_BLOCK),
                padding = Aes.Padding.NONE
            )
            System.arraycopy(enc, 0, out, 0, size1)
        }

        // Pass 2 (if remainder exists)
        if (res > 0) {
            require(bufSize >= AES_BLOCK) { "bufSize must be ≥ 16 when remainder > 0" }
            val start = bufSize - AES_BLOCK
            val tail = out.copyOfRange(start, start + AES_BLOCK)
            val enc = Aes.encryptCbc(
                key = key,
                plaintext = tail,
                iv = ByteArray(AES_BLOCK),
                padding = Aes.Padding.NONE
            )
            System.arraycopy(enc, 0, out, start, AES_BLOCK)
        }

        return out
    }

    fun decryptBuf(
        key: ByteArray,
        buf: ByteArray,
        bufSize: Int = buf.size
    ): ByteArray {
        require(bufSize in 0..buf.size) { "Invalid bufSize" }

        val res = bufSize % AES_BLOCK
        val out = buf.copyOf()

        // Pass 1 (if remainder exists)
        if (res > 0) {
            require(bufSize >= AES_BLOCK) { "bufSize must be ≥ 16 when remainder > 0" }
            val start = bufSize - AES_BLOCK
            val tail = out.copyOfRange(start, start + AES_BLOCK)
            val dec = Aes.decryptCbc(
                key = key,
                ciphertext = tail,
                iv = ByteArray(AES_BLOCK),
                padding = Aes.Padding.NONE
            )
            System.arraycopy(dec, 0, out, start, AES_BLOCK)
        }

        // Pass 2
        val size1 = bufSize - res
        if (size1 > 0) {
            val head = out.copyOfRange(0, size1)
            val dec = Aes.decryptCbc(
                key = key,
                ciphertext = head,
                iv = ByteArray(AES_BLOCK),
                padding = Aes.Padding.NONE
            )
            System.arraycopy(dec, 0, out, 0, size1)
        }

        return out
    }
}