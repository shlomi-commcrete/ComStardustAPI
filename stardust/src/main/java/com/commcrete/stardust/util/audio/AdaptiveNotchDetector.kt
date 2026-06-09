package com.commcrete.stardust.util.audio

import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import timber.log.Timber

/**
 * Per-chunk FFT-based tonal interference detector. Companion to [NotchFilter]:
 * analyzes a chunk, identifies narrowband peaks (mains hum harmonics,
 * switching-PSU spurs, device whistles), and returns the new band list the
 * NotchFilter should apply right now. Designed to be paired with the
 * static-notch config so the two compose (static for known frequencies,
 * adaptive for unknown / drifting / device-specific tones).
 *
 * Three detection paths run on every chunk:
 *
 *  1. **Harmonic-series scan** — for each fundamental in
 *     [NotchConfig.AdaptiveSettings.harmonicFundamentals], probes bins at
 *     n·f₀ for n = 1..harmonicMaxOrder. If at least
 *     [NotchConfig.AdaptiveSettings.harmonicMinCount] bins look like prominent
 *     peaks, ALL hits join the detection set — including ones inside the
 *     voice band. Voice formants don't form integer harmonic series at
 *     mains frequencies, so this is safe inside the voice band.
 *
 *  2. **Narrowband peak scan** — finds local maxima across the spectrum,
 *     classifies them as "tone" only when their Q (estimated from −3 dB
 *     bandwidth) exceeds [NotchConfig.AdaptiveSettings.qThreshold].
 *     Voice formants are wide (Q ≈ 5–30); tones are narrow (Q > 100), so
 *     a Q-80 threshold cleanly separates the two. Outside the voice band
 *     the Q check is skipped (formants don't live there).
 *
 *  3. **Silence-learned set** — when chunk RMS is below
 *     [NotchConfig.AdaptiveSettings.silenceRmsDbFs] there's no speech, so
 *     any prominent narrowband peak (even in the voice band) is recorded
 *     to the tracker. Catches arbitrary device whistles that don't match
 *     the harmonic-series pattern.
 *
 * Detections from all three paths feed a temporal tracker that:
 *  - buckets nearby frequencies (default ±5 Hz) so small drift doesn't
 *    fragment a single tone into multiple detections,
 *  - requires `stabilityChunks` consecutive hits before a bucket is
 *    "locked on" (prevents flicker on transient peaks),
 *  - keeps a locked-on bucket alive for `holdoverChunks` after it stops
 *    firing (so a brief silence in the tone doesn't drop the notch),
 *  - caps total simultaneous notches at `maxBands` (prefers buckets with
 *    longer holdover / more hits).
 *
 * Spectrum estimation uses Welch averaging over overlapping
 * [AudioDsp.FFT_SIZE]-sample windows with a Hann window. At 48 kHz with a
 * 500 ms chunk that's ~22 averages → smooth noise floor, clean peaks.
 *
 * The detector is **stateful** — keep a single instance per stream and call
 * [reset] on stream restart.
 */
internal class AdaptiveNotchDetector(
    private val sampleRateHz: Int,
    private val settings: NotchConfig.AdaptiveSettings,
) {

    private val fftSize: Int = AudioDsp.FFT_SIZE
    private val binHz: Float = sampleRateHz.toFloat() / fftSize.toFloat()
    private val bucketHz: Int = settings.bucketHz.coerceAtLeast(1)
    private val voiceLoBin: Int = (settings.voiceBandLowHz / binHz).toInt().coerceAtLeast(0)
    private val voiceHiBin: Int = (settings.voiceBandHighHz / binHz).toInt().coerceAtMost(fftSize / 2 - 1)

    /** Consecutive-hit counter per frequency bucket (for stability check). */
    private val hitCounter = HashMap<Int, Int>()
    /** Holdover counter per applied bucket (decays when bucket stops firing). */
    private val holdover = HashMap<Int, Int>()

    private var lastAppliedBuckets: Set<Int> = emptySet()
    private var lastAppliedBands: List<NotchFilter.Band> = emptyList()
    private var chunkIndex: Int = 0

    /** Drop all tracking state. Call on stream restart (PTT key down). */
    fun reset() {
        hitCounter.clear()
        holdover.clear()
        lastAppliedBuckets = emptySet()
        lastAppliedBands = emptyList()
        chunkIndex = 0
    }

    /**
     * Analyze [chunk] and return the new band list the notch filter should
     * apply. Returns `null` when the band set has not changed since the
     * previous call — caller can use that as a hint to skip the reconfigure
     * step (preserves biquad state).
     */
    fun detectAndTrack(chunk: ShortArray): List<NotchFilter.Band>? {
        chunkIndex++
        if (chunk.isEmpty()) return null

        val spec = computeWelchMagnitudeDb(chunk)
        val rmsDb = chunkRmsDbFs(chunk)
        val isSilence = rmsDb <= settings.silenceRmsDbFs

        val candidateBuckets = HashSet<Int>()

        // Path 1: harmonic-series scan
        for (fundamental in settings.harmonicFundamentals) {
            val hits = scanHarmonicSeries(spec, fundamental)
            if (hits.size >= settings.harmonicMinCount) {
                for (freq in hits) candidateBuckets.add(bucketFor(freq))
            }
        }

        // Path 2: narrowband peak scan (Q-filtered inside voice band)
        val peaks = findHighQPeaks(spec, allowVoiceBandWithoutQCheck = false)
        for (p in peaks) candidateBuckets.add(bucketFor(p))

        // Path 3: silence-learned set (no formants exist in silence)
        if (isSilence && settings.learnInVoiceBand) {
            val silencePeaks = findHighQPeaks(spec, allowVoiceBandWithoutQCheck = true)
            for (p in silencePeaks) candidateBuckets.add(bucketFor(p))
        }

        // Temporal tracking: bump hit counters, reset misses, manage holdover.
        val confirmed = HashSet<Int>()
        for (bucket in candidateBuckets) {
            val newCount = (hitCounter[bucket] ?: 0) + 1
            hitCounter[bucket] = newCount
            if (newCount >= settings.stabilityChunks) {
                confirmed.add(bucket)
                holdover[bucket] = settings.holdoverChunks
            }
        }
        // Decay hit counters for previously-tracked buckets that missed this chunk.
        val toClear = hitCounter.keys.filter { it !in candidateBuckets }
        for (b in toClear) hitCounter.remove(b)
        // Keep applied buckets alive via holdover decay.
        for (bucket in lastAppliedBuckets) {
            if (bucket !in candidateBuckets) {
                val rem = (holdover[bucket] ?: 0) - 1
                if (rem > 0) {
                    holdover[bucket] = rem
                    confirmed.add(bucket)
                } else {
                    holdover.remove(bucket)
                }
            }
        }

        // Cap at maxBands — prefer buckets with the longest holdover, then hit count.
        val ranked = confirmed.sortedByDescending {
            (holdover[it] ?: 0) * 1000 + (hitCounter[it] ?: 0)
        }
        val finalBuckets = ranked.take(settings.maxBands).toHashSet()

        val newBands = finalBuckets.sorted().map {
            NotchFilter.Band((it * bucketHz).toFloat(), settings.notchQ)
        }

        val changed = finalBuckets != lastAppliedBuckets
        if (changed) {
            lastAppliedBuckets = finalBuckets
            lastAppliedBands = newBands
            Timber.tag(TAG).d(
                "      ↳ adaptive notch (chunk #%03d, rms=%.1f dBFS%s): %d band(s) @ Q=%.0f → %s",
                chunkIndex, rmsDb,
                if (isSilence) " SILENCE" else "",
                newBands.size, settings.notchQ,
                if (newBands.isEmpty()) "(none)"
                else newBands.joinToString(",") { "${it.frequencyHz.toInt()}Hz" }
            )
        }
        return if (changed) newBands else null
    }

    /** Last bands handed to the notch filter (or empty if never applied). */
    fun lastAppliedBands(): List<NotchFilter.Band> = lastAppliedBands

    // ─── Spectrum estimation ────────────────────────────────────────────

    /**
     * Welch-averaged magnitude spectrum in dBFS. Uses 50 % overlap with the
     * fixed Hann window from [AudioDsp.hannWindow]. Falls back to a single
     * zero-padded FFT when [chunk] is shorter than [fftSize].
     */
    private fun computeWelchMagnitudeDb(chunk: ShortArray): FloatArray {
        val bins = fftSize / 2
        val accum = DoubleArray(bins)
        val win = AudioDsp.hannWindow
        val hop = fftSize / 2
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        var segs = 0
        var start = 0
        while (start + fftSize <= chunk.size) {
            for (i in 0 until fftSize) {
                re[i] = chunk[start + i] * win[i]
                im[i] = 0.0
            }
            AudioDsp.fftInPlace(re, im)
            for (k in 0 until bins) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                accum[k] += mag
            }
            segs++
            start += hop
        }
        if (segs == 0) {
            val n = chunk.size.coerceAtMost(fftSize)
            for (i in 0 until n) {
                re[i] = chunk[i] * win[i]
                im[i] = 0.0
            }
            for (i in n until fftSize) { re[i] = 0.0; im[i] = 0.0 }
            AudioDsp.fftInPlace(re, im)
            for (k in 0 until bins) {
                accum[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            }
            segs = 1
        }
        // Reference: Short.MAX_VALUE × Hann window energy ≈ FFT_SIZE / 4.
        val ref = Short.MAX_VALUE.toDouble() * fftSize / 4.0
        val out = FloatArray(bins)
        for (k in 0 until bins) {
            val mag = (accum[k] / segs).coerceAtLeast(1e-12)
            out[k] = (20.0 * log10(mag / ref)).toFloat()
        }
        return out
    }

    // ─── Detection helpers ──────────────────────────────────────────────

    private fun bucketFor(hz: Float): Int = (hz / bucketHz).roundToInt()

    /** Scan bins at integer multiples of [fundamental]; return the Hz values that look like peaks. */
    private fun scanHarmonicSeries(spec: FloatArray, fundamental: Float): List<Float> {
        if (fundamental <= 0f) return emptyList()
        val nyquist = sampleRateHz / 2f
        val out = ArrayList<Float>()
        var n = 1
        while (n * fundamental < nyquist && n <= settings.harmonicMaxOrder) {
            val freq = n * fundamental
            if (isProminentPeakAt(spec, freq)) out.add(freq)
            n++
        }
        return out
    }

    /**
     * Walk every local maximum in the spectrum. Outside the voice band:
     * keep any peak meeting prominence + absolute level. Inside the voice
     * band: also require Q ≥ qThreshold (so formants don't get flagged),
     * unless [allowVoiceBandWithoutQCheck] (silence-learning mode).
     */
    private fun findHighQPeaks(
        spec: FloatArray,
        allowVoiceBandWithoutQCheck: Boolean,
    ): List<Float> {
        val results = ArrayList<Float>()
        val bins = spec.size
        for (k in 2 until bins - 2) {
            if (spec[k] < spec[k - 1] || spec[k] < spec[k + 1]) continue
            if (spec[k] == spec[k - 1] && spec[k] == spec[k + 1]) continue
            if (spec[k] < settings.minPeakDbFs) continue
            val medianDb = localMedian(spec, k, halfWidth = 20)
            if (spec[k] - medianDb < settings.prominenceDb) continue

            val inVoice = k in voiceLoBin..voiceHiBin
            if (inVoice && !allowVoiceBandWithoutQCheck) {
                val q = estimateQ(spec, k)
                if (q < settings.qThreshold) continue
            }
            results.add(k * binHz)
        }
        return results
    }

    /** True if the bin nearest [freq] (±2 bins for drift) looks like a prominent peak. */
    private fun isProminentPeakAt(spec: FloatArray, freq: Float): Boolean {
        val target = (freq / binHz).roundToInt().coerceIn(2, spec.size - 3)
        var maxK = target
        for (dk in -2..2) {
            val kk = (target + dk).coerceIn(2, spec.size - 3)
            if (spec[kk] > spec[maxK]) maxK = kk
        }
        if (spec[maxK] < settings.minPeakDbFs) return false
        val medianDb = localMedian(spec, maxK, halfWidth = 20)
        return (spec[maxK] - medianDb) >= settings.prominenceDb
    }

    /** Median magnitude in [k − halfWidth, k + halfWidth], excluding ±2 bins around [k]. */
    private fun localMedian(spec: FloatArray, k: Int, halfWidth: Int): Float {
        val lo = (k - halfWidth).coerceAtLeast(0)
        val hi = (k + halfWidth).coerceAtMost(spec.size - 1)
        val values = ArrayList<Float>(hi - lo + 1)
        for (i in lo..hi) {
            if (i in (k - 2)..(k + 2)) continue
            values.add(spec[i])
        }
        if (values.isEmpty()) return 0f
        values.sort()
        return values[values.size / 2]
    }

    /** Estimate Q = f_centre / bandwidth_−3dB around the peak at bin [k]. */
    private fun estimateQ(spec: FloatArray, k: Int): Float {
        val peakDb = spec[k]
        val target = peakDb - 3f
        var left = k
        while (left > 0 && spec[left] > target) left--
        var right = k
        while (right < spec.size - 1 && spec[right] > target) right++
        val bwBins = (right - left).coerceAtLeast(1)
        val bwHz = bwBins * binHz
        val centreHz = k * binHz
        return if (bwHz > 0f) centreHz / bwHz else Float.MAX_VALUE
    }

    private fun chunkRmsDbFs(chunk: ShortArray): Float {
        var sumSq = 0.0
        for (s in chunk) sumSq += s.toDouble() * s.toDouble()
        val rms = sqrt(sumSq / chunk.size).coerceAtLeast(1e-6)
        return (20.0 * log10(rms / Short.MAX_VALUE.toDouble())).toFloat()
    }

    private companion object {
        const val TAG = "AudioTestFeeder"
    }
}

