package com.commcrete.stardust.stardust.model.config

import java.math.BigDecimal
import java.math.RoundingMode

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
 */
object LeaseCalculator {

    private const val BANDWIDTH_KHZ = 25.0
    private const val CARRIERS_PER_LEASE = 3
    private const val CARRIER_BW_KHZ = BANDWIDTH_KHZ / CARRIERS_PER_LEASE // 8.333...
    private const val RX_BASE_MHZ = 1095.0
    private const val TX_BASE_MHZ = 1195.5
    private const val MAX_LEASES = 3 // SB_MAX_LEASES

    fun calculatePresetLeases(preset: Preset): List<Lease> {
        val out = ArrayList<Lease>()
        for (xcvr in preset.xcvrList) {
            if (out.size >= MAX_LEASES) break

            // Slot 3 (ST) and any non-RD entry carry no lease of their own.
            if (xcvr.rdIndex < 1 || xcvr.rdIndex > 3) continue
            val bandwidth = xcvr.bandwidthOption?.takeIf { it.supportsLeases } ?: continue

            for (lease in xcvrLeases(xcvr.txFrequency, xcvr.rxFrequency, xcvr.carrier.index, bandwidth.carriers)) {
                if (out.size >= MAX_LEASES) break
                if (out.none { it.tx == lease.tx && it.rx == lease.rx }) out.add(lease)
            }
        }
        return out
    }

    private fun xcvrLeases(txMhz: Double, rxMhz: Double, carrier: Int, nCarriers: Int): List<Lease> {
        if (txMhz == 0.0 || rxMhz == 0.0) return emptyList()

        val start = carrier
        val end = start + nCarriers - 1
        val first = start / CARRIERS_PER_LEASE
        val last = end / CARRIERS_PER_LEASE

        // The stored frequency is the CENTER of the carrier block. Walk back to lease 0's center,
        // then step one channel per lease.
        val backKhz = ((start + end) / 2.0 - 1.0) * CARRIER_BW_KHZ

        val out = ArrayList<Lease>()
        var li = first
        while (li <= last && out.size < MAX_LEASES) {
            val offsetMhz = (li * BANDWIDTH_KHZ - backKhz) / 1000.0
            val tx = txMhz + TX_BASE_MHZ + offsetMhz
            val rx = rxMhz + RX_BASE_MHZ + offsetMhz
            out.add(Lease(tx = formatMhz(tx), rx = formatMhz(rx)))
            li++
        }
        return out
    }

    // Truncates (no rounding) to at most 5 decimal places, dropping trailing zeros.
    private fun formatMhz(value: Double): String =
        BigDecimal.valueOf(value).setScale(5, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
}
