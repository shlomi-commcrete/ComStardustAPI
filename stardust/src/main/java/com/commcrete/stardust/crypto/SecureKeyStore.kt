package com.commcrete.stardust.crypto


import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.commcrete.stardust.util.DataManager

class SecureKeyStore() {


    // 1.0.0 API: MasterKeys
    private val masterKeyAlias: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    }

    // 1.0.0 API: create(fileName, masterKeyAlias, context, keyScheme, valueScheme)
    private fun createPrefs() =
        EncryptedSharedPreferences.create(
            PREFS_NAME,                                // fileName
            masterKeyAlias,                            // masterKeyAlias
            DataManager.appContext,                                // <-- must be a real Android context
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { createPrefs() }

    @Synchronized
    fun getKey(): ByteArray {
        val b64 = runCatching { prefs.getString(KEY_NAME, null) }
            .getOrElse {
                if (it is SecurityException) {
                    // Existing encrypted value/keyset is corrupted or not decryptable on this install.
                    resetEncryptedPrefsStorage()
                    return@getOrElse null
                }
                throw it
            }
        if (b64.isNullOrEmpty()) {
            saveBytes(DEFAULT_KEY)
            return DEFAULT_KEY.copyOf()
        }
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    fun getKeyHex(): String = bytesToHex(getKey())

    @Synchronized
    fun setKey(bytes: ByteArray) {
        require(bytes.size == 32) { "Key must be exactly 32 bytes." }
        saveBytes(bytes)
    }

    fun setKeyFromHex(hex: String) = setKey(hexToBytes(hex))

    @Synchronized
    fun rotateRandomKey(): ByteArray {
        val rnd = java.security.SecureRandom()
        val b = ByteArray(32)
        rnd.nextBytes(b)
        saveBytes(b)
        return b
    }

    @Synchronized
    fun clear() {
        runCatching { prefs.edit().remove(KEY_NAME).apply() }
            .onFailure {
                if (it is SecurityException) {
                    resetEncryptedPrefsStorage()
                } else {
                    throw it
                }
            }
    }

    @Synchronized
    private fun saveBytes(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val writeOk = runCatching {
            prefs.edit().putString(KEY_NAME, b64).commit()
        }.getOrElse {
            if (it is SecurityException) {
                resetEncryptedPrefsStorage()
                false
            } else {
                throw it
            }
        }

        if (!writeOk) {
            // Retry once with freshly-created encrypted storage.
            createPrefs().edit().putString(KEY_NAME, b64).commit()
        }
    }

    private fun resetEncryptedPrefsStorage() {
        safeDeleteSharedPrefs(PREFS_NAME)
        safeDeleteSharedPrefs("${PREFS_NAME}$KEY_KEYSET_SUFFIX")
        safeDeleteSharedPrefs("${PREFS_NAME}$VALUE_KEYSET_SUFFIX")
    }

    private fun safeDeleteSharedPrefs(name: String) {
        val context = DataManager.appContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(name)
            return
        }
        // API < 24 fallback.
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * 2)
        val map = "0123456789abcdef".toCharArray()
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            hex[i++] = map[v ushr 4]
            hex[i++] = map[v and 0x0F]
        }
        return String(hex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 64) { "Hex must be exactly 64 characters (32 bytes)." }
        fun parse(c: Char) = c.digitToIntOrNull(16)
            ?: throw IllegalArgumentException("Invalid hex char: '$c'")
        val out = ByteArray(32)
        var i = 0
        while (i < 64) {
            val hi = parse(hex[i]) shl 4
            val lo = parse(hex[i + 1])
            out[i / 2] = (hi or lo).toByte()
            i += 2
        }
        return out
    }

    companion object {
        private const val PREFS_NAME = "secure_prefs"
        private const val KEY_NAME = "app_secret_key"
        private const val KEY_KEYSET_SUFFIX = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        private const val VALUE_KEYSET_SUFFIX = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
        private val DEFAULT_KEY = ByteArray(32) { 0x00.toByte() }
    }
}