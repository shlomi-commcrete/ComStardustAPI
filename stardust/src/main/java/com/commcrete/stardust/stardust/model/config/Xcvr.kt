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
     * A TX frequency is set, so this transceiver can send. The wire values are offsets from the
     * TX/RX bases (1195.5 / 1095 MHz), so an unset TX is what Commander shows as TX 1195.500000.
     */
    val hasTx: Boolean get() = txFrequency != 0.0

    /** An RX frequency is set, so this transceiver can receive. Unset shows as RX 1095.000000. */
    val hasRx: Boolean get() = rxFrequency != 0.0

    /**
     * Neither half is set. TX and RX are separate problems — one without the other still sends or
     * still receives — so this is only "nothing configured at all"; see [hasTx] and [hasRx].
     */
    fun hasDefaultFrequency(): Boolean {
        return !hasTx && !hasRx
    }
}
