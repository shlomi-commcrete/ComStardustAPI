package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.util.Carrier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy-firmware rule the SDK's presetsWithoutConfig misses: a preset nobody configured has no
 * transceiver with a frequency, so it has no functionality that could be missing and passed as usable.
 */
class PresetUnconfiguredTest {

    private fun xcvr(type: CarrierType, tx: Double = 0.0, rx: Double = 0.0) = Xcvr(
        txFrequency = tx,
        rxFrequency = rx,
        power = 0,
        options = 0,
        carrier = Carrier(index = 0, type = type),
        carrierOn = true,
    )

    private fun preset(vararg xcvrs: Xcvr) = Preset(index = 1, xcvrList = xcvrs.toMutableList())

    @Test
    fun `a preset with no frequency on any transceiver is unconfigured`() {
        assertTrue(preset(xcvr(CarrierType.HR), xcvr(CarrierType.LR), xcvr(CarrierType.HR)).isUnconfigured())
        assertTrue("no transceivers at all", preset().isUnconfigured())
    }

    @Test
    fun `an ST frequency alone does not configure a preset`() {
        assertTrue(preset(xcvr(CarrierType.HR), xcvr(CarrierType.ST, tx = 1600.5, rx = 1500.5)).isUnconfigured())
    }

    /**
     * Preset 1 of a live legacy config (fw Ver_2.0.23.6, captured 2026-10-22): TX set on every RD,
     * RX 0 (Commander: RX 1095.000000). One half set is a separate problem from nothing set: it
     * still sends (or still receives), so the preset is not unconfigured.
     */
    @Test
    fun `TX without RX, or RX without TX, is configured but one-sided`() {
        val txOnly = xcvr(CarrierType.HR, tx = 434.929166)
        assertTrue(txOnly.hasTx)
        assertFalse(txOnly.hasRx)
        assertFalse(txOnly.hasDefaultFrequency())
        assertFalse(preset(txOnly, xcvr(CarrierType.LR), xcvr(CarrierType.HR)).isUnconfigured())

        val rxOnly = xcvr(CarrierType.HR, rx = 1500.5)
        assertFalse(rxOnly.hasTx)
        assertTrue(rxOnly.hasRx)
        assertFalse(preset(rxOnly).isUnconfigured())
    }

    @Test
    fun `no TX blocks sending, no RX does not, and both are Commander fixes`() {
        assertTrue(PresetProblem.NO_TX.blocksTransmission)
        assertFalse(PresetProblem.NO_RX.blocksTransmission)
        assertFalse(PresetProblem.NO_TX.fixableInApp)
        assertFalse(PresetProblem.NO_RX.fixableInApp)
        assertTrue(PresetProblem.MISSING_FUNCTIONALITY.fixableInApp)
    }

    @Test
    fun `one RD transceiver with a frequency is enough`() {
        assertFalse(preset(xcvr(CarrierType.HR), xcvr(CarrierType.LR, tx = 1600.5, rx = 1500.5)).isUnconfigured())
    }

    @Test
    fun `only a preset with nothing to transmit on blocks every send`() {
        assertTrue(PresetProblem.NO_LEASE.blocksTransmission)
        assertTrue(PresetProblem.NOT_CONFIGURED.blocksTransmission)
        assertFalse("missing defaults affect RD Auto only", PresetProblem.MISSING_FUNCTIONALITY.blocksTransmission)
    }
}
