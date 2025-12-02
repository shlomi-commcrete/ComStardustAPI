package com.commcrete.stardust.crypto

import android.content.Context
import com.commcrete.stardust.BuildConfig
import com.commcrete.stardust.util.DataManager

object CryptoUtils {

    fun encryptData (context: Context, value : ByteArray) : ByteArray {
        val crypt = Crypt(
            paddingNone = true,
            enforceLegacy255Limit = false
        )

        val cryptBytes = SecureKeyUtils.getSecuredKey(context)
        val out = crypt.encryptBuf(cryptBytes, value)
        return out
    }

    fun decryptData (context: Context, value : ByteArray) : ByteArray {
        val crypt = Crypt(
            paddingNone = true,
            enforceLegacy255Limit = false
        )

        val cryptBytes = SecureKeyUtils.getSecuredKey(context)
        val out = crypt.decryptBuf(cryptBytes, value)
        return out
    }
}

fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
