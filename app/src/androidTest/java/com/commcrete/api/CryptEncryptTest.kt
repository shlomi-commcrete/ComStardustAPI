package com.commcrete.api

import com.commcrete.stardust.crypto.Crypt
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptEncryptTest {

    @Test
    fun encrypt_knownVector_noPadding_zeroIV_repeated() {
        val keyHex = "0100000000000000000000000000000000000000000000000000000000000000"
        val key = keyHex.hexToByteArray()
        val mutableList = mutableListOf(24, 128,12,  64,  35,   0,   0,  16,   1,  21,7,  58, 121, 165, 179, 161, 170,  97, 46)
        val byteArray: ByteArray = mutableList.map { it.toByte() }.toByteArray()

        val plainHex =
            "20005d80230000100c180923000010000000000102"
        val plain = byteArray

        val expected =
            "e24289cf5eb667ad015b16f5b786d930610669b42a26be8dbaacd58dc617d71a5ca0d6a81debaeb483be80ded0587337ee56e6e2961ddcb14ca462e3ed842ddcf85737e00a8e4059cc7401f4fbeac4e83a540785522306373e1f96c0efe3c54b"

        val crypt = Crypt(
            paddingNone = true,          // NoPadding
            enforceLegacy255Limit = false
        )

        repeat(100000) { i ->
            val start = System.currentTimeMillis()
            val out = crypt.encryptBuf(key, plain)
            var text = ""
            for(input in out){
                text +="${input.toUInt()}\n"
            }
            val end = System.currentTimeMillis()
            val duration = end - start

            val hex = TestUtils.bytesToHex(out)

            println("Run ${i + 1}: Encryption took $duration ms")

            assertEquals(expected, hex)
        }
    }
}

fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
