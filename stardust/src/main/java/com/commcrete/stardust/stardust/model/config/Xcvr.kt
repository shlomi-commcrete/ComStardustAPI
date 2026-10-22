package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.util.Carrier

data class Xcvr(
    var txFrequency: Double,
    var rxFrequency: Double,
    var power: Int,
    var options: Int,
    var carrier: Carrier,
    var carrierOn: Boolean,
    /** Logical RD from the carrier byte (bits 3-4): 1-3 = RD1-3, 0 = not an RD. */
    var rdIndex: Int = 0,
    /** Bandwidth code 0-7 (Ver_24.0.7+); -1 = not reported by legacy firmware. */
    var bandwidth: Int = -1
) {
    /** [bandwidth] as a [Bandwidth]; null when legacy firmware did not report one. */
    val bandwidthOption: Bandwidth? get() = Bandwidth.fromCode(bandwidth)

    fun getOptions(): Set<FunctionalityType> {
        return FunctionalityType.entries.filter { type -> (options and type.bitwise) == type.bitwise }.toSet()
    }

    /**
     * No usable channel: TX or RX is unset (0). Either half missing is enough — a transceiver that
     * cannot both send and receive carries nothing — and the lease calculator already drops a
     * transceiver with either half at 0 (LeaseCalculator.xcvrLeases), so the two agree.
     *
     * The wire values are offsets from the TX/RX bases (1195.5 / 1095 MHz), so an unset half is what
     * Commander shows as TX 1195.500000 or RX 1095.000000.
     */
    fun hasDefaultFrequency(): Boolean {
        return txFrequency == 0.0 || rxFrequency == 0.0
    }
}
