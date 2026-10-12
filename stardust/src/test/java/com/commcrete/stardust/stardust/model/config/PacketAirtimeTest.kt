package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.enums.FunctionalityType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins every table value, so a change to a measurement is a deliberate change to this test too. */
class PacketAirtimeTest {

    private val allBandwidths = Bandwidth.entries + listOf(null)

    /** (bandwidth, HR ms, LR ms) for every bandwidth. */
    private val file = listOf(
        Triple(Bandwidth.BW_7_81, 706L, 4761L),
        Triple(Bandwidth.BW_10_42, 530L, 3570L),
        Triple(Bandwidth.BW_15_63, 353L, 2379L),
        Triple(Bandwidth.BW_20_83, 265L, 1785L),
        Triple(Bandwidth.BW_31_25, 226L, 1190L),
        Triple(Bandwidth.BW_41_67, 182L, 892L),
        Triple(Bandwidth.BW_62_5, 138L, 595L),
        Triple(Bandwidth.BW_125, 69L, 298L),
    )

    private val text131 = listOf(
        Triple(Bandwidth.BW_7_81, 1279L, 8531L),
        Triple(Bandwidth.BW_10_42, 959L, 6398L),
        Triple(Bandwidth.BW_15_63, 639L, 4263L),
        Triple(Bandwidth.BW_20_83, 480L, 3199L),
        Triple(Bandwidth.BW_31_25, 320L, 2132L),
        Triple(Bandwidth.BW_41_67, 240L, 1599L),
        Triple(Bandwidth.BW_62_5, 160L, 1066L),
        Triple(Bandwidth.BW_125, 80L, 533L),
    )

    private val channelTimeout200 = listOf(
        Triple(Bandwidth.BW_7_81, 1833L, 12300L),
        Triple(Bandwidth.BW_10_42, 1374L, 9224L),
        Triple(Bandwidth.BW_15_63, 916L, 6146L),
        Triple(Bandwidth.BW_20_83, 687L, 4612L),
        Triple(Bandwidth.BW_31_25, 458L, 3074L),
        Triple(Bandwidth.BW_41_67, 343L, 2305L),
        Triple(Bandwidth.BW_62_5, 229L, 1537L),
        Triple(Bandwidth.BW_125, 114L, 767L),
    )

    private fun assertTable(airtime: PacketAirtime, rows: List<Triple<Bandwidth, Long, Long>>) {
        assertEquals("$airtime covers every bandwidth", Bandwidth.entries.toSet(), rows.map { it.first }.toSet())
        for ((bandwidth, hr, lr) in rows) {
            assertEquals("$airtime HR at $bandwidth", hr, airtime.ms(CarrierType.HR, bandwidth))
            assertEquals("$airtime LR at $bandwidth", lr, airtime.ms(CarrierType.LR, bandwidth))
        }
    }

    @Test
    fun `FILE follows its table`() = assertTable(PacketAirtime.FILE, file)

    @Test
    fun `TEXT follows the 131-byte measurements`() = assertTable(PacketAirtime.TEXT, text131)

    @Test
    fun `CHANNEL_TIMEOUT follows the 200-byte measurements`() =
        assertTable(PacketAirtime.CHANNEL_TIMEOUT, channelTimeout200)

    @Test
    fun `an unknown bandwidth takes the slowest row`() {
        assertEquals(706L, PacketAirtime.FILE.defaultMs(CarrierType.HR))
        assertEquals(4761L, PacketAirtime.FILE.defaultMs(CarrierType.LR))
        assertEquals(1279L, PacketAirtime.TEXT.defaultMs(CarrierType.HR))
        assertEquals(8531L, PacketAirtime.TEXT.defaultMs(CarrierType.LR))
        assertEquals(1833L, PacketAirtime.CHANNEL_TIMEOUT.defaultMs(CarrierType.HR))
        assertEquals(12300L, PacketAirtime.CHANNEL_TIMEOUT.defaultMs(CarrierType.LR))
    }

    @Test
    fun `ST ignores the bandwidth`() {
        allBandwidths.forEach {
            assertEquals("FILE ST at $it", 450L, PacketAirtime.FILE.ms(CarrierType.ST, it))
            assertEquals("TEXT ST at $it", 4000L, PacketAirtime.TEXT.ms(CarrierType.ST, it))
            assertEquals("CHANNEL_TIMEOUT ST at $it", 4000L, PacketAirtime.CHANNEL_TIMEOUT.ms(CarrierType.ST, it))
        }
    }

    @Test
    fun `a channel timeout is never shorter than a text package's airtime`() {
        for (bandwidth in allBandwidths) for (type in CarrierType.entries) {
            val timeout = PacketAirtime.CHANNEL_TIMEOUT.ms(type, bandwidth)
            assert(timeout >= PacketAirtime.TEXT.ms(type, bandwidth)) { "$type at $bandwidth" }
        }
    }

    @Test
    fun `every message type uses the channel timeout table`() {
        FunctionalityType.entries.forEach {
            assertEquals("$it", PacketAirtime.CHANNEL_TIMEOUT, PacketAirtime.timeoutFor(it))
        }
    }
}
