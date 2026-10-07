package com.commcrete.stardust.stardust.model.config

/**
 * Interval between the parts of a long text, per carrier type and bandwidth — the text
 * counterpart of [FilePacketAirtime]. A full text part is a 149-byte frame (131-byte body) against
 * a file package's 81 (63-byte body), so the file values do not apply here.
 *
 * Not measured yet: every bandwidth carries the interval the sender has always used (HR 800 ms,
 * LR and ST 4000 ms). Replace the per-bandwidth entries once text airtime is measured; callers
 * already pass the bandwidth, so nothing else changes.
 */
object TextPacketAirtime {

    /** HR when the bandwidth is unknown (legacy firmware, no config yet) or has no measurement. */
    private const val HR_DEFAULT_MS = 800L

    /** LR counterpart of [HR_DEFAULT_MS]. */
    private const val LR_DEFAULT_MS = 4000L

    /** ST does not follow the bandwidth. */
    private const val ST_MS = 4000L

    /** Default interval of [type]: what [ms] returns when the bandwidth is unknown. */
    fun defaultMs(type: CarrierType): Long = ms(type, null)

    fun ms(type: CarrierType, bandwidth: Bandwidth?): Long = when (type) {
        CarrierType.ST -> ST_MS
        CarrierType.HR -> hrMs(bandwidth) ?: HR_DEFAULT_MS
        CarrierType.LR -> lrMs(bandwidth) ?: LR_DEFAULT_MS
    }

    // TODO: measured text airtime per bandwidth. Until then each entry is the current interval.
    private fun hrMs(bandwidth: Bandwidth?): Long? = when (bandwidth) {
        Bandwidth.BW_7_81 -> 800L
        Bandwidth.BW_15_63 -> 800L
        Bandwidth.BW_20_83 -> 800L
        Bandwidth.BW_31_25 -> 800L
        Bandwidth.BW_41_67 -> 800L
        Bandwidth.BW_62_5 -> 800L
        Bandwidth.BW_10_42, Bandwidth.BW_125, null -> null
    }

    private fun lrMs(bandwidth: Bandwidth?): Long? = when (bandwidth) {
        Bandwidth.BW_7_81 -> 4000L
        Bandwidth.BW_15_63 -> 4000L
        Bandwidth.BW_20_83 -> 4000L
        Bandwidth.BW_31_25 -> 4000L
        Bandwidth.BW_41_67 -> 4000L
        Bandwidth.BW_62_5 -> 4000L
        Bandwidth.BW_10_42, Bandwidth.BW_125, null -> null
    }
}
