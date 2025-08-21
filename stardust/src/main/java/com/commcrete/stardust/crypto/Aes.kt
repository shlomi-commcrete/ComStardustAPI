package com.commcrete.stardust.crypto
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES helpers. Defaults:
 *  - Mode: CBC
 *  - Padding: PKCS5 (aka PKCS7 for 16-byte blocks)
 *  - IV: zero (16 bytes) unless provided
 */
object Aes {

    enum class Padding(val jceName: String) {
        NONE("NoPadding"),
        PKCS7("PKCS5Padding") // JCE name for PKCS7 on 16-byte blocks
    }

    private const val AES_BLOCK = 16

    fun encryptCbc(
        key: ByteArray,                 // 16/24/32 bytes for AES-128/192/256 (256 needs OS support)
        plaintext: ByteArray,
        iv: ByteArray = ByteArray(AES_BLOCK),  // zero IV default (matches your legacy)
        padding: Padding = Padding.PKCS7
    ): ByteArray {
        require(iv.size == AES_BLOCK) { "IV must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/CBC/${padding.jceName}")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        if (padding == Padding.NONE && plaintext.size % AES_BLOCK != 0) {
            throw IllegalArgumentException("NoPadding requires input multiple of 16 bytes")
        }
        return cipher.doFinal(plaintext)
    }

    fun decryptCbc(
        key: ByteArray,
        ciphertext: ByteArray,
        iv: ByteArray = ByteArray(AES_BLOCK),
        padding: Padding = Padding.PKCS7
    ): ByteArray {
        require(iv.size == AES_BLOCK) { "IV must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/CBC/${padding.jceName}")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        if (padding == Padding.NONE && ciphertext.size % AES_BLOCK != 0) {
            throw IllegalArgumentException("NoPadding requires input multiple of 16 bytes")
        }
        return cipher.doFinal(ciphertext)
    }
}