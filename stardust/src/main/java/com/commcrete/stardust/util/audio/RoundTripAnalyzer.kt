package com.commcrete.stardust.util.audio

import timber.log.Timber
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Runs an audio source through the WavTokenizer encoder + decoder and
 * computes objective quality metrics (PSNR, SI-SDR, LSD, alignment lag,
 * per-band spectral distortion, token diversity).
 *
 * Persists artifacts (token txt+bin, original/reconstructed WAVs) via
 * [AudioArtifactWriter] so the run can be re-analyzed offline.
 */
internal object RoundTripAnalyzer {

    private const val TAG = "AudioTestFeeder"

    fun runRoundTrip(
        source: Source,
        pcm: ShortArray,
        artifactDir: File,
    ): RoundTripResult? {
        val label = source.label
        val ai = com.commcrete.stardust.ai.codec.AIModuleInitializer
        if (!ai.aiEnabled) {
            Timber.tag(TAG).w("Round-trip skipped: AIModuleInitializer.aiEnabled == false")
            return null
        }
        val encoder = try {
            ai.wavTokenizerEncoder
        } catch (e: UninitializedPropertyAccessException) {
            Timber.tag(TAG).w("Round-trip skipped: wavTokenizerEncoder not initialized yet")
            return null
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Round-trip skipped: encoder unavailable")
            return null
        }
        val decoder = try {
            ai.wavTokenizerDecoder
        } catch (e: UninitializedPropertyAccessException) {
            Timber.tag(TAG).w("Round-trip skipped: wavTokenizerDecoder not initialized yet")
            return null
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Round-trip skipped: decoder unavailable")
            return null
        }

        if (pcm.size < AudioTestFeeder.SAMPLES_PER_CHUNK) {
            Timber.tag(TAG).w("Round-trip skipped: input shorter than one 0.5 s chunk")
            return null
        }

        val allTokens = ArrayList<Long>(pcm.size / 600 + 16)
        val reconstructedList = ArrayList<Short>(pcm.size + 4096)
        var encTotalNs = 0L
        var decTotalNs = 0L
        var chunks = 0
        var err: String? = null

        // Decoder context — mirrors what PttSendManager passes in production:
        // previous tokens + decoded samples are required for cross-chunk
        // continuity (token concat, smart splice, crossfade).
        var prevTokensCtx: List<Long>? = null
        var prevSamplesCtx: ShortArray? = null

        var i = 0
        while (i + AudioTestFeeder.SAMPLES_PER_CHUNK <= pcm.size) {
            val chunk = pcm.copyOfRange(i, i + AudioTestFeeder.SAMPLES_PER_CHUNK)
            try {
                val t0 = System.nanoTime()
                val tokens = encoder.encode(chunk)
                val t1 = System.nanoTime()
                val tokenList = tokens.toList()
                val rec = decoder.decode(
                    tokenList,
                    prevTokensCtx,
                    prevSamplesCtx,
                    com.commcrete.aiaudio.codecs.WavTokenizerDecoder.ModelType.General
                )
                val t2 = System.nanoTime()
                encTotalNs += (t1 - t0)
                decTotalNs += (t2 - t1)
                for (t in tokens) allTokens.add(t)
                for (s in rec) reconstructedList.add(s)
                prevTokensCtx = tokenList
                prevSamplesCtx = rec
                chunks++
            } catch (t: Throwable) {
                err = "chunk $chunks failed: ${t.message}"
                Timber.tag(TAG).e(t, "Round-trip chunk %d failed for %s", chunks, label)
                break
            }
            i += AudioTestFeeder.SAMPLES_PER_CHUNK
        }

        if (chunks == 0) {
            return RoundTripResult(
                label, pcm.size, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0,
                Double.NaN,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0,
                DoubleArray(AudioTestFeeder.BAND_LABELS.size), err ?: "no chunks processed",
                null, null, null, null
            )
        }

        val reconstructed = ShortArray(reconstructedList.size) { reconstructedList[it] }
        val tokensArr = LongArray(allTokens.size) { allTokens[it] }

        val sourceStem = AudioArtifactWriter.sanitizeFileStem(
            source.file.nameWithoutExtension.ifBlank { source.label }
        )
        val timeStamp = System.currentTimeMillis()
        val baseName = "${timeStamp}_${sourceStem}"
        val tokensTxt = File(artifactDir, "$baseName.tokens.txt")
        val tokensBin = File(artifactDir, "$baseName.tokens.bin")
        val decodedWav = File(artifactDir, "$baseName.decoded_from_full_file.wav")
        val originalWav = File(artifactDir, "$baseName.original_24k_mono_from_full_file.wav")

        val written = mutableListOf<String>()
        runCatching { AudioArtifactWriter.writeTokensTxt(tokensTxt, tokensArr, source, chunks) }
            .onSuccess { written += "tokens.txt" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", tokensTxt.name) }
        runCatching { AudioArtifactWriter.writeTokensBin(tokensBin, tokensArr) }
            .onSuccess { written += "tokens.bin" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", tokensBin.name) }
        runCatching { AudioArtifactWriter.writePcm16Wav(decodedWav, reconstructed, AudioTestFeeder.TARGET_SAMPLE_RATE) }
            .onSuccess { written += "decoded_from_full_file.wav" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", decodedWav.name) }
        runCatching { AudioArtifactWriter.writePcm16Wav(originalWav, pcm, AudioTestFeeder.TARGET_SAMPLE_RATE) }
            .onSuccess { written += "original_from_full_file.wav" }
            .onFailure { Timber.tag(TAG).w(it, "Failed to write %s", originalWav.name) }

        if (written.isNotEmpty()) {
            Timber.tag(TAG).i(
                "  ↳ wrote artifacts for [%s] in %s: %s",
                label, artifactDir.absolutePath, written.joinToString(", ")
            )
        }

        val maxLag = 2400 // ±100 ms @ 24 kHz
        val lag = bestLagXcorr(pcm, reconstructed, maxLag)
        val (refAligned, recAligned) = alignByLag(pcm, reconstructed, lag)
        val n = minOf(refAligned.size, recAligned.size)
        val ref = refAligned.copyOfRange(0, n)
        val rec = recAligned.copyOfRange(0, n)

        val psnr = computePsnr(ref, rec)
        val siSdr = computeSiSdr(ref, rec)
        val lsd = computeLogSpectralDistanceDb(ref, rec)

        val origBands = AudioStatsAnalyzer.computeSubBandEnergyPct(ref, AudioTestFeeder.TARGET_SAMPLE_RATE)
        val recBands = AudioStatsAnalyzer.computeSubBandEnergyPct(rec, AudioTestFeeder.TARGET_SAMPLE_RATE)
        val bandDist = DoubleArray(origBands.size) { idx ->
            val o = max(origBands[idx], 0.05)
            val r = max(recBands[idx], 0.05)
            10.0 * log10(r / o)
        }

        val uniq = tokensArr.toHashSet().size
        val durationSec = (chunks * AudioTestFeeder.SAMPLES_PER_CHUNK).toDouble() / AudioTestFeeder.TARGET_SAMPLE_RATE
        val tps = tokensArr.size / durationSec
        val rtf = (encTotalNs + decTotalNs) / 1e9 / durationSec

        return RoundTripResult(
            label = label,
            inputSamples = pcm.size,
            reconstructedSamples = reconstructed.size,
            chunks = chunks,
            tokens = tokensArr.size,
            uniqueTokens = uniq,
            tokenDiversity = uniq.toDouble() / 4096.0,
            tokensPerSecond = tps,
            avgEncodeMs = encTotalNs / 1e6 / chunks,
            avgDecodeMs = decTotalNs / 1e6 / chunks,
            realTimeFactor = rtf,
            logSpectralDistanceDb = lsd,
            psnrDb = psnr,
            siSdrDb = siSdr,
            alignmentLagSamples = lag,
            perBandSpectralDistortionDb = bandDist,
            error = err,
            tokensTxtFile = tokensTxt.takeIf { it.exists() && it.length() > 0 },
            tokensBinFile = tokensBin.takeIf { it.exists() && it.length() > 0 },
            decodedWavFile = decodedWav.takeIf { it.exists() && it.length() > 0 },
            originalNormalizedWavFile = originalWav.takeIf { it.exists() && it.length() > 0 },
        )
    }

    /** Brute-force time-domain cross-correlation peak in [-maxLag, +maxLag]. */
    private fun bestLagXcorr(ref: ShortArray, rec: ShortArray, maxLag: Int): Int {
        val window = minOf(ref.size, rec.size, 2 * AudioTestFeeder.TARGET_SAMPLE_RATE)
        if (window <= maxLag * 2) return 0
        var bestLag = 0
        var bestScore = Long.MIN_VALUE
        for (lag in -maxLag..maxLag step 8) {
            var acc = 0L
            var k = 0
            while (k < window) {
                val ri = k
                val ci = k + lag
                if (ci in 0 until rec.size && ri < ref.size) {
                    acc += ref[ri].toInt().toLong() * rec[ci].toInt().toLong()
                }
                k += 4
            }
            if (acc > bestScore) { bestScore = acc; bestLag = lag }
        }
        var bestRefined = bestLag
        var bestRefinedScore = Long.MIN_VALUE
        for (lag in (bestLag - 8)..(bestLag + 8)) {
            var acc = 0L
            var k = 0
            while (k < window) {
                val ci = k + lag
                if (ci in 0 until rec.size && k < ref.size) {
                    acc += ref[k].toInt().toLong() * rec[ci].toInt().toLong()
                }
                k++
            }
            if (acc > bestRefinedScore) { bestRefinedScore = acc; bestRefined = lag }
        }
        return bestRefined
    }

    private fun alignByLag(ref: ShortArray, rec: ShortArray, lag: Int): Pair<ShortArray, ShortArray> {
        return if (lag >= 0) {
            val recCut = if (lag >= rec.size) ShortArray(0) else rec.copyOfRange(lag, rec.size)
            ref to recCut
        } else {
            val refCut = if (-lag >= ref.size) ShortArray(0) else ref.copyOfRange(-lag, ref.size)
            refCut to rec
        }
    }

    private fun computePsnr(ref: ShortArray, rec: ShortArray): Double {
        if (ref.isEmpty() || rec.isEmpty()) return Double.NEGATIVE_INFINITY
        val n = minOf(ref.size, rec.size)
        var mse = 0.0
        for (i in 0 until n) {
            val d = ref[i].toInt() - rec[i].toInt()
            mse += d.toDouble() * d.toDouble()
        }
        mse /= n
        if (mse <= 0.0) return Double.POSITIVE_INFINITY
        return 20.0 * log10(32_767.0 / sqrt(mse))
    }

    /** Scale-Invariant Source-to-Distortion Ratio (Le Roux et al., 2019). */
    private fun computeSiSdr(ref: ShortArray, rec: ShortArray): Double {
        val n = minOf(ref.size, rec.size)
        if (n == 0) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        var refEnergy = 0.0
        for (i in 0 until n) {
            val r = ref[i].toDouble()
            val e = rec[i].toDouble()
            dot += r * e
            refEnergy += r * r
        }
        if (refEnergy <= 0.0) return Double.NEGATIVE_INFINITY
        val alpha = dot / refEnergy
        var sigE = 0.0
        var noiseE = 0.0
        for (i in 0 until n) {
            val target = alpha * ref[i].toDouble()
            val noise = rec[i].toDouble() - target
            sigE += target * target
            noiseE += noise * noise
        }
        if (noiseE <= 0.0) return Double.POSITIVE_INFINITY
        return 10.0 * log10(sigE / noiseE)
    }

    /**
     * Log-Spectral Distance (LSD), in dB, averaged over Hann-windowed STFT frames.
     * Phase-invariant — primary quality metric for iSTFT-based neural codecs.
     */
    private fun computeLogSpectralDistanceDb(ref: ShortArray, rec: ShortArray): Double {
        val n = minOf(ref.size, rec.size)
        if (n < AudioDsp.FFT_SIZE) return Double.NaN
        val hop = AudioDsp.FFT_SIZE / 2
        val reR = DoubleArray(AudioDsp.FFT_SIZE); val imR = DoubleArray(AudioDsp.FFT_SIZE)
        val reC = DoubleArray(AudioDsp.FFT_SIZE); val imC = DoubleArray(AudioDsp.FFT_SIZE)
        val magR = DoubleArray(AudioDsp.FFT_SIZE / 2 + 1)
        val magC = DoubleArray(AudioDsp.FFT_SIZE / 2 + 1)
        val nyquistBin = AudioDsp.FFT_SIZE / 2
        val floorBelowPeakDb = 60.0
        val floorRatio = 10.0.pow(-floorBelowPeakDb / 20.0)
        var totalLsd = 0.0
        var frames = 0
        var offset = 0
        val cap = minOf(n, AudioDsp.FFT_MAX_SAMPLES)
        while (offset + AudioDsp.FFT_SIZE <= cap) {
            for (i in 0 until AudioDsp.FFT_SIZE) {
                reR[i] = ref[offset + i].toDouble() * AudioDsp.hannWindow[i]; imR[i] = 0.0
                reC[i] = rec[offset + i].toDouble() * AudioDsp.hannWindow[i]; imC[i] = 0.0
            }
            AudioDsp.fftInPlace(reR, imR)
            AudioDsp.fftInPlace(reC, imC)
            var peakMag = 0.0
            for (bin in 1..nyquistBin) {
                val mr = sqrt(reR[bin] * reR[bin] + imR[bin] * imR[bin])
                val mc = sqrt(reC[bin] * reC[bin] + imC[bin] * imC[bin])
                magR[bin] = mr; magC[bin] = mc
                if (mr > peakMag) peakMag = mr
                if (mc > peakMag) peakMag = mc
            }
            if (peakMag <= 0.0) { offset += hop; continue }
            val floor = peakMag * floorRatio
            var sqSum = 0.0
            for (bin in 1..nyquistBin) {
                val lr = 20.0 * log10(max(magR[bin], floor))
                val lc = 20.0 * log10(max(magC[bin], floor))
                val d = lr - lc
                sqSum += d * d
            }
            totalLsd += sqrt(sqSum / nyquistBin)
            offset += hop
            frames++
        }
        return if (frames > 0) totalLsd / frames else Double.NaN
    }
}






