package com.commcrete.stardust.stardust.model.config

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * One satellite frequency lease: a TX/RX center-frequency pair (MHz, truncated to at most 5
 * decimal places), mirroring
 * the C core's `sb_lease_t`.
 */
data class Lease(val tx: String, val rx: String)

/**
 * Computes the frequency leases for a preset. Faithful port of the single-device path in the C
 * core's `lease_calculate.c` (`sb_calculate_leases` -> `accumulate_preset` -> `xcvr_leases`).
 *
 * Each transceiver occupies a block of carriers determined by its bandwidth code; a lease is one
 * 25 kHz channel (3 carriers). Only transceivers reporting an RD index of 1-3 with a valid
 * bandwidth contribute. Results are de-duplicated and capped at [MAX_LEASES]. No sort is applied,
 * matching `sb_calculate_leases` (the C#/Python references sort by TX; we deliberately do not).
 *
 * Math is done in integer third-Hz (a carrier is 25/3 kHz, so every offset is whole), never in
 * Double: two transceivers on the same channel must produce the same lease, and Double sums or the
 * wire's whole-Hz rounding left them a fraction of a Hz apart, which truncation then split into two
 * different strings (e.g. "1651.0025" vs "1651.00249").
 */
object LeaseCalculator {

    private const val CARRIERS_PER_LEASE = 3
    private const val LEASE_THIRD_HZ = 75_000L    // 25 kHz
    private const val CARRIER_THIRD_HZ = 25_000L  // 8.333... kHz
    private const val RX_BASE_HZ = 1_095_000_000L
    private const val TX_BASE_HZ = 1_195_500_000L
    private const val MAX_LEASES = 3 // SB_MAX_LEASES

    /**
     * Leases this close on both TX and RX are the same channel. The wire carries each transceiver's
     * frequency rounded to whole Hz, so the same channel reached from different transceivers can be
     * ~1 Hz apart; real channels are at least a carrier (8.33 kHz) apart.
     */
    private const val SAME_CHANNEL_TOLERANCE_HZ = 100L

    private class LeaseHz(val txHz: Long, val rxHz: Long) {
        fun isSameChannel(other: LeaseHz) =
            abs(txHz - other.txHz) <= SAME_CHANNEL_TOLERANCE_HZ &&
                abs(rxHz - other.rxHz) <= SAME_CHANNEL_TOLERANCE_HZ
    }

    fun calculatePresetLeases(preset: Preset): List<Lease> {
        val out = ArrayList<LeaseHz>()
        for (xcvr in preset.xcvrList) {
            if (out.size >= MAX_LEASES) break

            // Slot 3 (ST) and any non-RD entry carry no lease of their own.
            if (xcvr.rdIndex < 1 || xcvr.rdIndex > 3) continue
            val bandwidth = xcvr.bandwidthOption?.takeIf { it.supportsLeases } ?: continue

            for (lease in xcvrLeases(xcvr.txFrequency, xcvr.rxFrequency, xcvr.carrier.index, bandwidth.carriers)) {
                if (out.size >= MAX_LEASES) break
                if (out.none { it.isSameChannel(lease) }) out.add(lease)
            }
        }
        return out.map { Lease(tx = formatMhz(it.txHz), rx = formatMhz(it.rxHz)) }
    }

    private fun xcvrLeases(txMhz: Double, rxMhz: Double, carrier: Int, nCarriers: Int): List<LeaseHz> {
        if (txMhz == 0.0 || rxMhz == 0.0) return emptyList()

        // The wire value is whole Hz divided by 1e6, so rounding recovers it exactly.
        val txThirdHz = 3 * (Math.round(txMhz * 1_000_000) + TX_BASE_HZ)
        val rxThirdHz = 3 * (Math.round(rxMhz * 1_000_000) + RX_BASE_HZ)

        val start = carrier
        val end = start + nCarriers - 1
        val first = start / CARRIERS_PER_LEASE
        val last = end / CARRIERS_PER_LEASE

        // The stored frequency is the CENTER of the carrier block. Walk back to lease 0's center
        // ((start + end) / 2 - 1 carriers), then step one channel per lease.
        val backThirdHz = (start + end - 2) * CARRIER_THIRD_HZ / 2

        val out = ArrayList<LeaseHz>()
        var li = first
        while (li <= last && out.size < MAX_LEASES) {
            val offsetThirdHz = li * LEASE_THIRD_HZ - backThirdHz
            out.add(LeaseHz(toHz(txThirdHz + offsetThirdHz), toHz(rxThirdHz + offsetThirdHz)))
            li++
        }
        return out
    }

    // Nearest whole Hz. A third-Hz remainder is 0, 1 or 2, so there is never a tie.
    private fun toHz(thirdHz: Long): Long = Math.floorDiv(thirdHz + 1, 3L)

    // MHz truncated (no rounding) to at most 5 decimal places, dropping trailing zeros.
    private fun formatMhz(hz: Long): String =
        BigDecimal.valueOf(hz, 6).setScale(5, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
}
