package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.stardust.model.config.Bandwidth
import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.stardust.model.config.Preset
import com.commcrete.stardust.stardust.model.config.Xcvr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Two numberings of one transceiver: its slot (an event's xcvr, the send side's Carrier.index) and
 * its F (Xcvr.carrier.index 0-8, shown as the RD carrier's fNumber 1-9). The layout below is the
 * radio where they were mixed up: RD2 (slot 1, LR) sits on F3.
 */
class EventCarrierMappingTest {

    @Before
    fun preset() {
        ConfigurationUtils.selectedPreset = Preset(
            index = 0,
            xcvrList = mutableListOf(
                xcvr(fIndex = 0, type = CarrierType.HR, bandwidth = Bandwidth.BW_62_5),
                xcvr(fIndex = 2, type = CarrierType.LR, bandwidth = Bandwidth.BW_7_81),
                xcvr(fIndex = 1, type = CarrierType.HR, bandwidth = Bandwidth.BW_15_63),
            ),
        )
    }

    @After
    fun reset() {
        ConfigurationUtils.selectedPreset = null
    }

    private fun xcvr(fIndex: Int, type: CarrierType, bandwidth: Bandwidth) = Xcvr(
        txFrequency = 0.0,
        rxFrequency = 0.0,
        power = 0,
        options = 0,
        carrier = Carrier(index = fIndex, type = type),
        carrierOn = true,
        bandwidth = bandwidth.code,
    )

    private fun eventOn(xcvr: Int?) =
        StardustAppEventPackage(eventType = StardustAppEventPackage.StardustAppEventType.TXStart, xcvr = xcvr)
            .also(ConfigurationUtils::setStardustCarrierFromEvent)

    @Test
    fun `an event's carrier is numbered by F, which the channel indicator keys on`() {
        val rd2 = eventOn(xcvr = 1).carrier
        assertEquals("RD2 is on F3", 2, rd2?.index)
        assertEquals(CarrierType.LR, rd2?.type)
        assertEquals(1, eventOn(xcvr = 2).carrier?.index)
    }

    @Test
    fun `an event with no or an unknown xcvr has no carrier`() {
        assertNull(eventOn(xcvr = null).carrier)
        assertNull(eventOn(xcvr = 5).carrier)
    }

    @Test
    fun `an event's bandwidth is looked up by F, not as a slot`() {
        val rd2 = eventOn(xcvr = 1).carrier!!
        assertEquals(Bandwidth.BW_7_81, ConfigurationUtils.bandwidthForF(rd2.index))
        // xcvrList[2] is F2's transceiver - the wrong one for an F3 event.
        assertEquals(Bandwidth.BW_15_63, ConfigurationUtils.bandwidthFor(Carrier(index = 2, type = CarrierType.HR)))
    }

    @Test
    fun `the RD list carries each RD's F, numbered by slot`() {
        val rds = CarriersUtils.getCarriersByPreset(ConfigurationUtils.selectedPreset!!)
        assertEquals(listOf(0, 1, 2), rds.map { it.index })
        assertEquals("RD2 shows F3", listOf(1, 3, 2), rds.map { it.fNumber })
    }

    @Test
    fun `a saved list without F, or with an F the device moved, takes it from the configuration`() {
        val saved = listOf(
            Carrier(index = 0, type = CarrierType.HR),                // saved before fNumber existed
            Carrier(index = 1, type = CarrierType.LR, fNumber = 2),   // RD2 has since moved to F3
            Carrier(index = 2, type = CarrierType.HR, fNumber = 2),   // unchanged
        )
        val synced = CarriersUtils.withFNumbers(saved, ConfigurationUtils.selectedPreset!!)
        assertEquals(listOf(1, 3, 2), synced.map { it.fNumber })
        assertEquals(saved.map { it.index }, synced.map { it.index })
    }

    @Test
    fun `an RD with no transceiver in the configuration has no F`() {
        val synced = CarriersUtils.withFNumbers(
            listOf(Carrier(index = 5, type = CarrierType.HR, fNumber = 4)),
            ConfigurationUtils.selectedPreset!!,
        )
        assertNull(synced.single().fNumber)
    }
}
