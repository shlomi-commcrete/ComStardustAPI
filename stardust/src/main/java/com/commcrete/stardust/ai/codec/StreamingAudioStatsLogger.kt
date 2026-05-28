package com.commcrete.stardust.ai.codec

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Live-stream companion to `AudioTestFeeder` — logs all the diagnostics that
 * matter for AI ingestion (WavTokenizer in particular) directly from the
 * `AudioRecord` pipeline:
 *
 *  1. **On start** — captured input device identity (so you can confirm the
 *     jbox PCM2900C is actually the source, not the phone mic), configured
 *     sample rate / channels / bit depth, and the device's advertised native
 *     rates.
 *  2. **Per chunk (every 500 ms)** — compact one-liner with peak / RMS dBFS,
 *     clipping %, silence %, elapsed time. Lets you see gain drift, dropouts,
 *     and pumping in real time.
 *  3. **Periodic spectral snapshot (every 5 s)** — energy distribution across
 *     the 5 standard WavTokenizer bands and the dominant frequency, computed
 *     over a sliding ring buffer of the last 10 s of audio. Catches things
 *     like jbox "piiii" whines and bandwidth-limited capture as they happen.
 *  4. **On stop** — comprehensive summary with cumulative stats (peak, RMS,
 *     DC, effective bits, longest zero/repeat-run, clip ratio), a final
 *     spectral fingerprint with histogram bars, and an explicit
 *     WavTokenizer-suitability verdict (GOOD / OK / POOR with reasons).
 *
 * The logger is allocation-light during the read loop — heavy work (FFT,
 * formatted summary) only runs on the periodic-snapshot tick and on stop.
 *
 * Tagged `AudioStream` for easy `adb logcat -s AudioStream` filtering.
 */
class StreamingAudioStatsLogger(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
) {
    companion object {
        private const val TAG = "AudioStream"
        private const val FFT_SIZE = 2048
        private const val SILENCE_THRESHOLD = 200          // |x| < this → "silent" sample
        private const val CLIP_THRESHOLD = 32_700          // |x| ≥ this → "clipped" sample
        private val BAND_EDGES_HZ = doubleArrayOf(0.0, 300.0, 1_000.0, 3_400.0, 8_000.0, 12_000.0)
        private val BAND_LABELS = arrayOf(
            "0–300 Hz", "300–1k", "1k–3.4k", "3.4k–8k", "8k–12k"
        )
        /** Emit a spectral snapshot every N chunks. 10 × 500 ms = every 5 s. */
        private const val SNAPSHOT_EVERY_N_CHUNKS = 10
    }

    // ── Captured device identity ─────────────────────────────────────────
    private var deviceName: String = "unknown"
    private var deviceType: Int = -1
    private var deviceRates: List<Int> = emptyList()

    // ── Cumulative stream stats ──────────────────────────────────────────
    private var totalSamples: Long = 0
    private var peakAbs: Int = 0
    private var sumSq: Double = 0.0
    private var sumDc: Double = 0.0
    private var clippedCount: Long = 0
    private var silentCount: Long = 0
    private var zeroCrossings: Long = 0
    private var lsbMask: Int = 0
    private var longestZeroRun: Int = 0
    private var curZeroRun: Int = 0
    private var longestRepeatRun: Int = 0
    private var curRepeatRun: Int = 1
    private var prevSample: Short = 0
    private var firstSampleSeen: Boolean = false

    // ── Per-chunk stats (reset each chunk) ───────────────────────────────
    private var chunkPeak: Int = 0
    private var chunkSumSq: Double = 0.0
    private var chunkSilent: Int = 0
    private var chunkClipped: Int = 0
    private var chunkSamples: Int = 0

    // ── Read-timing stats ────────────────────────────────────────────────
    private var bufferReads: Long = 0
    private var lastReadNs: Long = 0
    private var totalGapNs: Long = 0
    private var maxGapNs: Long = 0

    // ── Ring buffer for spectral snapshots (~10 s of audio) ──────────────
    private val ringSize: Int = sampleRate * 10
    private val ring: ShortArray = ShortArray(ringSize)
    private var ringWriteIndex: Int = 0
    private var ringFilled: Boolean = false

    // ── Lazy Hann window (built first time FFT runs) ─────────────────────
    private val hannWindow: DoubleArray by lazy {
        DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1)) }
    }

    private var startTimeMs: Long = 0
    private var stopped: Boolean = false
    private var lastChunkIndexLogged: Int = -1

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Call once after [AudioRecord] is configured (and after [setPreferredDevice]
     * has been called if you're routing to USB).
     */
    fun onStart(deviceName: String?, deviceType: Int, deviceRates: List<Int>) {
        this.deviceName = deviceName ?: "default-mic"
        this.deviceType = deviceType
        this.deviceRates = deviceRates
        startTimeMs = System.currentTimeMillis()

        Timber.tag(TAG).i(
            "▶ Stream started → device='%s' (type=%s), configured=%d Hz mono(%d) %d-bit",
            this.deviceName, deviceTypeName(deviceType), sampleRate, channels, bitsPerSample
        )
        if (deviceRates.isNotEmpty()) {
            Timber.tag(TAG).i(
                "  device native rates=%s, configured rate=%d Hz%s",
                deviceRates, sampleRate,
                if (deviceRates.isNotEmpty() && sampleRate !in deviceRates)
                    " (Android HAL will resample)"
                else ""
            )
        }
    }

    /**
     * Call inside the read loop, right after `audioRecord.read(buf, 0, len)`.
     * Pass the same buffer and the same `read` count.
     */
    fun onBufferRead(buffer: ShortArray, count: Int) {
        if (count <= 0) return
        bufferReads++

        val now = System.nanoTime()
        if (lastReadNs > 0) {
            val gap = now - lastReadNs
            totalGapNs += gap
            if (gap > maxGapNs) maxGapNs = gap
        }
        lastReadNs = now

        var i = 0
        while (i < count) {
            val s = buffer[i]
            val v = s.toInt()
            val a = if (v < 0) -v else v
            if (a > peakAbs) peakAbs = a
            if (a > chunkPeak) chunkPeak = a
            val vd = v.toDouble()
            sumSq += vd * vd
            chunkSumSq += vd * vd
            sumDc += vd
            lsbMask = lsbMask or (v and 0xFFFF)

            if (a < SILENCE_THRESHOLD) { silentCount++; chunkSilent++ }
            if (a >= CLIP_THRESHOLD)   { clippedCount++; chunkClipped++ }

            if (firstSampleSeen) {
                if ((prevSample.toInt() xor v) < 0) zeroCrossings++
                if (s == prevSample && v != 0) {
                    curRepeatRun++
                } else {
                    if (curRepeatRun > longestRepeatRun) longestRepeatRun = curRepeatRun
                    curRepeatRun = 1
                }
            }
            if (v == 0) {
                curZeroRun++
                if (curZeroRun > longestZeroRun) longestZeroRun = curZeroRun
            } else curZeroRun = 0
            prevSample = s
            firstSampleSeen = true

            // Write into the ring (last 10 s for periodic spectral snapshot).
            ring[ringWriteIndex] = s
            ringWriteIndex++
            if (ringWriteIndex >= ringSize) {
                ringWriteIndex = 0
                ringFilled = true
            }

            totalSamples++
            chunkSamples++
            i++
        }
    }

    /**
     * Call each time a full 500 ms chunk has been assembled (i.e. right before /
     * after `onChunkReady?.invoke(...)`). The first call is at `chunkIndex = 1`.
     */
    fun onChunkCompleted(chunkIndex: Int) {
        if (chunkSamples == 0) return
        val rms = sqrt(chunkSumSq / chunkSamples)
        val peakDb = toDbFs(chunkPeak.toDouble())
        val rmsDb = toDbFs(rms)
        val clipPct = chunkClipped * 100.0 / chunkSamples
        val silentPct = chunkSilent * 100.0 / chunkSamples
        val elapsedSec = totalSamples.toDouble() / sampleRate

        Timber.tag(TAG).i(
            "chunk=%-3d t=%6.1fs peak=%+6.1fdBFS rms=%+6.1fdBFS clip=%5.2f%% silent=%5.1f%%%s",
            chunkIndex, elapsedSec, peakDb, rmsDb, clipPct, silentPct,
            chunkWarnings(peakDb, rmsDb, clipPct, silentPct)
        )

        // Periodic spectral snapshot (every 5 s by default).
        if (chunkIndex > 0 && chunkIndex != lastChunkIndexLogged &&
            chunkIndex % SNAPSHOT_EVERY_N_CHUNKS == 0
        ) {
            lastChunkIndexLogged = chunkIndex
            logSpectralSnapshot(chunkIndex)
        }

        // Reset per-chunk accumulators.
        chunkPeak = 0
        chunkSumSq = 0.0
        chunkSilent = 0
        chunkClipped = 0
        chunkSamples = 0
    }

    /**
     * Call from the recorder's `finally` block. Emits the comprehensive summary.
     * Idempotent.
     */
    fun onStop() {
        if (stopped) return
        stopped = true
        logFinalSummary()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal — formatting
    // ─────────────────────────────────────────────────────────────────────

    private fun chunkWarnings(peakDb: Double, rmsDb: Double, clipPct: Double, silentPct: Double): String {
        val flags = mutableListOf<String>()
        if (peakDb > -0.5)      flags += "⚠clip"
        if (rmsDb < -32.0)      flags += "⚠quiet"
        if (rmsDb > -6.0)       flags += "⚠hot"
        if (silentPct > 90.0)   flags += "⚠silence"
        return if (flags.isEmpty()) "" else "  " + flags.joinToString(" ")
    }

    private fun logSpectralSnapshot(chunkIndex: Int) {
        val pcm = snapshotPcm() ?: return
        val a = analyseSpectrum(pcm)
        val sb = StringBuilder()
        sb.append("─ spectral snapshot @ chunk=").append(chunkIndex).append(": ")
        for (i in BAND_LABELS.indices) {
            sb.append(BAND_LABELS[i]).append('=')
                .append(String.format(java.util.Locale.US, "%.1f%%", a.bands[i]))
            if (i < BAND_LABELS.size - 1) sb.append("  ")
        }
        sb.append("  | peak=")
            .append(String.format(java.util.Locale.US, "%.0fHz", a.dominantFreqHz))
            .append(" (")
            .append(String.format(java.util.Locale.US, "%.1f%%", a.dominantPct))
            .append(", flatness=")
            .append(String.format(java.util.Locale.US, "%.2f", a.flatness))
            .append(')')
        if (a.toneAlert != null) sb.append("  ⚠ ").append(a.toneAlert)
        Timber.tag(TAG).i(sb.toString())
    }

    private fun logFinalSummary() {
        val durationMs = System.currentTimeMillis() - startTimeMs
        if (totalSamples == 0L) {
            Timber.tag(TAG).w("⏹ Stream stopped: no samples captured (duration=%d ms)", durationMs)
            return
        }
        val rms = sqrt(sumSq / totalSamples)
        val dc = sumDc / totalSamples
        val peakDb = toDbFs(peakAbs.toDouble())
        val rmsDb = toDbFs(rms)
        val zcr = zeroCrossings.toDouble() / totalSamples
        val clipPct = clippedCount * 100.0 / totalSamples
        val silentPct = silentCount * 100.0 / totalSamples
        val effectiveBits = effectiveBitsFromMask(lsbMask)
        val longestZeroMs = longestZeroRun.toLong() * 1000 / sampleRate
        val longestRepeatMs = longestRepeatRun.toLong() * 1000 / sampleRate
        val avgGapMs = if (bufferReads > 1) totalGapNs / 1e6 / (bufferReads - 1) else 0.0
        val maxGapMs = maxGapNs / 1e6
        val avgSamplesPerRead = if (bufferReads > 0) totalSamples / bufferReads else 0

        val pcm = snapshotPcm()
        val spec = pcm?.let { analyseSpectrum(it) }
        val verdict = suitabilityVerdict(rmsDb, peakDb, clipPct, silentPct, effectiveBits,
            longestRepeatMs.toInt(), spec)

        val sb = StringBuilder()
        sb.append("\n╭─ Live audio stream stats (").append(deviceName).append(") ──────\n")
        sb.append("│ source device     : ").append(deviceName).append(" (").append(deviceTypeName(deviceType)).append(")\n")
        sb.append("│ configured        : ").append(sampleRate).append(" Hz mono(").append(channels).append(") ")
            .append(bitsPerSample).append("-bit\n")
        if (deviceRates.isNotEmpty())
            sb.append("│ device native     : rates=").append(deviceRates)
                .append(if (sampleRate !in deviceRates) "  (Android HAL resampled)" else "  ✓ no internal resample").append('\n')
        sb.append("│ duration          : ").append(durationMs).append(" ms ")
            .append("(").append(totalSamples).append(" samples = ")
            .append(String.format(java.util.Locale.US, "%.2f s", totalSamples / sampleRate.toDouble())).append(")\n")
        sb.append("│ buffer reads      : ").append(bufferReads)
            .append(" (avg ").append(avgSamplesPerRead).append(" samples/read, ")
            .append(String.format(java.util.Locale.US, "avg gap %.2f ms, max %.2f ms", avgGapMs, maxGapMs))
            .append(")\n")
        sb.append("│ peak amplitude    : ").append(peakAbs)
            .append(String.format(java.util.Locale.US, "  (%+.2f dBFS)", peakDb)).append('\n')
        sb.append("│ RMS               : ").append(String.format(java.util.Locale.US, "%.1f", rms))
            .append(String.format(java.util.Locale.US, "  (%+.2f dBFS)", rmsDb)).append('\n')
        sb.append("│ DC offset         : ").append(String.format(java.util.Locale.US, "%.2f", dc))
            .append(if (abs(dc) > 50) "  ⚠ noticeable DC bias" else "").append('\n')
        sb.append("│ zero-crossing     : ").append(String.format(java.util.Locale.US, "%.4f / sample", zcr)).append('\n')
        sb.append("│ silence ratio     : ").append(String.format(java.util.Locale.US, "%.2f %%", silentPct))
            .append("  (|x| < ").append(SILENCE_THRESHOLD).append(")\n")
        sb.append("│ clipped ratio     : ").append(String.format(java.util.Locale.US, "%.4f %%", clipPct))
            .append("  (|x| ≥ ").append(CLIP_THRESHOLD).append(")\n")
        sb.append("│ effective bits    : ").append(effectiveBits).append(" / 16")
            .append(if (effectiveBits < 12) "  ⚠ looks like padded/truncated source" else "").append('\n')
        sb.append("│ longest zero-run  : ").append(longestZeroMs).append(" ms")
            .append(if (longestZeroMs > 200) "  ⚠ possible transport dropout" else "").append('\n')
        sb.append("│ longest repeat-run: ").append(longestRepeatMs).append(" ms")
            .append(if (longestRepeatMs > 60) "  ⚠ possible PLC / stuck-ADC" else "").append('\n')

        if (spec != null) {
            sb.append("├─ spectral fingerprint (last ≤10 s, FFT) ──────\n")
            for (i in BAND_LABELS.indices) {
                val pct = spec.bands[i]
                sb.append("│ ")
                    .append(String.format(java.util.Locale.US, "%-10s", BAND_LABELS[i]))
                    .append(String.format(java.util.Locale.US, " %5.1f%%  ", pct))
                    .append(bar(pct)).append('\n')
            }
            sb.append("│ dominant peak     : ")
                .append(String.format(java.util.Locale.US, "%.0f Hz", spec.dominantFreqHz))
                .append(String.format(java.util.Locale.US, "  (%.1f%% energy, peak/median = +%.1f dB)",
                    spec.dominantPct, spec.peakToMedianDb)).append('\n')
            sb.append("│ spectral flatness : ")
                .append(String.format(java.util.Locale.US, "%.3f", spec.flatness))
                .append(if (spec.flatness < 0.05) "  ⚠ very tonal" else "").append('\n')
            if (spec.toneAlert != null)
                sb.append("│ tone alert        : ⚠ ").append(spec.toneAlert).append('\n')
        }

        sb.append("│ WavTokenizer      : ").append(verdict).append('\n')
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    private fun bar(pct: Double, width: Int = 20): String {
        val filled = ((pct.coerceIn(0.0, 100.0) / 100.0) * width).toInt().coerceIn(0, width)
        return "│" + "█".repeat(filled) + "·".repeat(width - filled) + "│"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal — analysis
    // ─────────────────────────────────────────────────────────────────────

    /** Take a contiguous snapshot of the ring buffer (most recent ≤10 s of audio). */
    private fun snapshotPcm(): ShortArray? {
        val n = if (ringFilled) ringSize else ringWriteIndex
        if (n < FFT_SIZE) return null
        val out = ShortArray(n)
        if (ringFilled) {
            val tail = ringSize - ringWriteIndex
            System.arraycopy(ring, ringWriteIndex, out, 0, tail)
            System.arraycopy(ring, 0, out, tail, ringWriteIndex)
        } else {
            System.arraycopy(ring, 0, out, 0, n)
        }
        return out
    }

    private data class SpectralResult(
        val bands: DoubleArray,
        val flatness: Double,
        val dominantFreqHz: Double,
        val dominantPct: Double,
        val peakToMedianDb: Double,
        val toneAlert: String?,
    )

    private fun analyseSpectrum(pcm: ShortArray): SpectralResult {
        val nyquistBin = FFT_SIZE / 2
        val hop = FFT_SIZE / 2
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val binHz = sampleRate.toDouble() / FFT_SIZE
        val bandEnergy = DoubleArray(BAND_LABELS.size)
        val avgPower = DoubleArray(nyquistBin + 1)
        val binBand = IntArray(nyquistBin + 1) { bin ->
            if (bin == 0) -1 else {
                val hz = bin * binHz
                var b = -1
                for (k in 0 until BAND_LABELS.size) {
                    if (hz >= BAND_EDGES_HZ[k] && hz < BAND_EDGES_HZ[k + 1]) { b = k; break }
                }
                b
            }
        }

        var offset = 0
        var frames = 0
        while (offset + FFT_SIZE <= pcm.size) {
            for (i in 0 until FFT_SIZE) {
                re[i] = pcm[offset + i].toDouble() * hannWindow[i]
                im[i] = 0.0
            }
            fftInPlace(re, im)
            for (bin in 1..nyquistBin) {
                val mag2 = re[bin] * re[bin] + im[bin] * im[bin]
                avgPower[bin] += mag2
                val band = binBand[bin]
                if (band >= 0) bandEnergy[band] += mag2
            }
            offset += hop
            frames++
        }

        val totalBand = bandEnergy.sum()
        val bandsPct =
            if (totalBand > 0.0) DoubleArray(bandEnergy.size) { bandEnergy[it] * 100.0 / totalBand }
            else DoubleArray(bandEnergy.size)

        val lowBin = max(2, (50.0 / binHz).toInt())
        val highBin = nyquistBin
        if (highBin <= lowBin)
            return SpectralResult(bandsPct, 1.0, 0.0, 0.0, 0.0, null)

        var sumMag = 0.0
        var sumLogMag = 0.0
        var totalEnergy = 0.0
        var peakBin = lowBin
        var peakVal = 0.0
        val mags = DoubleArray(highBin - lowBin + 1)
        for ((idx, bin) in (lowBin..highBin).withIndex()) {
            val mag = sqrt(avgPower[bin])
            mags[idx] = mag
            sumMag += mag
            sumLogMag += if (mag > 0) ln(mag) else -50.0
            totalEnergy += avgPower[bin]
            if (avgPower[bin] > peakVal) { peakVal = avgPower[bin]; peakBin = bin }
        }
        val nm = mags.size.toDouble()
        val arith = sumMag / nm
        val geo = Math.exp(sumLogMag / nm)
        val flatness = if (arith > 0.0) (geo / arith).coerceIn(0.0, 1.0) else 1.0
        val dominantFreq = peakBin * binHz
        val dominantPct = if (totalEnergy > 0.0) peakVal * 100.0 / totalEnergy else 0.0
        val sorted = mags.copyOf().also { it.sort() }
        val median = sorted[sorted.size / 2].coerceAtLeast(1e-9)
        val peakMag = sqrt(peakVal)
        val p2m = 20.0 * log10(peakMag / median)

        val tonal = flatness < 0.05 && dominantPct > 8.0 && p2m > 25.0
        val alert = if (tonal) "sustained tone at ${dominantFreq.toInt()} Hz (flatness=%.2f, peak/median=+%.0f dB)"
            .format(java.util.Locale.US, flatness, p2m) else null
        return SpectralResult(bandsPct, flatness, dominantFreq, dominantPct, p2m, alert)
    }

    private fun suitabilityVerdict(
        rmsDb: Double, peakDb: Double, clipPct: Double, silentPct: Double,
        effectiveBits: Int, longestRepeatMs: Int, spec: SpectralResult?,
    ): String {
        val issues = mutableListOf<String>()
        val warns = mutableListOf<String>()

        when {
            rmsDb.isInfinite() -> issues += "no signal (RMS = −∞)"
            rmsDb < -32.0     -> issues += "RMS %.1f dBFS too quiet".format(java.util.Locale.US, rmsDb)
            rmsDb > -6.0      -> issues += "RMS %.1f dBFS too hot".format(java.util.Locale.US, rmsDb)
            rmsDb < -24.0 || rmsDb > -12.0 ->
                warns += "RMS %.1f dBFS outside ideal −24…−12 dBFS".format(java.util.Locale.US, rmsDb)
        }
        if (peakDb > -0.5) warns += "peak %+.2f dBFS — near clip line".format(java.util.Locale.US, peakDb)
        if (clipPct > 0.1) issues += "clipped %.2f%% of samples".format(java.util.Locale.US, clipPct)
        if (effectiveBits in 1 until 12)
            warns += "%d effective bits — looks padded/truncated".format(java.util.Locale.US, effectiveBits)
        if (longestRepeatMs > 60)
            issues += "%d ms repeat-run → PLC / stuck-ADC".format(java.util.Locale.US, longestRepeatMs)
        if (silentPct > 80.0)
            warns += "%.0f%% silent — most tokens will be silence".format(java.util.Locale.US, silentPct)

        if (spec != null) {
            val b3 = spec.bands.getOrNull(3) ?: 0.0
            val b4 = spec.bands.getOrNull(4) ?: 0.0
            when {
                b3 < 1.0 && b4 < 0.5 ->
                    issues += "narrowband (3.4–8k=%.1f%% 8–12k=%.2f%%) → true SCO/CVSD — decoder will hallucinate highs"
                        .format(java.util.Locale.US, b3, b4)
                b4 < 0.5 ->
                    warns += "natively ≤8 kHz (3.4–8k=%.1f%% 8–12k=%.2f%%) — looks like 16 kHz mic; fine but no fullband"
                        .format(java.util.Locale.US, b3, b4)
            }
            if (spec.toneAlert != null)
                issues += "tonal contamination: ${spec.toneAlert} → codebook collapse + speech masking"
        }

        val verdict = when {
            issues.isNotEmpty() -> "✗ POOR — " + issues.joinToString("; ")
            warns.isNotEmpty()  -> "△ OK — " + warns.joinToString("; ")
            else                -> "✓ GOOD — input matches training-distribution loudness/bandwidth/bit-depth/PLC bounds"
        }
        return verdict
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal — utilities
    // ─────────────────────────────────────────────────────────────────────

    private fun effectiveBitsFromMask(mask: Int): Int {
        if (mask == 0) return 1
        var m = mask and 0xFFFF
        var lowestBit = 0
        while ((m and 1) == 0 && lowestBit < 16) { m = m ushr 1; lowestBit++ }
        return (16 - lowestBit).coerceIn(1, 16)
    }

    private fun toDbFs(linear: Double): Double {
        if (linear <= 0.0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(linear / 32_767.0)
    }

    /** Iterative radix-2 Cooley–Tukey FFT, in-place. [re].size must be a power of 2. */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang); val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0; var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe; im[i + k + half] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE     -> "USB_DEVICE"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET    -> "USB_HEADSET"
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY  -> "USB_ACCESSORY"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO  -> "BT_SCO"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC    -> "BUILTIN_MIC"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET  -> "WIRED_HEADSET"
        android.media.AudioDeviceInfo.TYPE_UNKNOWN        -> "UNKNOWN"
        -1                                                -> "n/a"
        else                                              -> "type=$type"
    }
}

