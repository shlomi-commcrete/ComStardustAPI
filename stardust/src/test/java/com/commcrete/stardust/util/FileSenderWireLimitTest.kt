package com.commcrete.stardust.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size limit is a package count — [FileSender.MAX_TOTAL_PACKAGES], data plus parity — so
 * these pin the boundary at each resilience factor, one byte either side of it.
 */
class FileSenderWireLimitTest {

    private val factors = Resilience.values().map { it.value }

    @Test
    fun largestPayloadFitsAndOneMoreByteDoesNot() {
        for (factor in factors) {
            val max = FileSender.maxPayloadBytes(factor)
            assertTrue("factor $factor: max $max should fit", FileSender.fitsOnWire(max, factor))
            // One byte more starts a new 60-byte package, which is what tips it over.
            assertFalse("factor $factor: ${max + 1} should not fit", FileSender.fitsOnWire(max + 1, factor))
        }
    }

    @Test
    fun maxIsAWholeNumberOfPackages() {
        for (factor in factors) {
            assertEquals(0L, FileSender.maxPayloadBytes(factor) % 60)
        }
    }

    @Test
    fun higherResilienceLeavesLessRoomForData() {
        val low = FileSender.maxPayloadBytes(Resilience.Low.value)
        val high = FileSender.maxPayloadBytes(Resilience.High.value)
        assertTrue("$high should be below $low", high < low)
    }

    @Test
    fun limitIsAboutThreeAndAHalfMegabytes() {
        for (factor in factors) {
            val max = FileSender.maxPayloadBytes(factor)
            assertTrue("factor $factor: $max", max in 3_500_000L..3_600_000L)
        }
    }

    @Test
    fun hugePayloadDoesNotOverflow() {
        assertFalse(FileSender.fitsOnWire(5_000_000_000L, Resilience.Low.value))
    }

    @Test
    fun emptyPayloadFits() {
        assertTrue(FileSender.fitsOnWire(0L, Resilience.Low.value))
    }
}
