package com.commcrete.stardust.crypto

import android.content.Context
import com.commcrete.stardust.BuildConfig
import com.commcrete.stardust.util.SharedPreferencesUtil

object SecureKeyUtils {

    val Key = BuildConfig.Crypt

    fun getSecuredKey(context: Context): ByteArray {
        val store = SecureKeyStore(context)
        val currentBytes: ByteArray = store.getKey()

        // Check if key is empty / still all zeros
        val isDefault = currentBytes.all { it == 0.toByte() }

        return if (isDefault) {
            // Return the default build-time key
            hexStringToByteArray(Key)
        } else {
            currentBytes
        }
    }

    // Helper if you don't already have one
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            result[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return result
    }

    fun setSecuredKey (context: Context, key : String, name : String) {
        val store = SecureKeyStore(context)
        store.clear()
        store.setKeyFromHex(key)
        SharedPreferencesUtil.setKeyNameCrypto(context, name)
    }


    fun setSecuredKeyDefault (context: Context) : Boolean{
        val store = SecureKeyStore(context)
        store.clear()
        store.setKeyFromHex(Key)
        SharedPreferencesUtil.setKeyNameCrypto(context, "Default")
        return true
    }
}