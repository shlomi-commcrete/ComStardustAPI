package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.enums.FunctionalityType

/**
 * Airtime of one package on the radio, per package kind, carrier type and bandwidth. Static tables
 * only: a new kind is a new entry, a new measurement is a new map value.
 *
 * A bandwidth missing from a map (unmeasured, unknown, legacy firmware) falls back to the type's
 * default. ST sends at a fixed rate that does not follow the bandwidth.
 */
enum class PacketAirtime(
    private val hrDefaultMs: Long,
    private val lrDefaultMs: Long,
    private val stMs: Long,
    private val hrByBandwidth: Map<Bandwidth, Long> = emptyMap(),
    private val lrByBandwidth: Map<Bandwidth, Long> = emptyMap(),
) {
    /**
     * File / image / contact packages. Port of the C core's `sb_calculate_send_time.c`
     * (`s_ms_per_pkt`), rounded to the millisecond, plus ST, which the C core does not cover.
     */
    FILE(
        hrDefaultMs = 705L,
        lrDefaultMs = 2800L,
        stMs = 450L,
        hrByBandwidth = mapOf(
            Bandwidth.BW_7_81 to 756L,
            Bandwidth.BW_15_63 to 403L,
            Bandwidth.BW_20_83 to 315L,
            Bandwidth.BW_31_25 to 226L,
            Bandwidth.BW_41_67 to 182L,
            Bandwidth.BW_62_5 to 138L,
        ),
        lrByBandwidth = mapOf(
            Bandwidth.BW_7_81 to 2840L,
            Bandwidth.BW_15_63 to 1444L,
            Bandwidth.BW_20_83 to 1096L,
            Bandwidth.BW_31_25 to 747L,
            Bandwidth.BW_41_67 to 573L,
            Bandwidth.BW_62_5 to 399L,
        ),
    ),

//    const SEND_TIME_MS: Array<{ BW: number; HR: number; LR: number }> = [
//    { BW: 7.81, HR: 715.762, LR: 2800.269 },
//    { BW: 15.63, HR: 362.655, LR: 1404.242 },
//    { BW: 20.83, HR: 274.618, LR: 1056.183 },
//    { BW: 41.67, HR: 142.277, LR: 532.966 },
//    { BW: 62.5, HR: 98.192, LR: 358.672 },
//    ];

    /**
     * Text parts, measured airtime of a 131-byte data package. The table's 24/25 and 50 kHz rows are
     * [Bandwidth.BW_20_83] and [Bandwidth.BW_41_67] (times scale with them). Unmeasured rows are
     * derived from the measured µs: BW_10_42 = BW_20_83 × 2, BW_31_25 = BW_15_63 / 2,
     * BW_125 = BW_62_5 / 2. Unknown bandwidth takes the slowest row; ST is unmeasured.
     */
    TEXT(
        hrDefaultMs = 1279L,
        lrDefaultMs = 5085L,
        stMs = 4000L,
        hrByBandwidth = mapOf(
            Bandwidth.BW_7_81 to 1279L,
            Bandwidth.BW_10_42 to 959L,
            Bandwidth.BW_15_63 to 639L,
            Bandwidth.BW_20_83 to 480L,
            Bandwidth.BW_31_25 to 320L,
            Bandwidth.BW_41_67 to 240L,
            Bandwidth.BW_62_5 to 160L,
            Bandwidth.BW_125 to 80L,
        ),
        lrByBandwidth = mapOf(
            Bandwidth.BW_7_81 to 5085L,
            Bandwidth.BW_10_42 to 3813L,
            Bandwidth.BW_15_63 to 2541L,
            Bandwidth.BW_20_83 to 1906L,
            Bandwidth.BW_31_25 to 1270L,
            Bandwidth.BW_41_67 to 953L,
            Bandwidth.BW_62_5 to 635L,
            Bandwidth.BW_125 to 318L,
        ),
    ),

    /**
     * TX indicator timeout: measured airtime of a 200-byte data package, the largest frame, so the
     * timeout never ends a package still on air. Same bandwidth mapping and derived rows as [TEXT].
     * Unknown bandwidth takes the slowest row; ST is unmeasured and keeps the old text interval.
     */
    TX_TIMEOUT(
        hrDefaultMs = 1833L,
        lrDefaultMs = 7297L,
        stMs = 4000L,
        hrByBandwidth = mapOf(
            Bandwidth.BW_7_81 to 1833L,
            Bandwidth.BW_10_42 to 1374L,
            Bandwidth.BW_15_63 to 916L,
            Bandwidth.BW_20_83 to 687L,
            Bandwidth.BW_31_25 to 458L,
            Bandwidth.BW_41_67 to 343L,
            Bandwidth.BW_62_5 to 229L,
            Bandwidth.BW_125 to 114L,
        ),
        lrByBandwidth = mapOf(
            Bandwidth.BW_7_81 to 7297L,
            Bandwidth.BW_10_42 to 5472L,
            Bandwidth.BW_15_63 to 3646L,
            Bandwidth.BW_20_83 to 2736L,
            Bandwidth.BW_31_25 to 1823L,
            Bandwidth.BW_41_67 to 1368L,
            Bandwidth.BW_62_5 to 912L,
            Bandwidth.BW_125 to 456L,
        ),
    );

    fun ms(type: CarrierType, bandwidth: Bandwidth?): Long = when (type) {
        CarrierType.ST -> stMs
        CarrierType.HR -> bandwidth?.let(hrByBandwidth::get) ?: hrDefaultMs
        CarrierType.LR -> bandwidth?.let(lrByBandwidth::get) ?: lrDefaultMs
    }

    /** What [ms] returns when the bandwidth is unknown. */
    fun defaultMs(type: CarrierType): Long = ms(type, null)

    companion object {
        /** One table for every message type today; a per-type timeout becomes a branch here. */
        @Suppress("UNUSED_PARAMETER")
        fun timeoutFor(functionality: FunctionalityType): PacketAirtime = TX_TIMEOUT
    }
}
