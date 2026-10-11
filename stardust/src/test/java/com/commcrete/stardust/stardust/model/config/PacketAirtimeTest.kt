package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.enums.FunctionalityType
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketAirtimeTest {

    /** `s_ms_per_pkt` in the C core's `sb_calculate_send_time.c`, rounded to the millisecond. */
    private val fileMeasured = listOf(
        Triple(Bandwidth.BW_7_81, 756L, 2840L),
        Triple(Bandwidth.BW_15_63, 403L, 1444L),
        Triple(Bandwidth.BW_20_83, 315L, 1096L),
        Triple(Bandwidth.BW_31_25, 226L, 747L),
        Triple(Bandwidth.BW_41_67, 182L, 573L),
        Triple(Bandwidth.BW_62_5, 138L, 399L),
    )

    private val allBandwidths = Bandwidth.entries + listOf(null)

    @Test
    fun `FILE HR and LR follow the measured table`() {
        for ((bandwidth, hr, lr) in fileMeasured) {
            assertEquals("HR at $bandwidth", hr, PacketAirtime.FILE.ms(CarrierType.HR, bandwidth))
            assertEquals("LR at $bandwidth", lr, PacketAirtime.FILE.ms(CarrierType.LR, bandwidth))
        }
    }

    @Test
    fun `FILE falls back to the type's default for an unmeasured or unknown bandwidth`() {
        for (bandwidth in listOf(Bandwidth.BW_10_42, Bandwidth.BW_125, null)) {
            assertEquals("HR at $bandwidth", 705L, PacketAirtime.FILE.ms(CarrierType.HR, bandwidth))
            assertEquals("LR at $bandwidth", 2800L, PacketAirtime.FILE.ms(CarrierType.LR, bandwidth))
        }
        assertEquals(705L, PacketAirtime.FILE.defaultMs(CarrierType.HR))
        assertEquals(2800L, PacketAirtime.FILE.defaultMs(CarrierType.LR))
    }

    @Test
    fun `FILE ST ignores the bandwidth`() {
        allBandwidths.forEach { assertEquals("ST at $it", 450L, PacketAirtime.FILE.ms(CarrierType.ST, it)) }
    }

    /**
     * Measured µs → ms for the table's 8/16/24/50/62.5 kHz rows; 10.42 = 20.83 × 2,
     * 31.25 = 15.63 / 2 and 125 = 62.5 / 2, derived from the µs before rounding.
     */
    private val text131 = listOf(
        Triple(Bandwidth.BW_7_81, 1279L, 5085L),
        Triple(Bandwidth.BW_10_42, 959L, 3813L),
        Triple(Bandwidth.BW_15_63, 639L, 2541L),
        Triple(Bandwidth.BW_20_83, 480L, 1906L),
        Triple(Bandwidth.BW_31_25, 320L, 1270L),
        Triple(Bandwidth.BW_41_67, 240L, 953L),
        Triple(Bandwidth.BW_62_5, 160L, 635L),
        Triple(Bandwidth.BW_125, 80L, 318L),
    )

    private val timeout200 = listOf(
        Triple(Bandwidth.BW_7_81, 1833L, 7297L),
        Triple(Bandwidth.BW_10_42, 1374L, 5472L),
        Triple(Bandwidth.BW_15_63, 916L, 3646L),
        Triple(Bandwidth.BW_20_83, 687L, 2736L),
        Triple(Bandwidth.BW_31_25, 458L, 1823L),
        Triple(Bandwidth.BW_41_67, 343L, 1368L),
        Triple(Bandwidth.BW_62_5, 229L, 912L),
        Triple(Bandwidth.BW_125, 114L, 456L),
    )

    @Test
    fun `every bandwidth has a TEXT and a TX_TIMEOUT row`() {
        assertEquals(Bandwidth.entries.toSet(), text131.map { it.first }.toSet())
        assertEquals(Bandwidth.entries.toSet(), timeout200.map { it.first }.toSet())
    }

    @Test
    fun `TEXT follows the 131-byte measurements`() {
        for ((bandwidth, hr, lr) in text131) {
            assertEquals("HR at $bandwidth", hr, PacketAirtime.TEXT.ms(CarrierType.HR, bandwidth))
            assertEquals("LR at $bandwidth", lr, PacketAirtime.TEXT.ms(CarrierType.LR, bandwidth))
        }
    }

    @Test
    fun `TEXT takes the slowest row when the bandwidth is unknown`() {
        assertEquals(1279L, PacketAirtime.TEXT.ms(CarrierType.HR, null))
        assertEquals(5085L, PacketAirtime.TEXT.ms(CarrierType.LR, null))
        allBandwidths.forEach { assertEquals("ST at $it", 4000L, PacketAirtime.TEXT.ms(CarrierType.ST, it)) }
    }

    @Test
    fun `TX_TIMEOUT follows the 200-byte measurements`() {
        for ((bandwidth, hr, lr) in timeout200) {
            assertEquals("HR at $bandwidth", hr, PacketAirtime.TX_TIMEOUT.ms(CarrierType.HR, bandwidth))
            assertEquals("LR at $bandwidth", lr, PacketAirtime.TX_TIMEOUT.ms(CarrierType.LR, bandwidth))
        }
    }

    @Test
    fun `TX_TIMEOUT takes the slowest row when the bandwidth is unknown`() {
        assertEquals(1833L, PacketAirtime.TX_TIMEOUT.ms(CarrierType.HR, null))
        assertEquals(7297L, PacketAirtime.TX_TIMEOUT.ms(CarrierType.LR, null))
    }

    @Test
    fun `a TX timeout is never shorter than a text package's airtime`() {
        for (bandwidth in allBandwidths) for (type in CarrierType.entries) {
            val timeout = PacketAirtime.TX_TIMEOUT.ms(type, bandwidth)
            assert(timeout >= PacketAirtime.TEXT.ms(type, bandwidth)) { "$type at $bandwidth" }
        }
    }

    @Test
    fun `every message type uses the TX timeout table`() {
        FunctionalityType.entries.forEach {
            assertEquals("$it", PacketAirtime.TX_TIMEOUT, PacketAirtime.timeoutFor(it))
        }
    }
}
