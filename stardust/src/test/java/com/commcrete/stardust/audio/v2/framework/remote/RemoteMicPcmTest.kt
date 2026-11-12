package com.commcrete.stardust.audio.v2.framework.remote

import com.commcrete.stardust.PttRecordingError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteMicPcmTest {

    private fun le(vararg samples: Short): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }

    private fun LePcmDecoder.decodeAll(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2 + 1)
        val n = decode(bytes, bytes.size, out)
        return out.copyOf(n)
    }

    @Test
    fun `whole samples decode as written, signs included`() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234, -4321)
        assertArrayEquals(samples, LePcmDecoder().decodeAll(le(*samples)))
    }

    @Test
    fun `a sample split across two reads is joined`() {
        val samples = shortArrayOf(1000, -2000, 3000)
        val bytes = le(*samples)
        val decoder = LePcmDecoder()
        val first = decoder.decodeAll(bytes.copyOfRange(0, 3)) // one sample and half of the next
        val second = decoder.decodeAll(bytes.copyOfRange(3, bytes.size))
        assertArrayEquals(samples, first + second)
    }

    @Test
    fun `byte by byte gives the same samples`() {
        val samples = shortArrayOf(-1, 256, -32768, 7)
        val decoder = LePcmDecoder()
        val out = le(*samples).fold(ShortArray(0)) { acc, b -> acc + decoder.decodeAll(byteArrayOf(b)) }
        assertArrayEquals(samples, out)
    }

    @Test
    fun `an empty read carries nothing over`() {
        val decoder = LePcmDecoder()
        assertEquals(0, decoder.decodeAll(ByteArray(0)).size)
        assertArrayEquals(shortArrayOf(42), decoder.decodeAll(le(42)))
    }

    @Test
    fun `mute codes round-trip to the same error`() {
        listOf(
            PttRecordingError.MIC_TAKEN_BY_CALL,
            PttRecordingError.MIC_ACCESS_BLOCKED,
            PttRecordingError.MIC_MUTED_IN_BACKGROUND,
        ).forEach { error ->
            val code = RemoteMicFailure.toCode(error)
            assertTrue(RemoteMicFailure.isMute(code))
            assertEquals(error, RemoteMicFailure.toError(code))
        }
    }

    @Test
    fun `anything that is not a mute reads as an unavailable microphone`() {
        listOf(RemoteMicFailure.MIC_UNAVAILABLE, RemoteMicFailure.CALLER_REJECTED, RemoteMicFailure.NO_PERMISSION, 999)
            .forEach { code ->
                assertFalse(RemoteMicFailure.isMute(code))
                assertEquals(PttRecordingError.MIC_UNAVAILABLE, RemoteMicFailure.toError(code))
            }
        assertFalse(RemoteMicFailure.isMute(RemoteMicFailure.NONE))
    }
}
