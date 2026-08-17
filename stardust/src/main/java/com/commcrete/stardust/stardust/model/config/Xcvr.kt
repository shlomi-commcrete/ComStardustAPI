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
    fun getOptions(): Set<FunctionalityType> {
        return FunctionalityType.entries.filter { type -> (options and type.bitwise) == type.bitwise }.toSet()
    }

    fun hasDefaultFrequency(): Boolean {
        return txFrequency == 0.0 && rxFrequency == 0.0
    }
}
