package com.commcrete.stardust.util.audio

import android.content.Context
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Orchestrates a single source's path through the test-feeder pipeline:
 *   load → analyze → (round-trip) → chunk → DSP filter chain → AI encoder.
 *
 * Mirrors `AudioRecorderAI.recordLoop` chunking exactly so file-fed runs
 * produce the same cadence and chunk shape as a live mic session. All
 * artifact persistence (filtered WAVs) is delegated to [AudioArtifactWriter].
 */
internal object AudioFeederEngine {

    private const val TAG = "AudioTestFeeder"

    suspend fun feedSingle(
        context: Context,
        destination: String,
        carrier: Carrier?,
        source: Source,
        realtimePacing: Boolean,
        roundTrip: Boolean,
        artifactDir: File,
        lowPass: LowPassConfig?,
        notch: NotchConfig?,
        rnNoise: RnNoiseConfig?,
        agc: AGCConfig?,
        onStats: (AudioStats) -> Unit,
        onRoundTrip: (RoundTripResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!source.file.exists() || !source.file.canRead()) {
            Timber.tag(TAG).w("Skipping unreadable file: %s", source.file.absolutePath)
            return@withContext
        }

        val (info, pcm) = AudioFileLoader.loadAndNormalize(source)
        AudioTestFeederLogger.logAudioInfo(info)
        val stats = AudioStatsAnalyzer.computeStats(pcm, source)
        AudioTestFeederLogger.logAudioStats(source.label, stats)
        onStats(stats)

        if (roundTrip) {
            val rt = RoundTripAnalyzer.runRoundTrip(source, pcm, artifactDir)
            if (rt != null) {
                onRoundTrip(rt)
                AudioTestFeederLogger.logRoundTrip(rt)
            }
        }

        val fullChunks = pcm.size / AudioTestFeeder.SAMPLES_PER_CHUNK
        val tailSamples = pcm.size % AudioTestFeeder.SAMPLES_PER_CHUNK
        val tailMs = (tailSamples * 1000L) / AudioTestFeeder.TARGET_SAMPLE_RATE
        Timber.tag(TAG).i(
            "  → Emitting %d full chunk(s) of %d samples (%d ms each)%s — chunked exactly like AudioRecorderAI (10 ms reads → %d-sample accumulator)",
            fullChunks, AudioTestFeeder.SAMPLES_PER_CHUNK, AudioTestFeeder.CHUNK_DURATION_MS,
            if (tailSamples > 0) " + 1 partial/final chunk of $tailSamples samples (${tailMs} ms)" else "",
            AudioTestFeeder.SAMPLES_PER_CHUNK
        )

        // ---- Stage construction (notch → rnnoise → agc → lpf) ----

        val notchFilter: NotchFilter? = notch?.takeIf { it.enabled }?.let {
            val resolvedBands = it.resolveBands()
            Timber.tag(TAG).i(
                "  ⌁ Notch ENABLED: %s → %d band(s): %s (applied per chunk before AGC/LPF/AI)",
                it.describe(),
                resolvedBands.size,
                resolvedBands.joinToString(",") { b -> "${b.frequencyHz.toInt()}Hz@Q${b.q.toInt()}" }
            )
            NotchFilter(sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE, bands = resolvedBands)
        }
        val notchAccumulator: ArrayList<Short>? =
            if (notchFilter != null) ArrayList(pcm.size) else null

        val rnNoiseProcessor: com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor? =
            rnNoise?.takeIf { it.enabled }?.let {
                Timber.tag(TAG).i(
                    "  ⌁ RnNoise ENABLED: %s (applied per chunk after notch, before AGC/LPF/AI)",
                    it.describe()
                )
                val proc = com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor().apply {
                    init(AudioTestFeeder.TARGET_SAMPLE_RATE)
                }
                if (proc.isActive()) {
                    Timber.tag(TAG).i(
                        "  ✓ RnNoise native backend active: %s",
                        proc.activeClassName()
                    )
                } else {
                    Timber.tag(TAG).w(
                        "  ⚠ RnNoise is enabled but running in PASS-THROUGH mode " +
                            "(native librnnoise_jni.so not loaded / wrapper class not on classpath). " +
                            "The `*-rnnoise.wav` artifact will equal the post-notch audio. " +
                            "Fix: run ./stardust/scripts/setup_rnnoise.sh then rebuild :stardust."
                    )
                }
                proc
            }
        val rnNoiseAccumulator: ArrayList<Short>? =
            if (rnNoiseProcessor != null) ArrayList(pcm.size) else null

        val agcFilter: AGCFilter? = agc?.takeIf { it.enabled }?.let {
            Timber.tag(TAG).i(
                "  ⌁ AGC ENABLED: %s (applied per chunk after notch, before LPF/AI)",
                it.describe()
            )
            AGCFilter(
                sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE,
                targetLevel = it.targetLevel,
                attackMs = it.attackMs,
                releaseMs = it.releaseMs,
                maxGainDb = it.maxGainDb,
                minGainDb = it.minGainDb,
                noiseGateLevel = it.noiseGateLevel,
            )
        }
        val agcAccumulator: ArrayList<Short>? =
            if (agcFilter != null) ArrayList(pcm.size) else null

        val lpf: LowPassFilter? = lowPass?.takeIf { it.enabled }?.let {
            Timber.tag(TAG).i(
                "  ⌁ Low-pass ENABLED: cutoff=%.1f Hz, roll-off=%.1f dB/oct (applied per chunk after AGC, before AI encode)",
                it.cutoffHz, it.rollOffDbPerOctave
            )
            LowPassFilter(
                sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE,
                cutoffHz = it.cutoffHz,
                rollOffDbPerOctave = it.rollOffDbPerOctave,
            )
        }
        val lpfAccumulator: ArrayList<Short>? =
            if (lpf != null) ArrayList(pcm.size) else null

        // ---- Chunk emission loop (mirrors AudioRecorderAI) ----

        var chunkIndex = 0
        val startTs = System.currentTimeMillis()
        val sinkFile = createSinkFile(context, destination, source.label)

        val gain = SharedPreferencesUtil.getAIGain(context) / 100f
        val readBufferSize = AudioTestFeeder.TARGET_SAMPLE_RATE / 100 // 10 ms = 240 samples @ 24 kHz
        val shortBuffer = ShortArray(readBufferSize)
        val chunkSamples = ShortArray(AudioTestFeeder.SAMPLES_PER_CHUNK)
        var chunkSampleIndex = 0
        var totalRead = 0
        val msPerReadBuffer = (readBufferSize * 1000L) / AudioTestFeeder.TARGET_SAMPLE_RATE

        fun emitChunk(buffer: ShortArray, length: Int, isPartial: Boolean) {
            val chunk = AudioDsp.applyAiGain(buffer, length, gain)

            val cStats = AudioStatsAnalyzer.quickChunkStats(chunk)
            Timber.tag(TAG).d(
                "    chunk #%03d len=%d peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f%s",
                chunkIndex, chunk.size, cStats.peak, cStats.peakDbFs,
                cStats.rms, cStats.rmsDbFs, cStats.zeroCrossingRate,
                if (isPartial) "  [PARTIAL/FINAL]" else ""
            )

            if (notchFilter != null) {
                notchFilter.processInPlace(chunk)
                val nStats = AudioStatsAnalyzer.quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-notch     peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f",
                    nStats.peak, nStats.peakDbFs, nStats.rms, nStats.rmsDbFs,
                    nStats.zeroCrossingRate
                )
                notchAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            if (rnNoiseProcessor != null) {
                rnNoiseProcessor.process(chunk, chunk.size)
                val rStats = AudioStatsAnalyzer.quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-rnnoise   peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f",
                    rStats.peak, rStats.peakDbFs, rStats.rms, rStats.rmsDbFs,
                    rStats.zeroCrossingRate
                )
                rnNoiseAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            if (agcFilter != null) {
                agcFilter.processInPlace(chunk)
                val aStats = AudioStatsAnalyzer.quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-AGC       peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f gain=%.2fx env=%.4f",
                    aStats.peak, aStats.peakDbFs, aStats.rms, aStats.rmsDbFs,
                    aStats.zeroCrossingRate,
                    agcFilter.currentGainLinear(), agcFilter.currentEnvelope()
                )
                agcAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            if (lpf != null) {
                lpf.processInPlace(chunk)
                val fStats = AudioStatsAnalyzer.quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-LPF       peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f",
                    fStats.peak, fStats.peakDbFs, fStats.rms, fStats.rmsDbFs,
                    fStats.zeroCrossingRate
                )
                lpfAccumulator?.let { acc -> for (s in chunk) acc.add(s) }
            }

            PttSendManager.addNewFrame(chunk, sinkFile, carrier, destination)
        }

        while (totalRead < pcm.size && isActive) {
            val read = minOf(readBufferSize, pcm.size - totalRead)
            System.arraycopy(pcm, totalRead, shortBuffer, 0, read)
            totalRead += read

            var consumed = 0
            while (consumed < read && isActive) {
                val remaining = AudioTestFeeder.SAMPLES_PER_CHUNK - chunkSampleIndex
                val toCopy = minOf(remaining, read - consumed)
                System.arraycopy(shortBuffer, consumed, chunkSamples, chunkSampleIndex, toCopy)
                chunkSampleIndex += toCopy
                consumed += toCopy

                if (chunkSampleIndex == AudioTestFeeder.SAMPLES_PER_CHUNK) {
                    emitChunk(chunkSamples, AudioTestFeeder.SAMPLES_PER_CHUNK, isPartial = false)
                    chunkIndex++
                    chunkSampleIndex = 0
                }
            }

            if (realtimePacing && totalRead < pcm.size) {
                delay(msPerReadBuffer)
            }
        }

        if (chunkSampleIndex > 0 && isActive) {
            emitChunk(chunkSamples, chunkSampleIndex, isPartial = true)
            chunkIndex++
        }

        val elapsed = System.currentTimeMillis() - startTs
        Timber.tag(TAG).i(
            "  ✓ Finished %s: %d chunks, %d samples (%d ms audio) in %d ms wall-clock",
            source.label, chunkIndex, totalRead,
            (totalRead * 1000L) / AudioTestFeeder.TARGET_SAMPLE_RATE, elapsed
        )

        // ---- Persist filter-stage artifacts ----

        if (lpf != null && lpfAccumulator != null && lowPass != null && lpfAccumulator.isNotEmpty()) {
            persistArtifact(
                artifactDir,
                "${System.currentTimeMillis()}-${source.label}-lowpass-" +
                    "${lowPass.cutoffHz.toInt()}Hz-${lowPass.rollOffDbPerOctave.toInt()}dboct.wav",
                lpfAccumulator, source.label, "low-pass-filtered"
            )
        }

        if (notchFilter != null && notchAccumulator != null && notch != null && notchAccumulator.isNotEmpty()) {
            val tag = if (notch.harmonics != null) {
                notch.harmonics.joinToString(separator = "_") {
                    "${it.frequencyHz.toInt()}HzQ${it.q.toInt()}"
                }
            } else {
                "${notch.fundamentalHz.toInt()}Hz-Q${notch.q.toInt()}-x${notch.numHarmonics}"
            }
            persistArtifact(
                artifactDir,
                "${System.currentTimeMillis()}-${source.label}-notch-$tag.wav",
                notchAccumulator, source.label, "notch-filtered"
            )
        }

        if (rnNoiseProcessor != null && rnNoiseAccumulator != null && rnNoiseAccumulator.isNotEmpty()) {
            persistArtifact(
                artifactDir,
                "${System.currentTimeMillis()}-${source.label}-rnnoise.wav",
                rnNoiseAccumulator, source.label, "RNNoise-cleaned"
            )
        }
        rnNoiseProcessor?.let {
            try { it.release() } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "RnNoise release failed for %s", source.label)
            }
        }

        if (agcFilter != null && agcAccumulator != null && agc != null && agcAccumulator.isNotEmpty()) {
            val tag = "tgt%.2f-atk%.0fms-rel%.0fms-max%.0fdB"
                .format(agc.targetLevel, agc.attackMs, agc.releaseMs, agc.maxGainDb)
                .replace(',', '.')
            persistArtifact(
                artifactDir,
                "${System.currentTimeMillis()}-${source.label}-agc-$tag.wav",
                agcAccumulator, source.label, "AGC-processed"
            )
        }
    }

    private fun persistArtifact(
        artifactDir: File,
        fileName: String,
        accumulator: ArrayList<Short>,
        sourceLabel: String,
        kind: String,
    ) {
        try {
            artifactDir.mkdirs()
            val out = File(artifactDir, fileName)
            val arr = ShortArray(accumulator.size) { accumulator[it] }
            AudioArtifactWriter.writePcm16Wav(out, arr, AudioTestFeeder.TARGET_SAMPLE_RATE)
            Timber.tag(TAG).i(
                "  💾 Wrote %s WAV: %s (%d samples, %d ms)",
                kind, out.absolutePath, arr.size,
                (arr.size * 1000L) / AudioTestFeeder.TARGET_SAMPLE_RATE
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write %s artifact WAV for %s", kind, sourceLabel)
        }
    }

    fun createSinkFile(context: Context, destination: String, label: String): File {
        val baseDir = try {
            File(DataManager.fileLocation)
        } catch (t: Throwable) {
            context.cacheDir
        }
        val dir = File(baseDir, "test_feeder/$destination").apply { if (!exists()) mkdirs() }
        return File(dir, "${System.currentTimeMillis()}-$label.pcm").apply {
            if (!exists()) createNewFile()
        }
    }
}





