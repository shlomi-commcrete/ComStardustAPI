package com.commcrete.stardust.util.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Computes per-file PCM statistics ([AudioStats]) and
 * spectral-fingerprint analyses used by the WavTokenizer suitability verdict.
 *
 * Pure analysis: takes already-normalized 24 kHz mono PCM and returns
 * descriptive numbers. No logging, no I/O, no DSP transforms (delegates FFT
 * and the Hann window to [AudioDsp]).
 */
internal object AudioStatsAnalyzer {

    /** Bundle returned by [computeSpectralAnalysis] — bands + tone diagnostics. */
    internal data class SpectralAnalysis(
        val bands: DoubleArray,
        val flatness: Double,
        val dominantFreqHz: Double,
        val dominantBinEnergyPct: Double,
        val peakToMedianDb: Double,
        val toneAlert: String?,
    )

    /** Lightweight per-chunk stats used during real-time emission logging. */
    internal data class ChunkStats(
        val peak: Int,
        val peakDbFs: Double,
        val rms: Double,
        val rmsDbFs: Double,
        val zeroCrossingRate: Double,
    )

    fun computeStats(pcm: ShortArray, source: Source): AudioStats {
        if (pcm.isEmpty()) {
            return AudioStats(
                0, 0, Double.NEGATIVE_INFINITY, 0.0, Double.NEGATIVE_INFINITY,
                0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0.0, "n/a", null,
                DoubleArray(AudioTestFeeder.BAND_LABELS.size),
                spectralFlatness = 1.0,
                dominantFreqHz = 0.0,
                dominantBinEnergyPct = 0.0,
                peakToMedianDb = 0.0,
                toneAlert = null,
            )
        }
        var peak = 0
        var sumSq = 0.0
        var diffSq = 0.0
        var sum = 0.0
        var zc = 0
        var silent = 0
        var clipped = 0
        var lsbMask = 0
        var longestZeroRun = 0
        var longestRepeatRun = 0
        var curZeroRun = 0
        var curRepeatRun = 1
        val silenceThreshold = 200
        val clipThreshold = 32_700
        var prev = pcm[0]
        for ((i, s) in pcm.withIndex()) {
            val v = s.toInt()
            val a = abs(v)
            if (a > peak) peak = a
            sumSq += (v * v).toDouble()
            sum += v
            lsbMask = lsbMask or (v and 0xFFFF)
            if (a < silenceThreshold) silent++
            if (a >= clipThreshold) clipped++
            if (i > 0) {
                val d = v - prev.toInt()
                diffSq += (d * d).toDouble()
                if ((prev.toInt() xor v) < 0) zc++
                if (s == prev && v != 0) curRepeatRun++ else {
                    if (curRepeatRun > longestRepeatRun) longestRepeatRun = curRepeatRun
                    curRepeatRun = 1
                }
            }
            if (v == 0) {
                curZeroRun++
                if (curZeroRun > longestZeroRun) longestZeroRun = curZeroRun
            } else curZeroRun = 0
            prev = s
        }
        if (curRepeatRun > longestRepeatRun) longestRepeatRun = curRepeatRun

        val rms = sqrt(sumSq / pcm.size)
        val dc = sum / pcm.size
        val effectiveBits = if (lsbMask == 0) 1 else {
            var lowest = 0
            while (lowest < 16 && (lsbMask shr lowest) and 1 == 0) lowest++
            (16 - lowest).coerceIn(1, 16)
        }
        val highBandRatio = if (sumSq > 0) (diffSq / (sumSq * 4.0)).coerceAtMost(1.0) else 0.0
        val bandwidthHint = when {
            highBandRatio < 0.02 -> "narrowband (≤~3.4 kHz, BLE SCO / CVSD-like)"
            highBandRatio < 0.08 -> "mid-band (≤~7 kHz, mSBC / phone-call grade)"
            highBandRatio < 0.25 -> "wideband"
            else -> "fullband / noisy"
        }
        val longestZeroRunMs = (longestZeroRun * 1000L / AudioTestFeeder.TARGET_SAMPLE_RATE).toInt()
        val longestRepeatRunMs = (longestRepeatRun * 1000L / AudioTestFeeder.TARGET_SAMPLE_RATE).toInt()

        val rawIssue: String? = if (source.rawPcm) sniffRawByteIssue(source.file) else null

        val spectral = computeSpectralAnalysis(pcm, AudioTestFeeder.TARGET_SAMPLE_RATE)

        return AudioStats(
            sampleCount = pcm.size,
            peak = peak,
            peakDbFs = toDbFs(peak.toDouble()),
            rms = rms,
            rmsDbFs = toDbFs(rms),
            dcOffset = dc,
            zeroCrossingRate = zc.toDouble() / pcm.size,
            silenceRatio = silent.toDouble() / pcm.size,
            clippedSampleRatio = clipped.toDouble() / pcm.size,
            effectiveBitsUsed = effectiveBits,
            longestZeroRunMs = longestZeroRunMs,
            longestRepeatRunMs = longestRepeatRunMs,
            highBandEnergyRatio = highBandRatio,
            bandwidthHint = bandwidthHint,
            possibleRawByteIssue = rawIssue,
            subBandEnergyPct = spectral.bands,
            spectralFlatness = spectral.flatness,
            dominantFreqHz = spectral.dominantFreqHz,
            dominantBinEnergyPct = spectral.dominantBinEnergyPct,
            peakToMedianDb = spectral.peakToMedianDb,
            toneAlert = spectral.toneAlert,
        )
    }

    /**
     * Single FFT pass that computes per-band energy + flatness + dominant peak +
     * peak-to-median + a textual tone alert when a sustained narrow-band whine
     * is detected.
     */
    fun computeSpectralAnalysis(pcm: ShortArray, sampleRate: Int): SpectralAnalysis {
        val emptyBands = DoubleArray(AudioTestFeeder.BAND_LABELS.size)
        if (pcm.size < AudioDsp.FFT_SIZE) {
            return SpectralAnalysis(emptyBands, 1.0, 0.0, 0.0, 0.0, null)
        }

        val nyquistBin = AudioDsp.FFT_SIZE / 2
        val n = minOf(pcm.size, AudioDsp.FFT_MAX_SAMPLES)
        val hop = AudioDsp.FFT_SIZE / 2
        val re = DoubleArray(AudioDsp.FFT_SIZE)
        val im = DoubleArray(AudioDsp.FFT_SIZE)
        val binHz = sampleRate.toDouble() / AudioDsp.FFT_SIZE

        val binBand = IntArray(nyquistBin + 1) { bin ->
            if (bin == 0) -1 else {
                val hz = bin * binHz
                var b = -1
                for (k in 0 until AudioTestFeeder.BAND_LABELS.size) {
                    if (hz >= AudioTestFeeder.BAND_EDGES_HZ[k] && hz < AudioTestFeeder.BAND_EDGES_HZ[k + 1]) { b = k; break }
                }
                b
            }
        }

        val bandEnergy = DoubleArray(AudioTestFeeder.BAND_LABELS.size)
        val avgPower = DoubleArray(nyquistBin + 1)
        var offset = 0
        var frames = 0
        while (offset + AudioDsp.FFT_SIZE <= n) {
            for (i in 0 until AudioDsp.FFT_SIZE) {
                re[i] = pcm[offset + i].toDouble() * AudioDsp.hannWindow[i]
                im[i] = 0.0
            }
            AudioDsp.fftInPlace(re, im)
            for (bin in 1..nyquistBin) {
                val mag2 = re[bin] * re[bin] + im[bin] * im[bin]
                avgPower[bin] += mag2
                val band = binBand[bin]
                if (band >= 0) bandEnergy[band] += mag2
            }
            offset += hop
            frames++
        }

        if (frames == 0) return SpectralAnalysis(emptyBands, 1.0, 0.0, 0.0, 0.0, null)

        val totalBandEnergy = bandEnergy.sum()
        val bandsPct = if (totalBandEnergy > 0.0)
            DoubleArray(bandEnergy.size) { bandEnergy[it] * 100.0 / totalBandEnergy }
        else emptyBands

        val lowBin = max(2, (50.0 / binHz).toInt())
        val highBin = nyquistBin
        if (highBin <= lowBin) {
            return SpectralAnalysis(bandsPct, 1.0, 0.0, 0.0, 0.0, null)
        }

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
        val nMags = mags.size.toDouble()
        val arithMean = sumMag / nMags
        val geoMean = exp(sumLogMag / nMags)
        val flatness = if (arithMean > 0.0) (geoMean / arithMean).coerceIn(0.0, 1.0) else 1.0
        val dominantFreq = peakBin * binHz
        val dominantPct = if (totalEnergy > 0.0) peakVal * 100.0 / totalEnergy else 0.0

        val sorted = mags.copyOf().also { it.sort() }
        val median = sorted[sorted.size / 2].coerceAtLeast(1e-9)
        val peakMag = sqrt(peakVal)
        val peakToMedianDb = 20.0 * log10(peakMag / median)

        val tonal = flatness < 0.05 && dominantPct > 8.0 && peakToMedianDb > 25.0

        val alert: String? = if (tonal) {
            val freqLabel = when {
                dominantFreq < 120.0 -> "${dominantFreq.toInt()} Hz (mains hum range)"
                dominantFreq < 500.0 -> "${dominantFreq.toInt()} Hz (low buzz)"
                dominantFreq < 4_000.0 -> "${dominantFreq.toInt()} Hz (whine — masks speech intelligibility)"
                dominantFreq < 8_000.0 -> "${dominantFreq.toInt()} Hz (high whine — switching-supply territory)"
                else                   -> "${dominantFreq.toInt()} Hz (very high tone)"
            }
            "sustained tone at $freqLabel — flatness=%.3f, dominant bin=%.1f%%, peak/median=+%.1f dB".format(
                java.util.Locale.US, flatness, dominantPct, peakToMedianDb
            )
        } else null

        return SpectralAnalysis(bandsPct, flatness, dominantFreq, dominantPct, peakToMedianDb, alert)
    }

    /** Returns per-band energy as percentages (sum ≈ 100). Thin wrapper for round-trip code. */
    fun computeSubBandEnergyPct(pcm: ShortArray, sampleRate: Int): DoubleArray {
        return computeSpectralAnalysis(pcm, sampleRate).bands
    }

    /**
     * Quick sanity check for raw PCM files: tries to detect wrong endian /
     * unsigned interpretation by comparing peak amplitudes under different
     * byte interpretations.
     */
    private fun sniffRawByteIssue(file: File): String? {
        return try {
            val sample = file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var off = 0
                while (off < buf.size) {
                    val r = input.read(buf, off, buf.size - off)
                    if (r <= 0) break
                    off += r
                }
                if (off == buf.size) buf else buf.copyOf(off)
            }
            if (sample.size < 1024) return null
            val asLe = AudioFileLoader.bytesToShortsLe(sample)
            val asBe = ShortArray(sample.size / 2)
            val bb = ByteBuffer.wrap(sample).order(ByteOrder.BIG_ENDIAN)
            for (i in asBe.indices) asBe[i] = bb.short
            val peakLe = asLe.maxOf { abs(it.toInt()) }
            val peakBe = asBe.maxOf { abs(it.toInt()) }
            val meanLe = asLe.map { it.toInt() }.average()
            val issues = mutableListOf<String>()
            if (peakBe < peakLe / 4 && peakBe > 0) issues += "looks BIG-ENDIAN, not little-endian"
            if (abs(meanLe) > 2000) issues += "large DC bias (${meanLe.toInt()}) — could be unsigned PCM or wrong bit depth"
            issues.joinToString("; ").ifEmpty { null }
        } catch (t: Throwable) { null }
    }

    fun quickChunkStats(chunk: ShortArray): ChunkStats {
        if (chunk.isEmpty()) return ChunkStats(0, Double.NEGATIVE_INFINITY, 0.0, Double.NEGATIVE_INFINITY, 0.0)
        var peak = 0
        var sumSq = 0.0
        var zc = 0
        var prev = chunk[0]
        for (s in chunk) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
            sumSq += (s.toInt() * s.toInt()).toDouble()
            if ((prev.toInt() xor s.toInt()) < 0) zc++
            prev = s
        }
        val rms = sqrt(sumSq / chunk.size)
        return ChunkStats(peak, toDbFs(peak.toDouble()), rms, toDbFs(rms), zc.toDouble() / chunk.size)
    }

    fun toDbFs(value: Double): Double {
        if (value <= 0.0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(value / 32_768.0)
    }
}

