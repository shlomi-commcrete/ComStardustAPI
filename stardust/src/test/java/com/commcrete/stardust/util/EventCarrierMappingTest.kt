package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.stardust.model.config.Preset
import com.commcrete.stardust.stardust.model.config.Xcvr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A radio event's xcvr is the transceiver's position in the preset — the index the send side's
 * carriers use — not the hardware carrier number in the config byte. On the radio where this was
 * found, transceiver 1 (LR) carried hardware carrier 2.
 */
class EventCarrierMappingTest {

    @After
    fun reset() {
        ConfigurationUtils.selectedPreset = null
    }

    private fun xcvr(hardwareCarrier: Int, type: CarrierType) = Xcvr(
        txFrequency = 0.0,
        rxFrequency = 0.0,
        power = 0,
        options = 0,
        carrier = Carrier(index = hardwareCarrier, type = type),
        carrierOn = true,
    )

    private fun eventOn(xcvr: Int?) =
        StardustAppEventPackage(eventType = StardustAppEventPackage.StardustAppEventType.TXStart, xcvr = xcvr)
            .also(ConfigurationUtils::setStardustCarrierFromEvent)

    @Test
    fun `xcvr maps to the carrier at that position, not the hardware carrier number`() {
        ConfigurationUtils.selectedPreset = Preset(
            index = 0,
            xcvrList = mutableListOf(
                xcvr(hardwareCarrier = 0, type = CarrierType.HR),
                xcvr(hardwareCarrier = 2, type = CarrierType.LR),
                xcvr(hardwareCarrier = 1, type = CarrierType.HR),
            ),
        )

        val lr = eventOn(xcvr = 1).carrier
        assertEquals(1, lr?.index)
        assertEquals(CarrierType.LR, lr?.type)
        assertEquals(2, eventOn(xcvr = 2).carrier?.index)
    }

    @Test
    fun `no xcvr or no preset leaves the carrier unknown`() {
        assertNull(eventOn(xcvr = 1).carrier)
        ConfigurationUtils.selectedPreset = Preset(index = 0, xcvrList = mutableListOf(xcvr(0, CarrierType.HR)))
        assertNull(eventOn(xcvr = null).carrier)
        assertNull(eventOn(xcvr = 5).carrier)
    }
}
