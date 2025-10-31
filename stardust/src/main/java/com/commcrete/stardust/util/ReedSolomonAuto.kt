@file:Suppress("MemberVisibilityCanBePrivate")
package com.commcrete.stardust.util
import android.annotation.SuppressLint
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor

class ReedSolomon(
    totalDataPackets: Int,
    totalParityPackets: Int
) {
    // total data/parity packets (across file)
    private var Ktotal: Int
    private var Ptotal: Int

    // block plan
    private var blocks: MutableList<Block> = mutableListOf()

    // caches per block for encoding interpolation
    // encodeCache[blockIdx] = { xVals, numerPolys, denomInv }
    private var encodeCache: Array<EncodeCache?> = emptyArray()

    // decode caches keyed by blockIdx + ":" + receivedIndices.joinToString(",")
    private val decodeCache: MutableMap<String, EncodeCache> = ConcurrentHashMap()

    init {
        if (!inited) initGF()
        require(totalDataPackets >= 0 && totalParityPackets >= 0) { "invalid packet counts" }
        Ktotal = totalDataPackets
        Ptotal = totalParityPackets
        planBlocks() // fills this.blocks and init caches
    }

    // ---- GF init (static) ----
    private data class Block(val k: Int, val p: Int, val n: Int)
    private data class Precomp(val numerPolys: Array<IntArray>, val denomInv: IntArray)
    private data class EncodeCache(val xVals: IntArray, val numerPolys: Array<IntArray>, val denomInv: IntArray)

    companion object {
        private lateinit var EXP: IntArray
        private lateinit var LOG: IntArray
        private var inited = false

        private fun initGF() {
            val prim = 0x11d
            EXP = IntArray(512)
            LOG = IntArray(256)
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor prim
            }
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
            inited = true
        }

        private fun gfAdd(a: Int, b: Int): Int = a xor b
        private fun gfSub(a: Int, b: Int): Int = a xor b

        private fun gfMul(a: Int, b: Int): Int {
            if (a == 0 || b == 0) return 0
            return EXP[(LOG[a] + LOG[b]) % 255]
        }

        private fun gfDiv(a: Int, b: Int): Int {
            require(b != 0) { "GF divide by 0" }
            if (a == 0) return 0
            var v = LOG[a] - LOG[b]
            if (v < 0) v += 255
            return EXP[v]
        }

        private fun gfInverse(a: Int): Int {
            require(a != 0) { "inv(0)" }
            return EXP[255 - LOG[a]]
        }

        private fun evalPoint(power: Int): Int {
            val p = ((power % 255) + 255) % 255
            return EXP[p]
        }

        // poly helpers (coeffs as Int in 0..255)
        private fun polyMul(a: IntArray, b: IntArray): IntArray {
            val r = IntArray(a.size + b.size - 1)
            for (i in a.indices) {
                val ai = a[i]
                if (ai == 0) continue
                for (j in b.indices) {
                    val bj = b[j]
                    if (bj == 0) continue
                    r[i + j] = r[i + j] xor gfMul(ai, bj)
                }
            }
            return r
        }

        private fun polyScale(a: IntArray, scalar: Int): IntArray {
            if (scalar == 0) return IntArray(a.size)
            return IntArray(a.size) { idx -> if (a[idx] == 0) 0 else gfMul(a[idx], scalar) }
        }

        private fun polyAddTo(target: MutableList<Int>, add: IntArray) {
            if (add.size > target.size) {
                repeat(add.size - target.size) { target.add(0) }
            }
            for (i in add.indices) {
                target[i] = (target[i] xor add[i]) and 0xFF
            }
        }

        private fun polyEval(coeffs: IntArray, x: Int): Int {
            var y = 0
            for (i in coeffs.size - 1 downTo 0) {
                y = gfMul(y, x) xor (coeffs[i] and 0xFF)
            }
            return y
        }

        // Lagrange precompute for given xVals (distinct)
        private fun precomputeLagrange(xVals: IntArray): Precomp {
            val k = xVals.size
            val numerPolys = Array(k) { IntArray(1) { 1 } }
            val denomInv = IntArray(k)

            for (i in 0 until k) {
                var numer = intArrayOf(1) // poly 1
                var denom = 1
                val xi = xVals[i]
                for (j in 0 until k) {
                    if (j == i) continue
                    val xj = xVals[j]
                    // multiply numer by (x - xj) => coeffs [xj, 1] with + as XOR
                    numer = polyMul(numer, intArrayOf(xj, 1))
                    val diff = xi xor xj
                    denom = gfMul(denom, diff)
                }
                numerPolys[i] = numer
                require(denom != 0) { "duplicate x in precompute" }
                denomInv[i] = gfInverse(denom)
            }
            return Precomp(numerPolys, denomInv)
        }
    }

    // ---- block planning: split Ktotal,Ptotal into blocks where each block k_i + p_i <= 255 ----
    private fun planBlocks() {
        val K = Ktotal
        val P = Ptotal
        if (K + P <= 255) {
            blocks = mutableListOf(Block(K, P, K + P))
            encodeCache = arrayOfNulls(1)
            return
        }
        // find minimal B s.t. distributing K and P into B blocks yields all k_i + p_i <= 255
        var B = ceil((K + P) / 255.0).toInt()
        while (true) {
            val baseK = floor(K / B.toDouble()).toInt()
            val remK = K % B
            val baseP = floor(P / B.toDouble()).toInt()
            val remP = P % B
            val candidate = ArrayList<Block>(B)
            var ok = true
            for (i in 0 until B) {
                val ki = baseK + if (i < remK) 1 else 0
                val pi = baseP + if (i < remP) 1 else 0
                if (ki + pi > 255) { ok = false; break }
                candidate.add(Block(ki, pi, ki + pi))
            }
            if (ok) {
                // If any block has k=0 (possible when K < B), remove empty blocks
                blocks = candidate.filter { it.k > 0 || it.p > 0 }.toMutableList()
                encodeCache = arrayOfNulls(blocks.size)
                return
            }
            B++
            require(B <= K + P) { "Cannot plan blocks (unexpected)" }
        }
    }

    // ---- encode full dataPackets (length Ktotal) into codeword (Ktotal+Ptotal) sequentially ----
    fun encode(dataPackets: List<Packet>): List<ByteArray> {
        require(dataPackets.size == Ktotal) { "encode expects exactly K=$Ktotal data packets" }
        if (Ktotal == 0) return emptyList()

        val pktLen = dataPackets.first().size
        dataPackets.forEach { require(it.size == pktLen) { "inconsistent packet sizes" } }

        val out = ArrayList<ByteArray>(Ktotal + Ptotal)

        var dataCursor = 0
        for (b in blocks.indices) {
            val (k, p, /*n*/) = blocks[b]

            // collect block data arrays
            val blockData = Array(k) { i -> dataPackets[dataCursor + i] }
            dataCursor += k

            // push systematic data
            repeat(k) { i -> out.add(blockData[i].copyOf()) }

            if (p == 0) continue

            // prepare or retrieve encode cache for this block
            if (encodeCache[b] == null) {
                val xVals = IntArray(k) { i -> evalPoint(i) } // alpha^0 .. alpha^{k-1}
                val pre = precomputeLagrange(xVals)
                encodeCache[b] = EncodeCache(xVals, pre.numerPolys, pre.denomInv)
            }
            val cache = encodeCache[b]!!

            // We'll compute parity p arrays
            val parityArr = Array(p) { ByteArray(pktLen) }

            for (bytePos in 0 until pktLen) {
                val yVals = IntArray(k) { i -> blockData[i][bytePos].toInt() and 0xFF }
                // For each i, compute scale = yi * denomInv[i]
                val scales = IntArray(k) { i ->
                    val yi = yVals[i]
                    if (yi == 0) 0 else gfMul(yi, cache.denomInv[i])
                }
                // For each parity j, evaluate sum_i scales[i] * numerPolys[i](x_parity)
                for (j in 0 until p) {
                    val xParity = evalPoint(k + j) // alpha^{k+j}
                    var acc = 0
                    for (i in 0 until k) {
                        val s = scales[i]
                        if (s == 0) continue
                        val valNP = polyEval(cache.numerPolys[i], xParity)
                        acc = acc xor gfMul(valNP, s)
                    }
                    parityArr[j][bytePos] = acc.toByte()
                }
            }
            repeat(p) { j -> out.add(parityArr[j]) }
        }

        return out
    }

    // ---- decode full sequence of length Ktotal+Ptotal with nulls ----
    // receivedPackets length must equal Ktotal+Ptotal (global layout by blocks)
    @SuppressLint("NewApi")
    fun decode(
        receivedPackets: List<Packet?>,
        missingIndices: IntArray = intArrayOf() // accepted but unused (parity with TS)
    ): List<Packet> {
        val Ntotal = Ktotal + Ptotal
        require(receivedPackets.size >= Ntotal) {
            "decode expects an array of length $Ntotal (got ${receivedPackets.size})"
            Log.d("ReedSolomon", "decode expects an array of length $Ntotal (got ${receivedPackets.size})")
        }

        // infer pktLen from any non-null
        val pktLen = receivedPackets.firstOrNull { it != null }?.size
            ?: error("no packets available to infer packet length")

        val outData = ArrayList<ByteArray>(Ktotal)
        var globalIdx = 0
        var outCursor = 0

        for (b in blocks.indices) {
            val (k, p, n) = blocks[b]

            // slice of receivedPackets for this block
            val blockSlice = Array(n) { i -> receivedPackets[globalIdx + i] }

            // count available
            val availableIdx = ArrayList<Int>(n)
            val availablePackets = ArrayList<ByteArray>(n)
            for (i in 0 until n) {
                val pk = blockSlice[i]
                if (pk != null) {
                    require(pk.size == pktLen) {
                        Log.d("ReedSolomon", "inconsistent packet lengths")
                        "inconsistent packet lengths"
                    }
                    availableIdx.add(i)
                    availablePackets.add(pk)
                }
            }
            require(availablePackets.size >= k) {
                Log.d("ReedSolomon", "Not enough packets to decode block $b (need $k, got ${availablePackets.size})")
                "Not enough packets to decode block $b (need $k, got ${availablePackets.size})"

            }

            // pick first k available indices for interpolation
            val useIdx = availableIdx.take(k)
            val usePackets = availablePackets.take(k)

            // prepare decode cache key
            val key = "$b:${useIdx.joinToString(",")}"
            val cacheEntry = decodeCache.computeIfAbsent(key) {
                val xVals = IntArray(k) { i -> evalPoint(useIdx[i]) }
                val pre = precomputeLagrange(xVals)
                EncodeCache(xVals, pre.numerPolys, pre.denomInv)
            }

            // Now for each byte position, interpolate and evaluate at systematic positions 0..k-1
            val messageBlock = Array(k) { ByteArray(pktLen) }

            for (bytePos in 0 until pktLen) {
                // build yVals for used points
                val yVals = IntArray(k) { i -> usePackets[i][bytePos].toInt() and 0xFF }
                // compute scales = yi * denomInv[i]
                val scales = IntArray(k) { i ->
                    val yi = yVals[i]
                    if (yi == 0) 0 else gfMul(yi, cacheEntry.denomInv[i])
                }
                // compute coefficients polynomial (result) as accumulation of scaled numerPolys
                var coeffs: MutableList<Int> = mutableListOf(0)
                for (i in 0 until k) {
                    val s = scales[i]
                    if (s == 0) continue
                    val scaled = polyScale(cacheEntry.numerPolys[i], s)
                    polyAddTo(coeffs, scaled)
                }
                // now evaluate coeffs at x = alpha^{0..k-1}
                val coeffArray = coeffs.toIntArray()
                for (di in 0 until k) {
                    val x = evalPoint(di)
                    val v = polyEval(coeffArray, x)
                    messageBlock[di][bytePos] = v.toByte()
                }
            }

            // push messageBlock into outData
            repeat(k) { i ->
                if (outData.size <= outCursor) outData.add(messageBlock[i]) else outData[outCursor] = messageBlock[i]
                outCursor++
            }
            globalIdx += n
        }

        return outData
    }
}