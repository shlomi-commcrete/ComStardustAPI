package com.commcrete.stardust.util.audio

import android.content.Context
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

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
        dynamics: DynamicsConfig?,
        declick: DeclickConfig?,
        aiGainSoftSat: Boolean,
        onStats: (AudioStats) -> Unit,
        onRoundTrip: (RoundTripResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!source.file.exists() || !source.file.canRead()) {
            Timber.tag(TAG).w("Skipping unreadable file: %s", source.file.absolutePath)
            return@withContext
        }
        RecorderUtils.dirToSaveFile = artifactDir

        // 0. Snapshot the SOURCE file as-is (pre-normalization) so we keep an
        //    untouched copy of the original recording — same bytes, same
        //    sample rate / channels / container — next to the normalized and
        //    filtered artifacts.
        persistOriginalSourceFile(source, artifactDir)

        // 1. Load mono PCM at the source's NATIVE sample rate (no resampling
        //    yet) — the live per-chunk filter chain will run at this rate so
        //    DSP happens before resampling. We do still need a 24 kHz mirror
        //    for round-trip / cross-source stats / the normalized artifact.
        val (info, nativeRate, monoNative) = AudioFileLoader.loadMono(source)
        AudioTestFeederLogger.logAudioInfo(info)
        val pcm24k: ShortArray = if (nativeRate == AudioTestFeeder.TARGET_SAMPLE_RATE) monoNative
            else AudioDsp.resampleLinear(monoNative, nativeRate, AudioTestFeeder.TARGET_SAMPLE_RATE)
        val stats = AudioStatsAnalyzer.computeStats(pcm24k, source)
        AudioTestFeederLogger.logAudioStats(source.label, stats)
        onStats(stats)

        // 2. Optional round-trip on the FILTERED 24 kHz audio (the codec is
        //    fixed at TARGET_SAMPLE_RATE, so the round-trip path stays there).
        //    Pass the AI gain so the round-trip sees the exact same chain
        //    (Declick → Notch → RNNoise → DP → AGC → AI-gain → LPF) the live emitter does.
        val gain = SharedPreferencesUtil.getAIGain(context) / 100f
        runRoundTripIfRequested(
            roundTrip, source, pcm24k, artifactDir,
            lowPass, notch, rnNoise, agc, dynamics, declick, gain, aiGainSoftSat, onRoundTrip,
        )

        // 3. Build the live per-chunk filter chain at the NATIVE rate. The
        //    chunk after filtering is resampled to TARGET_SAMPLE_RATE before
        //    being handed to PttSendManager so the codec still sees 24 kHz.
        logChunkPlan(monoNative, nativeRate)
        val chain = LiveFilterChain.build(
            notch, rnNoise, agc, lowPass, dynamics, declick,
            aiGain = gain,
            aiGainSoftSat = aiGainSoftSat,
            sampleRate = nativeRate, expectedSize = monoNative.size,
        )

        val sinkFile = createSinkFile(context, destination, source.label)
        val aiInputWriter = com.commcrete.stardust.ai.codec.testing.DebugRawWavWriter().apply {
            start(
                context = context,
                sampleRate = AudioTestFeeder.TARGET_SAMPLE_RATE,
                channels = 1,
                bitsPerSample = 16,
                fileNamePrefix = "${source.label}-ai_input_24k",
                outputDir = artifactDir,
            )
        }

        PttSendManager.resetLiveDecodeState()
        val emission = try {
            streamPcmAsChunks(
                pcm = monoNative,
                sampleRate = nativeRate,
                realtimePacing = realtimePacing,
                chain = chain,
            ) { filteredChunk ->
                val outChunk = if (nativeRate == AudioTestFeeder.TARGET_SAMPLE_RATE) filteredChunk
                    else AudioDsp.resampleLinear(filteredChunk, nativeRate, AudioTestFeeder.TARGET_SAMPLE_RATE)

                val finalChunk: ShortArray = when {
                    outChunk.size >= AudioTestFeeder.SAMPLES_PER_CHUNK -> outChunk
                    outChunk.size < 600 -> {
                        Timber.tag(TAG).d(
                            "    ⏭ skipping sub-token tail of %d sample(s) (< 600, would emit 0 tokens)",
                            outChunk.size,
                        )
                        return@streamPcmAsChunks
                    }
                    else -> {
                        val faded = outChunk.copyOf()
                        val fadeLen = minOf(120, faded.size) // ≈ 5 ms @ 24 kHz
                        val start = faded.size - fadeLen
                        for (i in 0 until fadeLen) {
                            // Cosine half-window: 1 → 0 over fadeLen samples.
                            val w = 0.5 * (1.0 + kotlin.math.cos(Math.PI * i / fadeLen))
                            faded[start + i] = (faded[start + i] * w).toInt().toShort()
                        }
                        Timber.tag(TAG).d(
                            "    ⤵ partial tail %d samples — applied %d-sample cosine fade-out before AI encode",
                            faded.size, fadeLen,
                        )
                        faded
                    }
                }

                aiInputWriter.append(finalChunk, finalChunk.size)
                PttSendManager.addNewFrame(
                    finalChunk, sinkFile, carrier, destination,
                    applyFilters = false,
                )
            }
        } finally {
            // Patch the WAV header with final sizes regardless of whether
            // emission completed normally. Drain any in-flight encoded chunks
            // first so the AI-output WAV captures the tail (PttSendManager's
            // encoding loop polls every ~10 ms, so a short grace period is
            // enough). Then unregister the hook before closing the writer to
            // avoid a late append racing with stop().
            runCatching { delay(150) }
            PttSendManager.onDecodedChunk = null
            runCatching { aiInputWriter.stop() }
            //runCatching { aiOutputWriter.stop() }
        }
        Timber.tag(TAG).i(
            "  ✓ Finished %s: %d chunks, %d samples @ %d Hz (%d ms audio) in %d ms wall-clock",
            source.label, emission.chunkCount, emission.samplesProcessed, nativeRate,
            (emission.samplesProcessed * 1000L) / nativeRate,
            emission.elapsedMs,
        )

        // 5. Persist per-stage WAV artifacts (at native rate, since that's
        //    what the accumulators captured) and release native resources.
        //    The ai_input_24k artifact has already been streamed to disk by
        //    aiInputWriter above (closed in the finally block), so it sits
        //    next to these per-stage WAVs in artifactDir.
        chain.persistArtifacts(artifactDir, source.label, lowPass, notch, agc, dynamics, declick, sampleRate = nativeRate)
        chain.release(source.label)
    }

    // ─── feedSingle phases ───────────────────────────────────────────────────

    /**
     * If [roundTrip] is true, run the encoder→decoder round-trip on a
     * **filtered** copy of [pcm] (so the codec sees exactly what
     * `PttSendManager` will receive in production), then forward the result
     * to [onRoundTrip] and log it.
     *
     * Uses fresh, isolated filter instances via [applyFilterChainOneShot] so
     * the live per-chunk chain built later keeps its own clean state. The
     * [aiGain] is applied at the same chain slot as the live path
     * (post-AGC, pre-LPF) so the round-trip metrics aren't biased by a
     * different overall level than what the encoder sees in production.
     */
    private fun runRoundTripIfRequested(
        roundTrip: Boolean,
        source: Source,
        pcm: ShortArray,
        artifactDir: File,
        lowPass: LowPassConfig?,
        notch: NotchConfig?,
        rnNoise: RnNoiseConfig?,
        agc: AGCConfig?,
        dynamics: DynamicsConfig?,
        declick: DeclickConfig?,
        aiGain: Float,
        aiGainSoftSat: Boolean,
        onRoundTrip: (RoundTripResult) -> Unit,
    ) {
        if (!roundTrip) return
        val pcmForRoundTrip = applyFilterChainOneShot(
            pcm, lowPass, notch, rnNoise, agc, dynamics, declick, aiGain, aiGainSoftSat,
        )
        val rt = RoundTripAnalyzer.runRoundTrip(source, pcmForRoundTrip, artifactDir) ?: return
        onRoundTrip(rt)
        AudioTestFeederLogger.logRoundTrip(rt)
    }

    /** Log how many full / partial chunks the upcoming emission will produce. */
    private fun logChunkPlan(pcm: ShortArray, sampleRate: Int) {
        val samplesPerChunk = (sampleRate * AudioTestFeeder.CHUNK_DURATION_MS / 1000L).toInt()
        val fullChunks = pcm.size / samplesPerChunk
        val tailSamples = pcm.size % samplesPerChunk
        val tailMs = (tailSamples * 1000L) / sampleRate
        Timber.tag(TAG).i(
            "  → Emitting %d full chunk(s) of %d samples (%d ms each) @ %d Hz%s — filtered at native rate, resampled to %d Hz before AI",
            fullChunks, samplesPerChunk, AudioTestFeeder.CHUNK_DURATION_MS, sampleRate,
            if (tailSamples > 0) " + 1 partial/final chunk of $tailSamples samples (${tailMs} ms)" else "",
            AudioTestFeeder.TARGET_SAMPLE_RATE,
        )
    }

    /** Result of [streamPcmAsChunks] used for the wall-clock summary log. */
    private data class EmissionStats(
        val chunkCount: Int,
        val samplesProcessed: Int,
        val elapsedMs: Long,
    )

    /**
     * Mirrors `AudioRecorderAI.recordLoop` chunking exactly:
     *  - 20 ms read buffers (480 samples @ 24 kHz, 960 @ 48 kHz),
     *  - copy into a [SAMPLES_PER_CHUNK][AudioTestFeeder.SAMPLES_PER_CHUNK]-
     *    sample accumulator, emit a full chunk whenever the accumulator fills,
     *  - flush any remainder as one PARTIAL/FINAL chunk on EOF.
     *
     * Each fully-assembled chunk goes through the voice-first chain
     * (notch → rnnoise → DP → AGC → AI-gain → LPF) inside [chain] before
     * being handed to [onChunk].
     *
     * @param onChunk receives the final, post-LPF chunk handed to the AI
     *                encoder (typically `PttSendManager.addNewFrame`).
     */
    private suspend fun CoroutineScope.streamPcmAsChunks(
        pcm: ShortArray,
        sampleRate: Int,
        realtimePacing: Boolean,
        chain: LiveFilterChain,
        onChunk: (ShortArray) -> Unit,
    ): EmissionStats {
        // 20 ms read buffers — same as AudioRecorderAI.recordLoop:
        //   `val readChunkSamples = (captureRate / 50).coerceAtLeast(...)`.
        // 10 ms was producing more syscalls / coroutine yields per second
        // than the live recorder, which made the feeder's pacing diverge
        // from production by a constant factor under realtimePacing.
        val readBufferSize = (sampleRate / 50).coerceAtLeast(1) // 20 ms
        val samplesPerChunk = (sampleRate * AudioTestFeeder.CHUNK_DURATION_MS / 1000L).toInt()
        val shortBuffer = ShortArray(readBufferSize)
        val chunkSamples = ShortArray(samplesPerChunk)
        val msPerReadBuffer = (readBufferSize * 1000L) / sampleRate

        var chunkSampleIndex = 0
        var totalRead = 0
        var chunkIndex = 0
        val startTs = System.currentTimeMillis()

        while (totalRead < pcm.size && isActive) {
            val read = minOf(readBufferSize, pcm.size - totalRead)
            System.arraycopy(pcm, totalRead, shortBuffer, 0, read)
            totalRead += read

            var consumed = 0
            while (consumed < read && isActive) {
                val remaining = samplesPerChunk - chunkSampleIndex
                val toCopy = minOf(remaining, read - consumed)
                System.arraycopy(shortBuffer, consumed, chunkSamples, chunkSampleIndex, toCopy)
                chunkSampleIndex += toCopy
                consumed += toCopy

                if (chunkSampleIndex == samplesPerChunk) {
                    emitOneChunk(
                        chunkSamples, samplesPerChunk,
                        chunkIndex, isPartial = false, chain, onChunk,
                    )
                    chunkIndex++
                    chunkSampleIndex = 0
                }
            }

            // Approximate AudioRecord's blocking pacing (~20 ms per read).
            if (realtimePacing && totalRead < pcm.size) delay(msPerReadBuffer)
        }

        // Flush remainder as one PARTIAL/FINAL chunk, mirroring
        // AudioRecorderAI.onPartialFinalChunk.
        if (chunkSampleIndex > 0 && isActive) {
            emitOneChunk(
                chunkSamples, chunkSampleIndex,
                chunkIndex, isPartial = true, chain, onChunk,
            )
            chunkIndex++
        }

        return EmissionStats(
            chunkCount = chunkIndex,
            samplesProcessed = totalRead,
            elapsedMs = System.currentTimeMillis() - startTs,
        )
    }

    /**
     * Snapshot the raw chunk pre-chain (so we can log the unprocessed input
     * stats), then run it through [chain] (which logs every enabled stage —
     * notch → rnnoise → DP → AGC → AI-gain → LPF — in order) and finally
     * hand the post-LPF chunk to [onChunk].
     */
    private fun emitOneChunk(
        buffer: ShortArray,
        length: Int,
        chunkIndex: Int,
        isPartial: Boolean,
        chain: LiveFilterChain,
        onChunk: (ShortArray) -> Unit,
    ) {
        val chunk = if (length == buffer.size) buffer.copyOf() else buffer.copyOf(length)

        val raw = AudioStatsAnalyzer.quickChunkStats(chunk)
        Timber.tag(TAG).d(
            "    chunk #%03d len=%d peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f%s [pre-chain]",
            chunkIndex, chunk.size, raw.peak, raw.peakDbFs,
            raw.rms, raw.rmsDbFs, raw.zeroCrossingRate,
            if (isPartial) "  [PARTIAL/FINAL]" else "",
        )

        chain.processChunkInPlace(chunk)
        onChunk(chunk)
    }

    // ─── Live per-chunk filter chain (separate from the round-trip one-shot) ──

    /**
     * Bundles the live per-chunk filter chain — instances, accumulators and
     * the source configs needed for artifact filenames — into one cohesive
     * value so [feedSingle] doesn't have to thread a dozen nullable filters
     * through every helper.
     *
     * Use [build] to construct (it logs which stages were enabled), then:
     *  - [processChunkInPlace] for each chunk during emission,
     *  - [persistArtifacts] once at the end to write per-stage WAVs,
     *  - [release] to free native resources (RNNoise).
     */
    private class LiveFilterChain private constructor(
        val dynamicsFilter: DynamicsProcessingFilter?,
        val notchFilter: NotchFilter?,
        val rnNoiseProcessor: com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor?,
        /** RNNoise wet/dry mix in `[0,1]` (1 = full clean, 0 = bypass). */
        val rnNoiseMix: Float,
        /** RNNoise max-attenuation floor (linear, 0 = disabled). */
        val rnNoiseAttenFloor: Float,
        val agcFilter: AGCFilter?,
        val lpf: LowPassFilter?,
        /**
         * Impulsive-noise removal stage. Applied FIRST (before notch / rnnoise /
         * DP / AGC / AI-gain / LPF) so the stateful downstream filters never see
         * the spikes — see [DeclickConfig] for the full rationale.
         */
        val declickFilter: DeclickFilter?,
        /**
         * AI gain applied between AGC and LPF (post-leveller make-up).
         * 1f = passthrough. Mirrors `AudioRecorderAI.processSamples()`
         * semantics by default (per-sample multiply + int16 hard clip).
         * When [aiGainSoftSat] is true, uses a tanh soft-saturator instead
         * of hard clip — preserves hot level without brittle clipping
         * (see [AudioDsp.applyAiGainSoftSatInPlace]).
         */
        val aiGain: Float,
        /**
         * `true` → use tanh soft saturation for AI gain (peaks roll off
         * smoothly past ~50 % of full scale, no hard clipping).
         * `false` → linear gain + int16 hard clip (legacy behaviour,
         * matches production `AudioRecorderAI.processSamples`).
         */
        val aiGainSoftSat: Boolean,
        /** Whether to log per-chunk tick detections (mirrors [DeclickConfig.logDetections]). */
        val declickLogDetections: Boolean,
        val dynamicsAccumulator: ArrayList<Short>?,
        val notchAccumulator: ArrayList<Short>?,
        val rnNoiseAccumulator: ArrayList<Short>?,
        val agcAccumulator: ArrayList<Short>?,
        val lpfAccumulator: ArrayList<Short>?,
        val declickAccumulator: ArrayList<Short>?,
    ) {

        /**
         * Run a single chunk through every enabled stage in place.
         *
         * Order is **voice-first**:
         *   Declick → Notch → RNNoise → DynamicsProcessing → AGC → AI-gain → LPF
         *
         * Rationale:
         *  - Declick FIRST removes impulsive spikes so downstream stateful
         *    filters never see them (single-sample tick → notch biquad rings
         *    50 ms, RNNoise smears 200 ms, DP/AGC pump 80–250 ms).
         *  - Notch kills tonal interference (mains hum, whistles) so DP/AGC
         *    don't pump on a tone.
         *  - RNNoise sees natural mic levels (its training distribution),
         *    on a tone-free signal — best speech vs. noise discrimination.
         *  - DP shapes the cleaned signal (presence, multi-band).
         *  - AGC drives the now-clean, shaped signal toward `targetLevel`.
         *  - AI-gain is applied AFTER AGC as a post-make-up bias, so the
         *    user's slider isn't immediately undone by AGC's release loop.
         *  - LPF runs last to anti-alias before the resample-to-24 kHz +
         *    encoder hand-off.
         */
        fun processChunkInPlace(chunk: ShortArray) {
            declickFilter?.let {
                val events = it.processInPlace(chunk)
                if (declickLogDetections && events.isNotEmpty()) {
                    Timber.tag(TAG).d(
                        "      ↳ declick        %d tick(s) repaired (total run %d): %s",
                        events.size, it.totalTicks(),
                        events.joinToString(",", limit = 8) { e ->
                            "${e.positionInChunk}/${e.lengthSamples}smp@${
                                // peak in dBFS for quick scan
                                if (e.peakAbs <= 0) "-inf"
                                else "%.1fdB".format(
                                    20.0 * kotlin.math.log10(e.peakAbs.toDouble() / Short.MAX_VALUE)
                                ).replace(',', '.')
                            }${if (e.repair == DeclickFilter.RepairMethod.SKIPPED_TOO_LONG) "[SKIP]" else ""}"
                        },
                    )
                }
                logStageStats("post-declick", chunk)
                declickAccumulator?.appendAll(chunk)
            }
            notchFilter?.let {
                it.processInPlace(chunk)
                logStageStats("post-notch", chunk)
                notchAccumulator?.appendAll(chunk)
            }
            rnNoiseProcessor?.let {
                // Snapshot the dry input before the native call so we can
                // (a) blend wet/dry per [rnNoiseMix] and
                // (b) clamp per-sample attenuation to [rnNoiseAttenFloor].
                val needsBlend = rnNoiseMix < 1f || rnNoiseAttenFloor > 0f
                val dry = if (needsBlend) chunk.copyOf() else null
                it.process(chunk, chunk.size)
                if (dry != null) softenRnNoise(chunk, dry, rnNoiseMix, rnNoiseAttenFloor)
                logStageStats("post-rnnoise", chunk)
                rnNoiseAccumulator?.appendAll(chunk)
            }
            dynamicsFilter?.let {
                it.processInPlace(chunk)
                logStageStats("post-DP", chunk)
                dynamicsAccumulator?.appendAll(chunk)
            }
            agcFilter?.let {
                it.processInPlace(chunk)
                val s = AudioStatsAnalyzer.quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-AGC       peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f gain=%.2fx env=%.4f",
                    s.peak, s.peakDbFs, s.rms, s.rmsDbFs, s.zeroCrossingRate,
                    it.currentGainLinear(), it.currentEnvelope(),
                )
                agcAccumulator?.appendAll(chunk)
            }
            if (aiGain != 1f) {
                if (aiGainSoftSat) {
                    AudioDsp.applyAiGainSoftSatInPlace(chunk, aiGain)
                } else {
                    AudioDsp.applyAiGainInPlace(chunk, aiGain)
                }
                val s = AudioStatsAnalyzer.quickChunkStats(chunk)
                Timber.tag(TAG).d(
                    "      ↳ post-aigain    peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f gain=%.2fx [%s]",
                    s.peak, s.peakDbFs, s.rms, s.rmsDbFs, s.zeroCrossingRate, aiGain,
                    if (aiGainSoftSat) "tanh-soft-sat" else "linear+clip",
                )
            }
            lpf?.let {
                it.processInPlace(chunk)
                logStageStats("post-LPF", chunk)
                lpfAccumulator?.appendAll(chunk)
            }
        }

        /**
         * Soften RNNoise output: clamp each cleaned sample's magnitude to be
         * at least [floor] of the dry sample's magnitude, then linearly blend
         * cleaned vs. dry by [mix]. All in place on [wet].
         *
         *  - `mix == 1f && floor == 0f`  → no-op (caller skips this)
         *  - `mix == 0f`                 → pure dry (RNNoise effectively bypassed)
         *  - `floor == 0.5f`             → output magnitude never < 50 % of dry
         */
        private fun softenRnNoise(wet: ShortArray, dry: ShortArray, mix: Float, floor: Float) {
            val n = minOf(wet.size, dry.size)
            for (i in 0 until n) {
                val d = dry[i].toInt()
                var w = wet[i].toInt()
                if (floor > 0f && d != 0) {
                    val absDry = if (d < 0) -d else d
                    val minAbs = (absDry * floor).toInt()
                    val absWet = if (w < 0) -w else w
                    if (absWet < minAbs) {
                        // Restore magnitude floor while preserving wet's sign
                        // (or, if wet is exactly zero, fall back to dry's sign).
                        val sign = when {
                            w > 0 -> 1
                            w < 0 -> -1
                            d >= 0 -> 1
                            else -> -1
                        }
                        w = sign * minAbs
                    }
                }
                val blended = if (mix >= 1f) w
                else (w * mix + d * (1f - mix)).toInt()
                wet[i] = blended.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        /** Write the per-stage WAV artifacts that have accumulated samples. */
        fun persistArtifacts(
            artifactDir: File,
            sourceLabel: String,
            lowPass: LowPassConfig?,
            notch: NotchConfig?,
            agc: AGCConfig?,
            dynamics: DynamicsConfig?,
            declick: DeclickConfig?,
            sampleRate: Int = AudioTestFeeder.TARGET_SAMPLE_RATE,
        ) {
            if (declickFilter != null && declickAccumulator != null && declick != null && declickAccumulator.isNotEmpty()) {
                val tag = "thr%.0fxMAD-min%.0fdBFS-max%dsmp".format(
                    declick.thresholdMad, declick.minPeakDbFs, declick.maxTickSamples,
                ).replace(',', '.')
                val name = "${System.currentTimeMillis()}-$sourceLabel-declick-$tag.wav"
                writeStageWav(artifactDir, name, declickAccumulator, sourceLabel, "de-clicked", sampleRate)
            }
            if (dynamicsFilter != null && dynamicsAccumulator != null && dynamics != null && dynamicsAccumulator.isNotEmpty()) {
                val tag = "in%+.0fdB-b1_%.0fto%.0f-r%.0f".format(
                    dynamics.inputGainDb,
                    dynamics.band0.highEdgeHz, dynamics.band1.highEdgeHz,
                    dynamics.band1.ratio,
                ).replace(',', '.')
                val name = "${System.currentTimeMillis()}-$sourceLabel-dp-$tag.wav"
                writeStageWav(artifactDir, name, dynamicsAccumulator, sourceLabel, "dynamics-processed", sampleRate)
            }
            if (lpf != null && lpfAccumulator != null && lowPass != null && lpfAccumulator.isNotEmpty()) {
                val name = "${System.currentTimeMillis()}-$sourceLabel-lowpass-" +
                    "${lowPass.cutoffHz.toInt()}Hz-${lowPass.rollOffDbPerOctave.toInt()}dboct.wav"
                writeStageWav(artifactDir, name, lpfAccumulator, sourceLabel, "low-pass-filtered", sampleRate)
            }
            if (notchFilter != null && notchAccumulator != null && notch != null && notchAccumulator.isNotEmpty()) {
                val tag = if (notch.harmonics != null) {
                    notch.harmonics.joinToString(separator = "_") {
                        "${it.frequencyHz.toInt()}HzQ${it.q.toInt()}"
                    }
                } else {
                    "${notch.fundamentalHz.toInt()}Hz-Q${notch.q.toInt()}-x${notch.numHarmonics}"
                }
                val name = "${System.currentTimeMillis()}-$sourceLabel-notch-$tag.wav"
                writeStageWav(artifactDir, name, notchAccumulator, sourceLabel, "notch-filtered", sampleRate)
            }
            if (rnNoiseProcessor != null && rnNoiseAccumulator != null && rnNoiseAccumulator.isNotEmpty()) {
                val name = "${System.currentTimeMillis()}-$sourceLabel-rnnoise.wav"
                writeStageWav(artifactDir, name, rnNoiseAccumulator, sourceLabel, "RNNoise-cleaned", sampleRate)
            }
            if (agcFilter != null && agcAccumulator != null && agc != null && agcAccumulator.isNotEmpty()) {
                val tag = "tgt%.2f-atk%.0fms-rel%.0fms-max%.0fdB"
                    .format(agc.targetLevel, agc.attackMs, agc.releaseMs, agc.maxGainDb)
                    .replace(',', '.')
                val name = "${System.currentTimeMillis()}-$sourceLabel-agc-$tag.wav"
                writeStageWav(artifactDir, name, agcAccumulator, sourceLabel, "AGC-processed", sampleRate)
            }
        }

        /** Release native resources (RNNoise) + log per-source declick summary. */
        fun release(sourceLabel: String) {
            rnNoiseProcessor?.let {
                runCatching { it.release() }.onFailure { t ->
                    Timber.tag(TAG).w(t, "RnNoise release failed for %s", sourceLabel)
                }
            }
            declickFilter?.let {
                Timber.tag(TAG).i(
                    "  ⌁ Declick summary for %s: %d tick(s) repaired",
                    sourceLabel, it.totalTicks(),
                )
                it.reset()
            }
        }

        private fun logStageStats(label: String, chunk: ShortArray) {
            val s = AudioStatsAnalyzer.quickChunkStats(chunk)
            Timber.tag(TAG).d(
                "      ↳ %-13s peak=%d (%.1f dBFS) rms=%.1f (%.1f dBFS) zcr=%.3f",
                label, s.peak, s.peakDbFs, s.rms, s.rmsDbFs, s.zeroCrossingRate,
            )
        }

        private fun ArrayList<Short>.appendAll(chunk: ShortArray) {
            for (s in chunk) add(s)
        }

        companion object {
            /**
             * Build a chain from the given configs. Each enabled stage gets a
             * fresh filter instance + a sample accumulator pre-sized to the
             * full pcm length. Logs which stages are enabled.
             */
            fun build(
                notch: NotchConfig?,
                rnNoise: RnNoiseConfig?,
                agc: AGCConfig?,
                lowPass: LowPassConfig?,
                dynamics: DynamicsConfig?,
                declick: DeclickConfig?,
                aiGain: Float,
                aiGainSoftSat: Boolean,
                sampleRate: Int = AudioTestFeeder.TARGET_SAMPLE_RATE,
                expectedSize: Int,
            ): LiveFilterChain {
                val declickFilter = declick?.takeIf { it.enabled }?.let {
                    Timber.tag(TAG).i(
                        "  ⌁ Declick ENABLED: %s @ %d Hz (applied FIRST per chunk — kills impulsive spikes before notch/RNNoise/DP/AGC see them)",
                        it.describe(), sampleRate,
                    )
                    DeclickFilter(sampleRateHz = sampleRate, config = it)
                }

                val dynamicsFilter = dynamics?.takeIf { it.enabled }?.let {
                    Timber.tag(TAG).i(
                        "  ⌁ DynamicsProcessing ENABLED: %s @ %d Hz (applied after RNNoise, before AGC — voice-first chain)",
                        it.describe(), sampleRate,
                    )
                    DynamicsProcessingFilter(sampleRateHz = sampleRate, config = it)
                }

                val notchFilter = notch?.takeIf { it.enabled }?.let {
                    val resolvedBands = it.resolveBands()
                    Timber.tag(TAG).i(
                        "  ⌁ Notch ENABLED: %s → %d band(s): %s (applied first per chunk @ %d Hz so DP/AGC don't pump on tones)",
                        it.describe(), resolvedBands.size,
                        resolvedBands.joinToString(",") { b -> "${b.frequencyHz.toInt()}Hz@Q${b.q.toInt()}" },
                        sampleRate,
                    )
                    NotchFilter(sampleRate, resolvedBands)
                }

                val rnNoiseProcessor = rnNoise?.takeIf { it.enabled }?.let {
                    Timber.tag(TAG).i(
                        "  ⌁ RnNoise ENABLED: %s (applied per chunk @ %d Hz after notch, before DP/AGC/AI/LPF — fed at natural mic level)",
                        it.describe(), sampleRate,
                    )
                    val proc = com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor().apply {
                        init(sampleRate)
                    }
                    if (proc.isActive()) {
                        Timber.tag(TAG).i("  ✓ RnNoise native backend active: %s", proc.activeClassName())
                    } else {
                        Timber.tag(TAG).w(
                            "  ⚠ RnNoise is enabled but running in PASS-THROUGH mode " +
                                "(native librnnoise_jni.so not loaded / wrapper class not on classpath). " +
                                "The `*-rnnoise.wav` artifact will equal the post-notch audio. " +
                                "Fix: run ./stardust/scripts/setup_rnnoise.sh then rebuild :stardust.",
                        )
                    }
                    proc
                }

                val agcFilter = agc?.takeIf { it.enabled }?.let {
                    Timber.tag(TAG).i(
                        "  ⌁ AGC ENABLED: %s (applied per chunk @ %d Hz after DP, before AI-gain/LPF)",
                        it.describe(), sampleRate,
                    )
                    AGCFilter(
                        sampleRateHz = sampleRate,
                        targetLevel = it.targetLevel,
                        attackMs = it.attackMs,
                        releaseMs = it.releaseMs,
                        maxGainDb = it.maxGainDb,
                        minGainDb = it.minGainDb,
                        noiseGateLevel = it.noiseGateLevel,
                    )
                }

                val lpf = lowPass?.takeIf { it.enabled }?.let {
                    Timber.tag(TAG).i(
                        "  ⌁ Low-pass ENABLED: cutoff=%.1f Hz, roll-off=%.1f dB/oct @ %d Hz (applied LAST per chunk, after AI-gain)",
                        it.cutoffHz, it.rollOffDbPerOctave, sampleRate,
                    )
                    LowPassFilter(
                        sampleRateHz = sampleRate,
                        cutoffHz = it.cutoffHz,
                        rollOffDbPerOctave = it.rollOffDbPerOctave,
                    )
                }

                if (aiGain != 1f) {
                    Timber.tag(TAG).i(
                        "  ⌁ AI gain ENABLED: %.2fx [%s] (applied per chunk @ %d Hz after AGC, before LPF — post-leveller make-up so AGC can't undo it)",
                        aiGain,
                        if (aiGainSoftSat) "tanh soft-sat (no hard clip)" else "linear + int16 hard-clip",
                        sampleRate,
                    )
                } else {
                    Timber.tag(TAG).i("  ⌁ AI gain: 1.00x (passthrough)")
                }

                return LiveFilterChain(
                    dynamicsFilter = dynamicsFilter,
                    notchFilter = notchFilter,
                    rnNoiseProcessor = rnNoiseProcessor,
                    rnNoiseMix = rnNoise?.mixClamped ?: 1f,
                    rnNoiseAttenFloor = rnNoise?.attenuationFloorLin ?: 0f,
                    agcFilter = agcFilter,
                    lpf = lpf,
                    declickFilter = declickFilter,
                    aiGain = aiGain,
                    aiGainSoftSat = aiGainSoftSat,
                    declickLogDetections = declick?.logDetections ?: false,
                    dynamicsAccumulator = if (dynamicsFilter != null) ArrayList(expectedSize) else null,
                    notchAccumulator = if (notchFilter != null) ArrayList(expectedSize) else null,
                    rnNoiseAccumulator = if (rnNoiseProcessor != null) ArrayList(expectedSize) else null,
                    agcAccumulator = if (agcFilter != null) ArrayList(expectedSize) else null,
                    lpfAccumulator = if (lpf != null) ArrayList(expectedSize) else null,
                    declickAccumulator = if (declickFilter != null) ArrayList(expectedSize) else null,
                )
            }
        }
    }

    private fun writeStageWav(
        artifactDir: File,
        fileName: String,
        accumulator: ArrayList<Short>,
        sourceLabel: String,
        kind: String,
        sampleRate: Int = AudioTestFeeder.TARGET_SAMPLE_RATE,
    ) {
        try {
            artifactDir.mkdirs()
            val out = File(artifactDir, fileName)
            val arr = ShortArray(accumulator.size) { accumulator[it] }
            AudioArtifactWriter.writePcm16Wav(out, arr, sampleRate)
            Timber.tag(TAG).i(
                "  💾 Wrote %s WAV: %s (%d samples @ %d Hz, %d ms)",
                kind, out.absolutePath, arr.size, sampleRate,
                (arr.size * 1000L) / sampleRate,
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write %s artifact WAV for %s", kind, sourceLabel)
        }
    }


    /**
     * Copy the SOURCE file as-is into [artifactDir] before normalization.
     *
     * Unlike [persistOriginalNormalizedWav] (which writes the post-normalize
     * 24 kHz mono 16-bit PCM as a WAV), this preserves the input byte-for-byte
     * — same sample rate / channels / bit depth / container — so you can hear
     * exactly what was on disk before the feeder touched it. Useful when
     * comparing devices or debugging codec / decoder paths that depend on the
     * native format (e.g. m4a / 48 kHz stereo).
     *
     * The artifact filename keeps the original extension (or `.pcm` for
     * `Source.rawPcm`) plus the source label and a timestamp so it sits
     * alphabetically next to the other per-source artifacts.
     */
    private fun persistOriginalSourceFile(source: Source, artifactDir: File) {
        try {
            artifactDir.mkdirs()
            val ext = when {
                source.rawPcm -> "pcm"
                source.file.extension.isNotEmpty() -> source.file.extension
                else -> "bin"
            }
            val out = File(
                artifactDir,
                "${System.currentTimeMillis()}-${source.label}-original_source.$ext",
            )
            source.file.inputStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            Timber.tag(TAG).i(
                "  💾 Wrote original SOURCE file (pre-normalize, %d bytes): %s",
                out.length(), out.absolutePath,
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).e(
                t, "Failed to copy original source file for %s (path=%s)",
                source.label, source.file.absolutePath,
            )
        }
    }

    /**
     * Snapshot the normalized 24 kHz mono 16-bit PCM (before any live
     * filtering) to a WAV artifact. This gives every feed run a clean
     * reference file alongside the per-stage filter artifacts, regardless
     * of whether [roundTrip] was requested (the round-trip path already
     * writes a similar file via [RoundTripAnalyzer], but only when enabled).
     */
    private fun persistOriginalNormalizedWav(
        pcm: ShortArray,
        source: Source,
        artifactDir: File,
    ) {
        try {
            artifactDir.mkdirs()
            val out = File(
                artifactDir,
                "${System.currentTimeMillis()}-${source.label}-original_24k_mono.wav",
            )
            AudioArtifactWriter.writePcm16Wav(out, pcm, AudioTestFeeder.TARGET_SAMPLE_RATE)
            Timber.tag(TAG).i(
                "  💾 Wrote original (pre-filter) WAV: %s (%d samples, %d ms)",
                out.absolutePath, pcm.size,
                (pcm.size * 1000L) / AudioTestFeeder.TARGET_SAMPLE_RATE,
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write original (pre-filter) artifact WAV for %s", source.label)
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

    /**
     * Run [pcm] through the full DSP chain in a single batch using
     * **fresh, isolated** filter instances, and return the filtered copy.
     *
     * Order matches the live per-chunk chain built in [LiveFilterChain]:
     *   Declick → Notch → RNNoise → DynamicsProcessing → AGC → AI-gain → LPF
     *
     * Used to build the input buffer for [RoundTripAnalyzer] so the encoder
     * sees the same signal the live chunk emitter ships to `PttSendManager`.
     * The live chain (assembled separately in `feedSingle`) keeps its own
     * fresh state — their biquad memories / AGC envelope / RNNoise residual
     * tail are unaffected by this one-shot pass.
     *
     * If a stage is `null` or `enabled == false` it is skipped, matching the
     * live behaviour. Stages that allocate native resources (RNNoise) are
     * released before returning.
     */
    private fun applyFilterChainOneShot(
        pcm: ShortArray,
        lowPass: LowPassConfig?,
        notch: NotchConfig?,
        rnNoise: RnNoiseConfig?,
        agc: AGCConfig?,
        dynamics: DynamicsConfig?,
        declick: DeclickConfig?,
        aiGain: Float,
        aiGainSoftSat: Boolean,
    ): ShortArray {
        // Fast path: no stages → return the input unchanged. The round-trip
        // analyzer doesn't mutate its input, so we can hand it the same array.
        val anyEnabled = (notch?.enabled == true) || (rnNoise?.enabled == true) ||
            (agc?.enabled == true) || (lowPass?.enabled == true) ||
            (dynamics?.enabled == true) || (declick?.enabled == true) || aiGain != 1f
        if (!anyEnabled) return pcm

        // Operate on a defensive copy — round-trip is supposed to be idempotent
        // and we don't want to disturb the original pcm that the live chunk
        // emitter is about to consume.
        val out = pcm.copyOf()

        // Voice-first order — declick first kills impulsive spikes so the
        // stateful filters downstream never see them; notch then kills tones
        // so DP/AGC don't pump on them; RNNoise sees natural levels (its
        // training distribution); DP shapes; AGC drives to target; AI gain
        // is post-leveller make-up (so AGC can't undo it); LPF runs last to
        // anti-alias before the encoder.
        declick?.takeIf { it.enabled }?.let { cfg ->
            // Chunk-by-chunk so the per-chunk MAD tracker mirrors the live
            // chain exactly (single DeclickFilter instance, state preserved
            // across slices).
            val det = DeclickFilter(sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE, config = cfg)
            val chunkSize = AudioTestFeeder.SAMPLES_PER_CHUNK
            val slice = ShortArray(chunkSize)
            var totalTicks = 0
            var i = 0
            while (i < out.size) {
                val len = minOf(chunkSize, out.size - i)
                System.arraycopy(out, i, slice, 0, len)
                val sliceForDet = if (len == chunkSize) slice else slice.copyOf(len)
                val events = det.processInPlace(sliceForDet)
                totalTicks += events.size
                if (len == chunkSize) {
                    System.arraycopy(sliceForDet, 0, out, i, len)
                } else {
                    // Copy back from the trimmed buffer the detector mutated.
                    System.arraycopy(sliceForDet, 0, out, i, len)
                }
                i += len
            }
            Timber.tag(TAG).d(
                "  ↻ Round-trip declick: %d tick(s) repaired across %d samples", totalTicks, out.size,
            )
        }
        notch?.takeIf { it.enabled }?.let { cfg ->
            val staticBands = cfg.resolveBands()
            val nf = NotchFilter(
                sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE,
                bands = staticBands,
            )
            val adaptive = cfg.adaptive
            if (adaptive == null) {
                nf.processInPlace(out)
            } else {
                // Chunk-by-chunk so the per-chunk detector mirrors the live
                // path. NotchFilter keeps biquad state across the per-chunk
                // calls (just like in LiveFilterChain), so the output is
                // continuous.
                val det = AdaptiveNotchDetector(AudioTestFeeder.TARGET_SAMPLE_RATE, adaptive)
                val chunkSize = AudioTestFeeder.SAMPLES_PER_CHUNK
                val bucketHz = 5
                val slice = ShortArray(chunkSize)
                var i = 0
                while (i < out.size) {
                    val len = minOf(chunkSize, out.size - i)
                    System.arraycopy(out, i, slice, 0, len)
                    val sliceForDetector = if (len == chunkSize) slice else slice.copyOf(len)
                    val newBands = det.detectAndTrack(sliceForDetector)
                    if (newBands != null) {
                        val merged: List<NotchFilter.Band> = if (newBands.isEmpty()) {
                            staticBands
                        } else {
                            val byBucket = HashMap<Int, NotchFilter.Band>(staticBands.size + newBands.size)
                            for (b in staticBands) {
                                val k = (b.frequencyHz / bucketHz).roundToInt()
                                val prev = byBucket[k]
                                if (prev == null || b.q > prev.q) byBucket[k] = b
                            }
                            for (b in newBands) {
                                val k = (b.frequencyHz / bucketHz).roundToInt()
                                val prev = byBucket[k]
                                if (prev == null || b.q > prev.q) byBucket[k] = b
                            }
                            byBucket.values.sortedBy { it.frequencyHz }
                        }
                        nf.configure(bands = merged)
                    }
                    nf.processInPlace(slice, len)
                    System.arraycopy(slice, 0, out, i, len)
                    i += len
                }
            }
        }

        rnNoise?.takeIf { it.enabled }?.let {
            val proc = com.commcrete.stardust.ai.codec.filter.RnNoiseProcessor().apply {
                init(AudioTestFeeder.TARGET_SAMPLE_RATE)
            }
            try {
                proc.process(out, out.size)
            } finally {
                runCatching { proc.release() }
            }
        }

        dynamics?.takeIf { it.enabled }?.let {
            DynamicsProcessingFilter(
                sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE,
                config = it,
            ).processInPlace(out)
        }

        agc?.takeIf { it.enabled }?.let {
            AGCFilter(
                sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE,
                targetLevel = it.targetLevel,
                attackMs = it.attackMs,
                releaseMs = it.releaseMs,
                maxGainDb = it.maxGainDb,
                minGainDb = it.minGainDb,
                noiseGateLevel = it.noiseGateLevel,
            ).processInPlace(out)
        }

        if (aiGain != 1f) {
            if (aiGainSoftSat) AudioDsp.applyAiGainSoftSatInPlace(out, aiGain)
            else AudioDsp.applyAiGainInPlace(out, aiGain)
        }

        lowPass?.takeIf { it.enabled }?.let {
            LowPassFilter(
                sampleRateHz = AudioTestFeeder.TARGET_SAMPLE_RATE,
                cutoffHz = it.cutoffHz,
                rollOffDbPerOctave = it.rollOffDbPerOctave,
            ).processInPlace(out)
        }

        Timber.tag(TAG).i(
            "  ↻ Round-trip will run on FILTERED audio (declick=%s, notch=%s, rnnoise=%s, dp=%s, agc=%s, aiGain=%.2fx[%s], lpf=%s)",
            declick?.takeIf { it.enabled }?.describe() ?: "off",
            notch?.takeIf { it.enabled }?.describe() ?: "off",
            rnNoise?.takeIf { it.enabled }?.describe() ?: "off",
            dynamics?.takeIf { it.enabled }?.describe() ?: "off",
            agc?.takeIf { it.enabled }?.describe() ?: "off",
            aiGain,
            if (aiGainSoftSat) "tanh" else "linear+clip",
            lowPass?.takeIf { it.enabled }
                ?.let { "${it.cutoffHz.toInt()}Hz/${it.rollOffDbPerOctave.toInt()}dBoct" } ?: "off",
        )
        return out
    }
}







