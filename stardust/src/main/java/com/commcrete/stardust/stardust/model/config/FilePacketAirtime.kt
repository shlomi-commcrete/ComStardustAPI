package com.commcrete.stardust.stardust.model.config

/**
 * Airtime of one file package on the radio. Port of the C core's `sb_calculate_send_time.c`
 * (`s_ms_per_pkt`), plus ST, which the C core does not cover.
 *
 * Measured for file / image / contact packages only. Text, ACK and PTT packages are much smaller,
 * so these values do not apply to them.
 */
object FilePacketAirtime {

    /** HR when the bandwidth is unknown (legacy firmware, no config yet) or has no measurement. */
    private const val HR_DEFAULT_MS = 705L

    /** LR counterpart of [HR_DEFAULT_MS]. */
    private const val LR_DEFAULT_MS = 2800L

    /** ST sends at a fixed rate that does not follow the bandwidth. */
    private const val ST_MS = 450L

    /** Default airtime of [type]: what [ms] returns when the bandwidth is unknown. */
    fun defaultMs(type: CarrierType): Long = ms(type, null)

    fun ms(type: CarrierType, bandwidth: Bandwidth?): Long = Math.round(
        when (type) {
            CarrierType.ST -> ST_MS.toDouble()
            CarrierType.HR -> hrMs(bandwidth) ?: HR_DEFAULT_MS.toDouble()
            CarrierType.LR -> lrMs(bandwidth) ?: LR_DEFAULT_MS.toDouble()
        }
    )

    private fun hrMs(bandwidth: Bandwidth?): Double? = when (bandwidth) {
        Bandwidth.BW_7_81 -> 755.762
        Bandwidth.BW_15_63 -> 402.655
        Bandwidth.BW_20_83 -> 314.618
        Bandwidth.BW_31_25 -> 226.384
        Bandwidth.BW_41_67 -> 182.277
        Bandwidth.BW_62_5 -> 138.192
        Bandwidth.BW_10_42, Bandwidth.BW_125, null -> null
    }

    private fun lrMs(bandwidth: Bandwidth?): Double? = when (bandwidth) {
        Bandwidth.BW_7_81 -> 2840.269
        Bandwidth.BW_15_63 -> 1444.242
        Bandwidth.BW_20_83 -> 1096.183
        Bandwidth.BW_31_25 -> 747.344
        Bandwidth.BW_41_67 -> 572.966
        Bandwidth.BW_62_5 -> 398.672
        Bandwidth.BW_10_42, Bandwidth.BW_125, null -> null
    }
}
