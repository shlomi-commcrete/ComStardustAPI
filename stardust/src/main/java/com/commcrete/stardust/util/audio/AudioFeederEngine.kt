package com.commcrete.stardust.util.audio

import android.content.Context
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.tester.AudioFileLoader
import com.commcrete.stardust.util.audio.tester.AudioTestFeeder
import com.commcrete.stardust.util.audio.tester.Source
import kotlinx.coroutines.CoroutineScope
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
 * artifact persistence (filtered WAVs) is delegated to [com.commcrete.stardust.util.audio.tester.AudioArtifactWriter].
 */
internal object AudioFeederEngine {

    private const val TAG = "AudioTestFeeder"

    /**
     * Build a CODEC2 send pipeline. When [destination] is `null`, encoded
     * CODEC2 packets are still produced (so the pipeline can drain cleanly
     * via [Codec2SendPipeline.finish]) but **no** BLE/USB transmission
     * occurs — used when the test feeder is invoked without `withSend = true`.
     *
     * This is the **same** pipeline the live `WavRecorder` path uses, so
     * audio fed by the test feeder hits identical encode → pack → send
     * code as a real microphone recording.
     *
     * [onEncodedFrame] mirrors the same parameter in the live
     * [AudioRecorderCodec2] pipeline: the feeder uses it to run each
     * 4-byte CODEC2 frame back through a [com.ustadmobile.codec2.Codec2Decoder]
     * and accumulate the decoded PCM bytes so they can be written to the
     * artifact directory — exactly the decoded-WAV artifact that
     * [AudioRecorderCodec2.mirrorDecodedArtifact] produces during live
     * recording.
     */
    internal fun createCodec2Pipeline(
        context: Context,
        destination: String?,
        carrier: Carrier?,
        onEncodedFrame: ((ByteArray) -> Unit)? = null,
    ): Codec2SendPipeline = Codec2SendPipeline(
        context = context,
        carrier = carrier,
        sourceProvider = { DataManager.getSource() },
        destinationProvider = { destination },
        onEncodedFrame = onEncodedFrame,
    )

    suspend fun feedSingle(
        context: Context,
        receiverId: String,
        chatId: String,
        carrier: Carrier?,
        source: Source,
        codeType: RecorderUtils.CODE_TYPE,
        codec2Pipeline: Codec2SendPipeline?,
        realtimePacing: Boolean,
        artifactDir: File,
        enableNoiseCancellation: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (!source.file.exists() || !source.file.canRead()) {
            Timber.tag(TAG).w("Skipping unreadable file: %s", source.file.absolutePath)
            return@withContext
        }
        RecorderUtils.dirToSaveFile = artifactDir

        // 0. Snapshot the SOURCE file as-is (pre-normalization) — see
        //    [persistOriginalSourceFile] for the rationale.
        persistOriginalSourceFile(source, artifactDir)

        // 1. Load mono PCM at the source's NATIVE sample rate (no resampling
        //    yet) — the live per-chunk filter chain will run at this rate so
        //    DSP happens before resampling. Cross-source stats use the same
        //    native-rate buffer so the analyzer is comparing apples to apples
        //    across devices (24 kHz mic vs 48 kHz jbox).
        val (info, nativeRate, monoNative) = AudioFileLoader.loadMono(source)



        // 2. Stream the PCM as chunks at native rate. Each raw chunk is
        //    handed to [PttAudioProcessor.process] which applies the same
        //    DSP chain + resample step the live recording uses, then we
        //    route the processed chunk to the encoder.
        //
        //    NOTE: NO per-source reset of [PttAudioProcessor] / decoder
        //    state here — both are wiped once at the feed-level via
        //    `PttSendManager.restart()` (which calls
        //    `PttAudioProcessor.reset()` internally). Multi-source feed
        //    runs share state across sources, mirroring how the live
        //    recorder shares state across consecutive chunks within one
        //    PTT session.
        logChunkPlan(monoNative, nativeRate, codeType)
        val targetRate = when (codeType) {
            RecorderUtils.CODE_TYPE.AI -> AudioTestFeeder.TARGET_SAMPLE_RATE
            RecorderUtils.CODE_TYPE.CODEC2 -> Codec2SendPipeline.TARGET_SAMPLE_RATE
        }
        val flowKey = "${codeType.name}-feeder"

        val sinkFile: File? = if (codeType == RecorderUtils.CODE_TYPE.AI) {
            createSinkFile(context, chatId ?: "${source.file.name}_codec", source.label)
        } else {
            null
        }
        val aiInputWriter = if (codeType == RecorderUtils.CODE_TYPE.AI) {
            com.commcrete.stardust.ai.codec.testing.DebugRawWavWriter().apply {
                start(
                    context = context,
                    sampleRate = AudioTestFeeder.TARGET_SAMPLE_RATE,
                    channels = 1,
                    bitsPerSample = 16,
                    fileNamePrefix = "${source.label}-ai_input_24k",
                    outputDir = artifactDir,
                )
            }
        } else {
            null
        }
        // Parallel of `aiInputWriter` for the CODEC2 path: captures the
        // exact 8 kHz mono PCM that is fed into the Codec2 encoder, so the
        // resulting WAV matches what the encoder actually sees (post-DSP,
        // post-resample). Filename mirrors the AI writer with "ai" → "codec"
        // and the rate suffix updated to the CODEC2 target (8 kHz).
        val codecInputWriter = if (codeType == RecorderUtils.CODE_TYPE.CODEC2) {
            com.commcrete.stardust.ai.codec.testing.DebugRawWavWriter().apply {
                start(
                    context = context,
                    sampleRate = Codec2SendPipeline.TARGET_SAMPLE_RATE,
                    channels = 1,
                    bitsPerSample = 16,
                    fileNamePrefix = "${source.label}-codec_input_8k",
                    outputDir = artifactDir,
                )
            }
        } else {
            null
        }

        val inputGain = SharedPreferencesUtil.getAudioGain() / 100f

        val emission = try {
            streamPcmAsChunks(
                pcm = monoNative,
                sampleRate = nativeRate,
                realtimePacing = realtimePacing,
            ) { rawChunk, chunkIndex ->
                // Apply input gain BEFORE DSP — same as live recorders.
                val gained = if (inputGain != 1f) {
                    if (codeType == RecorderUtils.CODE_TYPE.AI) {
                        // Soft-clip tanh — matches AudioRecorderAI.processSamples
                        AudioDsp.applyAiGainSoftSatInPlace(rawChunk, inputGain)
                    } else {
                        // Hard-clip — matches AudioRecorderCodec2 gain path
                        AudioDsp.applyAiGainInPlace(rawChunk, inputGain)
                    }
                    rawChunk
                } else {
                    rawChunk
                }

                // DSP chain + resample to encoder target rate.
                val processed = PttAudioProcessor.process(
                    pcmArray = gained,
                    nativeRate = nativeRate,
                    targetRate = targetRate,
                    enableNoiseCancellation = enableNoiseCancellation,
                )
                if (codeType == RecorderUtils.CODE_TYPE.AI) {
                    routeChunkToAi(
                        processedChunk = processed,
                        sinkFile = sinkFile ?: return@streamPcmAsChunks,
                        carrier = carrier,
                        chatId = chatId,
                        receiverId = receiverId,
                        aiInputWriter = aiInputWriter,
                    )
                } else {
                    routeChunkToCodec2(
                        processedChunk = processed,
                        codec2Pipeline = codec2Pipeline,
                        codecInputWriter = codecInputWriter,
                    )
                }
            }
        } finally {
            // Drain any in-flight encoded chunks before closing the
            // diagnostic writer (PttSendManager's encoding loop polls
            // every ~10 ms, so a short grace period catches the tail).
            runCatching { delay(150) }
            if (codeType == RecorderUtils.CODE_TYPE.AI) {
                PttSendManager.onDecodedChunk = null
                runCatching { aiInputWriter?.stop() }
            } else {
                runCatching { codecInputWriter?.stop() }
            }
        }
        Timber.tag(TAG).i(
            "  ✓ Finished %s: %d chunks, %d samples @ %d Hz (%d ms audio) in %d ms wall-clock",
            source.label, emission.chunkCount, emission.samplesProcessed, nativeRate,
            (emission.samplesProcessed * 1000L) / nativeRate,
            emission.elapsedMs,
        )
    }

    /**
     * Save a copy of the source file into [artifactDir] in the same
     * format the feeder uses (`*-original_source.wav`), without any
     * DSP processing or encoding.
     *
     * Use this to capture raw recordings for later analysis or
     * offline feeding via [feedSingle].
     */
    suspend fun saveOnly(
        source: Source,
        artifactDir: File,
    ) = withContext(Dispatchers.IO) {
        if (!source.file.exists() || !source.file.canRead()) {
            Timber.tag(TAG).w("Skipping unreadable file: %s", source.file.absolutePath)
            return@withContext
        }

        artifactDir.mkdirs()
        persistOriginalSourceFile(source, artifactDir)

        Timber.tag(TAG).i("  ✓ saveOnly %s → %s", source.label, artifactDir.absolutePath)
    }

    // ─── feedSingle phases ───────────────────────────────────────────────────

    /**
     * Hand a fully-processed (filtered + resampled to 24 kHz by
     * `PttAudioProcessor.process`) chunk to the AI encoder via the
     * **same** entry point the live recorder uses
     * (`PttSendManager.addNewFrame`).
     *
     * No tail-special-casing — partial chunks are forwarded verbatim,
     * exactly the way `RecorderUtils.forwardAiChunk` forwards the
     * `AudioRecorderAI.onPartialFinalChunk` callback. Keeping the byte
     * stream identical to live is the entire point of routing through
     * one shared `addNewFrame` call.
     */
    private fun routeChunkToAi(
        processedChunk: ShortArray,
        sinkFile: File,
        carrier: Carrier?,
        receiverId: String,
        chatId: String,
        aiInputWriter: com.commcrete.stardust.ai.codec.testing.DebugRawWavWriter?,
    ) {
        // Diagnostic-only observer; doesn't alter the audio path.
        aiInputWriter?.append(processedChunk, processedChunk.size)
        PttSendManager.addNewFrame(
            pcmArray = processedChunk,
            file = sinkFile,
            carrier = carrier,
            receiverId = receiverId,
            chatId = chatId,
        )
    }

    /**
     * Hand a fully-processed (filtered + resampled to 8 kHz by
     * `PttAudioProcessor.process`) chunk to the **shared**
     * [Codec2SendPipeline] — the same pipeline the live `WavRecorder`
     * path now uses. Captures the exact bytes the encoder will consume
     * into [codecInputWriter] for the `*-codec_input_8k.wav` artifact.
     */
    private fun routeChunkToCodec2(
        processedChunk: ShortArray,
        codec2Pipeline: Codec2SendPipeline?,
        codecInputWriter: com.commcrete.stardust.ai.codec.testing.DebugRawWavWriter?,
    ) {
        codecInputWriter?.append(processedChunk, processedChunk.size)
        codec2Pipeline?.enqueuePcm(processedChunk)
    }

    /** Log how many full / partial chunks the upcoming emission will produce. */
    private fun logChunkPlan(pcm: ShortArray, sampleRate: Int, codeType: RecorderUtils.CODE_TYPE) {
        val samplesPerChunk = (sampleRate * AudioTestFeeder.CHUNK_DURATION_MS / 1000L).toInt()
        val fullChunks = pcm.size / samplesPerChunk
        val tailSamples = pcm.size % samplesPerChunk
        val tailMs = (tailSamples * 1000L) / sampleRate
        val targetRate = if (codeType == RecorderUtils.CODE_TYPE.AI) {
            AudioTestFeeder.TARGET_SAMPLE_RATE
        } else {
            Codec2SendPipeline.TARGET_SAMPLE_RATE
        }
        Timber.tag(TAG).i(
            "  → Emitting %d full chunk(s) of %d samples (%d ms each) @ %d Hz%s — filtered at native rate, resampled to %d Hz before %s",
            fullChunks, samplesPerChunk, AudioTestFeeder.CHUNK_DURATION_MS, sampleRate,
            if (tailSamples > 0) " + 1 partial/final chunk of $tailSamples samples (${tailMs} ms)" else "",
            targetRate,
            codeType.name,
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
     * Hands each fully-assembled raw chunk (and its index) to [onChunk].
     * The callback is responsible for any DSP / resampling — the feeder
     * delegates that to `PttAudioProcessor.process(...)` so the test path
     * stays in lock-step with the live recording path.
     *
     * @param onChunk receives `(rawChunkAtNativeRate, chunkIndex)`. The
     *                index is contiguous (0, 1, 2, …) including the
     *                partial-final chunk.
     */
    private suspend fun CoroutineScope.streamPcmAsChunks(
        pcm: ShortArray,
        sampleRate: Int,
        realtimePacing: Boolean,
        onChunk: (ShortArray, Int) -> Unit,
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
                        chunkIndex, onChunk,
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
                chunkIndex, onChunk,
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
     * Snapshot raw chunk stats (so we can log the unprocessed input
     * profile) and hand the raw chunk to [onChunk]. Filtering / resampling
     * is the callback's responsibility — typically delegated to
     * `PttAudioProcessor.process(...)` so the test feeder stays
     * bit-comparable with the live recording path.
     */
    private fun emitOneChunk(
        buffer: ShortArray,
        length: Int,
        chunkIndex: Int,
        onChunk: (ShortArray, Int) -> Unit,
    ) {
        val chunk = if (length == buffer.size) buffer.copyOf() else buffer.copyOf(length)

        onChunk(chunk, chunkIndex)
    }


    /**
     * Copy the SOURCE file as-is into [artifactDir] before normalization.
     *
     * Preserves the input byte-for-byte — same sample rate / channels /
     * bit depth / container — so you can hear exactly what was on disk
     * before the feeder touched it. Useful when comparing devices or
     * debugging codec / decoder paths that depend on the native format
     * (e.g. m4a / 48 kHz stereo).
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



