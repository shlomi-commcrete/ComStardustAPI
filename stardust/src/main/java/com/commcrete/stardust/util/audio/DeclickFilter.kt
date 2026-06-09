package com.commcrete.stardust.util.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Impulsive-noise removal filter — companion to [DeclickConfig]. Finds
 * sound-wave anomalies (clicks, pops, USB / BLE bit-error spikes, slope
 * discontinuities) and replaces them with smooth interpolation that joins
 * the surrounding clean wave **C¹-continuously** — no derivative jump at
 * the repair boundaries, so the result is a single continuous wave.
 *
 * **Detection** combines two paths (both compared against their own
 * rolling MAD, EMA-smoothed across chunks):
 *  - Amplitude deviation against a small median-filter reference.
 *  - First-difference jump `|x[n] − x[n−1]|` (when
 *    [DeclickConfig.useDerivativeDetection]) — catches "wave kinks" whose
 *    peak isn't necessarily large.
 *
 * **Region expansion** then walks left and right from each candidate run
 * while neighbouring samples are still ≥ half-threshold, so the repair
 * endpoints land on genuinely clean samples (no leftover overshoot).
 *
 * **Repair** is dispatched by run length:
 *  - Short runs (≤ [DeclickConfig.splineMaxLength]) → Hermite cubic
 *    spline with endpoint tangents from CLEAN samples only.
 *  - Longer runs (≤ [DeclickConfig.maxTickSamples]) → linear interior
 *    with a short Hann edge crossfade that restores tangent continuity
 *    at both boundaries.
 *  - Above [DeclickConfig.maxTickSamples] → left untouched (treated as
 *    legitimate audio loss).
 *
 * Stateful — keep a single instance per stream and call [reset] on stream
 * restart (e.g. PTT key-down). Per-chunk processing is in place; the list
 * of detected ticks is exposed via [lastDetections] for logging.
 *
 * See [DeclickConfig] for algorithm details and tuning knobs.
 */
internal class DeclickFilter(
    @Suppress("unused") sampleRateHz: Int,
    private val config: DeclickConfig,
) {

    /** Description of one detected/repaired tick. */
    data class TickEvent(
        /** Index of the first repaired sample within the chunk it was found in. */
        val positionInChunk: Int,
        /** Run length in samples (number of contiguous repaired samples). */
        val lengthSamples: Int,
        /** Peak absolute value (raw int16 magnitude) among the repaired run. */
        val peakAbs: Int,
        /** Repair method used. */
        val repair: RepairMethod,
    )

    enum class RepairMethod { SPLINE, LINEAR, SKIPPED_TOO_LONG }

    // ─── State ──────────────────────────────────────────────────────────

    /** Smoothed MAD of per-sample amplitude deviation (across chunks). */
    private var rollingMad: Float = INITIAL_MAD_FLOOR
    /** Smoothed MAD of per-sample first-difference jumps (across chunks). */
    private var rollingMadJump: Float = INITIAL_MAD_FLOOR
    private var totalTicksFound: Long = 0
    private var totalSamplesSeen: Long = 0
    private var lastDetections: List<TickEvent> = emptyList()

    /** Total ticks repaired since [reset]. */
    fun totalTicks(): Long = totalTicksFound

    /** Ticks repaired in the most recent [processInPlace] call. */
    fun lastDetections(): List<TickEvent> = lastDetections

    fun reset() {
        rollingMad = INITIAL_MAD_FLOOR
        rollingMadJump = INITIAL_MAD_FLOOR
        totalTicksFound = 0
        totalSamplesSeen = 0
        lastDetections = emptyList()
    }

    // ─── Hot path ───────────────────────────────────────────────────────

    /**
     * Detect + repair ticks in [chunk] in place. Returns the list of
     * detected ticks (also available via [lastDetections]).
     *
     * Pipeline per chunk:
     *  1. Compute per-sample amplitude deviation (against a small median
     *     window) and per-sample first-difference jump.
     *  2. Update rolling MADs (EMA-blended across chunks) for both.
     *  3. Walk the interior — a sample is a *candidate* when EITHER
     *     `dev > thresholdMad·MAD` OR `jump > thresholdMad·MAD_jump`
     *     AND `|x| ≥ minPeakLin`.
     *  4. Group contiguous candidates into a run, then **expand** the run
     *     left and right while neighbours are still ≥ half-threshold —
     *     catches the overshoot/ringing tail so repair endpoints land on
     *     genuinely clean samples.
     *  5. Repair: Hermite (≤ splineMaxLength) or linear+edge-crossfade
     *     (≤ maxTickSamples). Both produce C¹-continuous output at the
     *     boundaries — the wave is continuous through the repair.
     */
    fun processInPlace(chunk: ShortArray): List<TickEvent> {
        val halfMed = config.medianWindow / 2
        val minPeakLin = max(
            1,
            (Short.MAX_VALUE.toFloat() * 10f.pow(config.minPeakDbFs / 20f)).toInt(),
        )

        if (chunk.size < config.medianWindow + 2) {
            lastDetections = emptyList()
            return lastDetections
        }

        // 1a. Per-sample amplitude deviation against a small median window.
        //     Only the interior [halfMed, size − halfMed) is analyzed —
        //     a handful of boundary samples per chunk are unprocessed
        //     (< 0.01 % at 48 kHz / 500 ms chunks).
        val deviations = FloatArray(chunk.size)
        val medianBuf = FloatArray(config.medianWindow)
        val lo = halfMed
        val hi = chunk.size - halfMed
        for (n in lo until hi) {
            for (k in 0 until config.medianWindow) {
                medianBuf[k] = chunk[n - halfMed + k].toFloat()
            }
            // Small N (5-9) → insertion sort is fastest.
            insertionSortSmall(medianBuf)
            val med = medianBuf[config.medianWindow / 2]
            deviations[n] = abs(chunk[n].toFloat() - med)
        }

        // 1b. Per-sample first-difference jump |x[n] − x[n−1]|.
        //     Catches wave kinks (slope discontinuities) — the signature
        //     of a click in the time domain even when its peak is small.
        val jumps = FloatArray(chunk.size)
        if (config.useDerivativeDetection) {
            for (n in 1 until chunk.size) {
                jumps[n] = abs(chunk[n].toFloat() - chunk[n - 1].toFloat())
            }
        }

        // 2. Update rolling MADs (EMA-blended across chunks). Use
        //    Arrays.sort (dual-pivot quicksort, O(N log N)) for chunk-sized
        //    arrays — insertion sort would be O(N²) on 12-24k samples.
        val devCopy = deviations.copyOfRange(lo, hi)
        devCopy.sort()
        val chunkMad = if (devCopy.isNotEmpty()) devCopy[devCopy.size / 2] else rollingMad
        val alpha = (devCopy.size.toFloat() / (devCopy.size + config.madSmoothingSamples.toFloat()))
            .coerceIn(0f, 1f)
        rollingMad = (1f - alpha) * rollingMad + alpha * max(chunkMad, INITIAL_MAD_FLOOR)
        val devThreshold = config.thresholdMad * rollingMad
        val devExpandThreshold = devThreshold * config.expansionFraction

        var jumpThreshold = Float.MAX_VALUE
        var jumpExpandThreshold = Float.MAX_VALUE
        if (config.useDerivativeDetection) {
            val jumpsCopy = jumps.copyOfRange(lo, hi)
            jumpsCopy.sort()
            val chunkMadJump = if (jumpsCopy.isNotEmpty()) jumpsCopy[jumpsCopy.size / 2] else rollingMadJump
            rollingMadJump = (1f - alpha) * rollingMadJump + alpha * max(chunkMadJump, INITIAL_MAD_FLOOR)
            jumpThreshold = config.thresholdMad * rollingMadJump
            jumpExpandThreshold = jumpThreshold * config.expansionFraction
        }

        // 3. Walk the interior, find candidate runs, expand them, repair.
        val events = ArrayList<TickEvent>()
        var n = lo
        while (n < hi) {
            val hit = (deviations[n] > devThreshold || jumps[n] > jumpThreshold) &&
                abs(chunk[n].toInt()) >= minPeakLin
            if (hit) {
                // Find the right end of the strict-threshold run.
                var start = n
                var end = n + 1
                while (end < hi && (deviations[end] > devThreshold || jumps[end] > jumpThreshold)) end++

                // Expand left/right at half-threshold to swallow the
                // overshoot/ringing tail of the anomaly so the repair
                // endpoints land on genuinely clean samples.
                if (config.expansionFraction > 0f) {
                    while (start > lo &&
                        (deviations[start - 1] > devExpandThreshold ||
                            jumps[start - 1] > jumpExpandThreshold)
                    ) {
                        start -= 1
                    }
                    while (end < hi &&
                        (deviations[end] > devExpandThreshold ||
                            jumps[end] > jumpExpandThreshold)
                    ) {
                        end += 1
                    }
                }

                val runLength = end - start
                val peakAbs = (start until end).maxOf { abs(chunk[it].toInt()) }

                val method = when {
                    runLength > config.maxTickSamples -> RepairMethod.SKIPPED_TOO_LONG
                    runLength <= config.splineMaxLength -> RepairMethod.SPLINE
                    else -> RepairMethod.LINEAR
                }
                when (method) {
                    RepairMethod.SPLINE -> hermiteRepair(chunk, start, end)
                    RepairMethod.LINEAR -> linearRepairWithEdgeCrossfade(chunk, start, end)
                    RepairMethod.SKIPPED_TOO_LONG -> Unit
                }
                if (method != RepairMethod.SKIPPED_TOO_LONG) totalTicksFound++

                events.add(TickEvent(start, runLength, peakAbs, method))
                n = end
            } else {
                n++
            }
        }

        totalSamplesSeen += chunk.size
        lastDetections = events
        return events
    }

    // ─── Repair primitives ──────────────────────────────────────────────

    /**
     * **Cubic Hermite spline** repair: fills `[start, end)` with a smooth
     * curve that:
     *  - passes through `x[start-1]` and `x[end]` (clean endpoints), AND
     *  - matches the local slope at each endpoint computed from CLEAN
     *    samples on the same side of the gap (does not cross the tick
     *    when estimating tangents — the Catmull-Rom approach the original
     *    used pulled the curve toward the post-tick sample via P0..P3,
     *    which produced a kink whenever the tick was wide).
     *
     * Result: the repaired region is **C¹-continuous** with the clean
     * audio at both boundaries — the wave is genuinely continuous through
     * the repair (no derivative discontinuity, no audible kink).
     *
     * Tangent in τ-space (where τ ∈ [0, 1] over the L-sample gap) =
     * `slope_per_sample × (L + 1)` because moving by τ = 1 covers L + 1
     * sample-steps of the discrete signal.
     */
    private fun hermiteRepair(chunk: ShortArray, start: Int, end: Int) {
        val n = chunk.size
        val p0 = chunk[(start - 1).coerceIn(0, n - 1)].toFloat()
        val p1 = chunk[end.coerceIn(0, n - 1)].toFloat()
        // Slopes computed strictly from the clean side — do NOT cross the
        // gap. Falls back to 0 at chunk edges where there isn't enough
        // context.
        val m0PerSample = if (start - 2 >= 0) p0 - chunk[start - 2].toFloat() else 0f
        val m1PerSample = if (end + 1 < n) chunk[end + 1].toFloat() - p1 else 0f

        val L = end - start
        val m0Tau = m0PerSample * (L + 1).toFloat()
        val m1Tau = m1PerSample * (L + 1).toFloat()

        for (i in 0 until L) {
            val tau = (i + 1).toFloat() / (L + 1).toFloat()
            val tau2 = tau * tau
            val tau3 = tau2 * tau
            // Cubic Hermite basis functions.
            val h00 = 2f * tau3 - 3f * tau2 + 1f
            val h10 = tau3 - 2f * tau2 + tau
            val h01 = -2f * tau3 + 3f * tau2
            val h11 = tau3 - tau2
            val v = h00 * p0 + h10 * m0Tau + h01 * p1 + h11 * m1Tau
            chunk[start + i] = v.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Linear interpolation between `x[start-1]` and `x[end]`, **plus** a
     * short Hann edge crossfade at both boundaries that blends the linear
     * interior into a clean-extrapolated tangent for the first / last
     * few samples of the repaired region.
     *
     * The crossfade makes the repair C¹-continuous at the boundaries
     * (matches the local slope from the clean side) while keeping the
     * safe straight-line behaviour in the interior — avoids the Hermite
     * overshoot risk that grows with gap length.
     *
     * For very short gaps (< `2 × EDGE_CROSSFADE_SAMPLES`) the crossfade
     * is automatically reduced so the two edge bands don't overlap; in
     * practice such short gaps go through [hermiteRepair] anyway.
     */
    private fun linearRepairWithEdgeCrossfade(chunk: ShortArray, start: Int, end: Int) {
        val n = chunk.size
        val left = chunk[(start - 1).coerceIn(0, n - 1)].toFloat()
        val right = chunk[end.coerceIn(0, n - 1)].toFloat()
        val length = end - start

        // Phase 1: plain linear fill.
        for (i in 0 until length) {
            val t = (i + 1).toFloat() / (length + 1).toFloat()
            val v = left * (1f - t) + right * t
            chunk[start + i] = v.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Phase 2: Hann edge crossfade. Replace the first / last fadeLen
        // samples with a Hann-weighted blend of (a) the linear value
        // already written and (b) a clean-extrapolated tangent line that
        // continues the slope of the clean side.
        val fadeLen = EDGE_CROSSFADE_SAMPLES.coerceAtMost(length / 2).coerceAtLeast(0)
        if (fadeLen == 0) return

        // ── Left edge — extrapolate clean slope FORWARD from x[start-1].
        val leftSlope = if (start - 2 >= 0) left - chunk[start - 2].toFloat() else 0f
        for (i in 0 until fadeLen) {
            // w decays from ~1 (at i=0) toward ~0 (at i=fadeLen-1): keep
            // clean curve fully at the boundary, fade into linear interior.
            val w = 0.5f + 0.5f * kotlin.math.cos(
                Math.PI * (i + 1).toDouble() / (fadeLen + 1).toDouble()
            ).toFloat()
            val cleanExt = left + leftSlope * (i + 1).toFloat()
            val linearVal = chunk[start + i].toFloat()
            val v = w * cleanExt + (1f - w) * linearVal
            chunk[start + i] = v.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // ── Right edge — extrapolate clean slope BACKWARD from x[end].
        val rightSlope = if (end + 1 < n) chunk[end + 1].toFloat() - right else 0f
        for (i in 0 until fadeLen) {
            val sampleIdx = end - 1 - i
            val w = 0.5f + 0.5f * kotlin.math.cos(
                Math.PI * (i + 1).toDouble() / (fadeLen + 1).toDouble()
            ).toFloat()
            // Walk backwards from x[end] using the post-tick slope.
            val cleanExt = right - rightSlope * (i + 1).toFloat()
            val linearVal = chunk[sampleIdx].toFloat()
            val v = w * cleanExt + (1f - w) * linearVal
            chunk[sampleIdx] = v.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Insertion sort for small (5–9 element) arrays. Faster than Arrays.sort at this size. */
    private fun insertionSortSmall(a: FloatArray) {
        for (i in 1 until a.size) {
            val key = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > key) {
                a[j + 1] = a[j]
                j--
            }
            a[j + 1] = key
        }
    }

    private companion object {
        /** Initial / floor MAD value before any signal is observed. */
        const val INITIAL_MAD_FLOOR = 30f
        /**
         * Hann crossfade length at each edge of a linear-repaired region.
         * 4 samples ≈ 80 µs at 48 kHz / 170 µs at 24 kHz — enough to make
         * the slope transition C¹-continuous without smearing the interior.
         */
        const val EDGE_CROSSFADE_SAMPLES = 4
    }
}


