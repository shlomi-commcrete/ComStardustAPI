package com.commcrete.stardust.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [ReedSolomon.canRecover] is what both ends of a file transfer settle an early-ended transfer
 * by — the sender on a lost link, the receiver on its gap timeout or a lost link. It must say yes
 * exactly when [ReedSolomon.decode] would succeed, and that is a per-block question once the
 * codeword is longer than 255 packets.
 */
class ReedSolomonRecoverabilityTest {

    @Test
    fun `one block survives losing up to its parity, anywhere`() {
        val rs = ReedSolomon(totalDataPackets = 10, totalParityPackets = 4)

        assertTrue(rs.canRecover(emptySet()))
        assertTrue(rs.canRecover(setOf(0, 3, 7, 13)))
        assertFalse(rs.canRecover(setOf(0, 3, 7, 12, 13)))
    }

    @Test
    fun `no parity survives no loss`() {
        val rs = ReedSolomon(totalDataPackets = 10, totalParityPackets = 0)

        assertTrue(rs.canRecover(emptySet()))
        assertFalse(rs.canRecover(setOf(9)))
    }

    /**
     * The case a total count gets wrong: 400 + 40 splits into two blocks of 200 + 20, so 21
     * losses inside one block are fatal although they are well under the 40 parity overall.
     */
    @Test
    fun `losses are counted per block, not overall`() {
        val rs = ReedSolomon(totalDataPackets = 400, totalParityPackets = 40)

        assertFalse("21 lost in the first block", rs.canRecover((0 until 21).toSet()))
        assertTrue("20 lost in each block", rs.canRecover((0 until 20).toSet() + (220 until 240).toSet()))
        assertEquals(20, rs.minBlockParity())
    }

    @Test
    fun `a tail up to the smallest block's parity always decodes`() {
        val rs = ReedSolomon(totalDataPackets = 401, totalParityPackets = 41)
        val total = 401 + 41
        val tail = rs.minBlockParity()

        assertEquals(20, tail)
        assertTrue(rs.canRecover((total - tail until total).toSet()))
        // The last block (200 + 20) cannot lose a 21st package.
        assertFalse(rs.canRecover((total - tail - 1 until total).toSet()))
    }

    /** Agreement with the decoder itself, not just with the arithmetic: a "yes" must decode. */
    @Test
    fun `a codeword missing as many packets as it has parity decodes to the original data`() {
        val k = 30
        val p = 6
        val random = Random(7)
        val data = List(k) { ByteArray(60).also(random::nextBytes) }
        val rs = ReedSolomon(totalDataPackets = k, totalParityPackets = p)
        val codeword = rs.encode(data)

        // Data packets among them, and the never-arrived tail a receiver's timeout sees.
        val missing = setOf(0, 7, 18, 29, 34, 35)
        assertTrue(rs.canRecover(missing))
        val received = codeword.mapIndexed { i, packet -> if (i in missing) null else packet }
        val decoded = ReedSolomon(k, p).decode(received, missing.toIntArray())

        data.forEachIndexed { i, packet -> assertArrayEquals(packet, decoded[i]) }
    }
}
