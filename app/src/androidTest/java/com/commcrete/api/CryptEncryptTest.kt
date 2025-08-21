package com.commcrete.api

import com.commcrete.stardust.crypto.Crypt
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptEncryptTest {

    @Test
    fun encrypt_knownVector_noPadding_zeroIV_repeated() {
        val keyHex = "D8F83FF7141551756201271E54B979235EA726E69B85C3AEA83233264CCD90D5"
        val key = keyHex.hexToByteArray()
        val plainHex =
            "00100020b1550308053d03080f3d0308193d0308233d03082d3d030800000000000000000000000000000000e9550308373d030800000000450d0308590c0308ed550308f1550308f5550308f9550308fd550308015603080556030809560308"
        val plain = plainHex.hexToByteArray()

        val expected =
            "e24289cf5eb667ad015b16f5b786d930610669b42a26be8dbaacd58dc617d71a5ca0d6a81debaeb483be80ded0587337ee56e6e2961ddcb14ca462e3ed842ddcf85737e00a8e4059cc7401f4fbeac4e83a540785522306373e1f96c0efe3c54b"

        val crypt = Crypt(
            paddingNone = true,          // NoPadding
            enforceLegacy255Limit = false
        )

        repeat(100000) { i ->
            val start = System.currentTimeMillis()
            val out = crypt.encryptBuf(key, plain)
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
