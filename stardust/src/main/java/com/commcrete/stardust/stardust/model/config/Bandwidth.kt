package com.commcrete.stardust.stardust.model.config

/**
 * Transceiver bandwidth, as reported in bits 5-7 of the carrier byte (Ver_24.0.7+). Mirrors the C
 * core's `bandwidth_t`: [khz] is `BW_KHZ[]`, [carriers] is `BW_CARRIERS[]` from `lease_calculate.c`.
 *
 * Wire facts only. Timing measured on top of a bandwidth lives in [FilePacketAirtime].
 */
enum class Bandwidth(val code: Int, val khz: Double, val carriers: Int) {
    BW_7_81(0, 7.81, 1),
    BW_10_42(1, 10.42, 0),
    BW_15_63(2, 15.63, 2),
    BW_20_83(3, 20.83, 3),
    BW_31_25(4, 31.25, 4),
    BW_41_67(5, 41.67, 5),
    BW_62_5(6, 62.5, 8),
    BW_125(7, 125.0, 0);

    /** Whether a transceiver on this bandwidth occupies carriers, and so yields leases. */
    val supportsLeases: Boolean get() = carriers > 0

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** Null for legacy firmware's -1 and anything else outside 0-7. */
        fun fromCode(code: Int): Bandwidth? = byCode[code]
    }
}
