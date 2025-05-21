package com.commcrete.stardust.util

import kotlin.random.Random

typealias Packet = ByteArray
typealias BinaryMatrix = Array<IntArray>

interface LDPCEncoder {
    /** Encode data packets and append parity packets. */
    fun encode(packets: List<Packet>): List<Packet>
    /** Decode a mix of received and missing packets (nulls) to recover originals. */
    fun decode(received: List<Packet?>, lostIndices: List<Int>): List<Packet>
}

class LDPCCode(
    private val maxPackets: Int = 100,
    private val parityPackets: Int = 4
) : LDPCEncoder {
    private val parityCheckMatrix: BinaryMatrix = generateParityCheckMatrix(maxPackets, parityPackets)
    private var maxPacketSize: Int = 0

    private fun generateParityCheckMatrix(dataLength: Int, parityLength: Int): BinaryMatrix {
        val total = dataLength + parityLength
        val H = Array(parityLength) { IntArray(total) { 0 } }

        // 1) Identity block for parity‐part
        for (p in 0 until parityLength) {
            H[p][dataLength + p] = 1
        }

        // 2) Diagonal‐style coverage for data packets
        for (p in 0 until parityLength) {
            for (d in 0 until dataLength) {
                if ((d + p) % parityLength == 0
                    || (d + p + 1) % parityLength == 0
                    || (d + p + 2) % parityLength == 0
                ) {
                    H[p][d] = 1
                }
            }
            // Ensure at least 4 data‐packet checks
            val ones = H[p].slice(0 until dataLength).sum()
            if (ones < 4) {
                repeat(4 - ones) {
                    var pos: Int
                    do { pos = Random.nextInt(dataLength) }
                    while (H[p][pos] == 1)
                    H[p][pos] = 1
                }
            }
        }

        return H
    }

    private fun xorPackets(a: Packet, b: Packet): Packet {
        val length = maxOf(a.size, b.size)
        val out = ByteArray(length)
        for (i in 0 until length) {
            val va = if (i < a.size) a[i].toInt() else 0
            val vb = if (i < b.size) b[i].toInt() else 0
            out[i] = (va xor vb).toByte()
        }
        return out
    }

    override fun encode(packets: List<Packet>): List<Packet> {
        val dataLen = packets.size
        require(dataLen <= maxPackets) { "Too many packets; max is $maxPackets." }

        if (dataLen > 0) {
            maxPacketSize = packets[0].size
            require(packets.all { it.size == maxPacketSize }) {
                "All packets must be the same size."
            }
        }

        // copy data + init parity slots
        val all = packets.toMutableList()
        repeat(parityPackets) { all += ByteArray(maxPacketSize) }

        // build parity
        for (p in 0 until parityPackets) {
            val parityIndex = dataLen + p
            for (d in 0 until dataLen) {
                if (parityCheckMatrix[p][d] == 1) {
                    all[parityIndex] = xorPackets(all[parityIndex], packets[d])
                }
            }
        }

        return all
    }

    override fun decode(received: List<Packet?>, lostIndices: List<Int>): List<Packet> {
        val total = maxPackets + parityPackets
        received.firstOrNull { it != null }?.let {
            maxPacketSize = it.size
        }
        val working = received.toMutableList()

        // pad or trim to expected length
        if (working.size < total) {
            repeat(total - working.size) { working += null }
        } else if (working.size > total) {
            error("Expected $total packets including nulls.")
        }

        val missingData = lostIndices.count { it < maxPackets }
        if (missingData > parityPackets) {
            println("Warning: $missingData missing data; can only recover $parityPackets.")
        }

        val parityOk = BooleanArray(parityPackets)
        var iterations = 0
        val maxIter = 50

        do {
            var progress = false
            iterations++

            for (p in 0 until parityPackets) {
                if (parityOk[p]) continue
                val parityIdx = maxPackets + p
                val parityPkt = working[parityIdx] ?: continue

                val involved = mutableListOf<Int>()
                val missing = mutableListOf<Int>()
                for (i in 0 until total) {
                    if (parityCheckMatrix[p][i] == 1) {
                        involved += i
                        if (working[i] == null) missing += i
                    }
                }

                when (missing.size) {
                    0 -> parityOk[p] = true
                    1 -> {
                        // recover the lone missing packet
                        val missIdx = missing[0]
                        val rec = ByteArray(maxPacketSize)
                        for (idx in involved) {
                            working[idx]?.let { pkt ->
                                for (b in pkt.indices) {
                                    rec[b] = (rec[b].toInt() xor pkt[b].toInt()).toByte()
                                }
                            }
                        }
                        working[missIdx] = rec
                        parityOk[p] = true
                        progress = true
                    }
                }
            }

        } while (iterations < maxIter && parityOk.any { !it })

        // final check
        val stillMissing = working.take(maxPackets).count { it == null }
        if (stillMissing > 0) {
            println("Could not recover $stillMissing packets after $iterations iterations.")
        }

        // cast away nulls for returned data
        return working.take(maxPackets).map { it ?: ByteArray(maxPacketSize) }
    }
}