package com.commcrete.stardust.util.audio.tester

import android.content.Context
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.ai.codec.PttSession
import com.commcrete.stardust.ai.codec.testing.DebugRawWavWriter
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.audio.AudioFeederEngine
import com.commcrete.stardust.util.audio.PttAudioProcessor
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import com.ustadmobile.codec2.Codec2Decoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Test-only utility that simulates microphone recording for the AI codec pipeline.
 *
 * Reads one or more audio files captured on different devices (WAV, raw PCM,
 * or any container Android's `MediaCodec` can decode — m4a / mp3 / aac / ogg /
 * flac …) and feeds them into [com.commcrete.stardust.ai.codec.PttSendManager] in 500 ms chunks — exactly the
 * way `AudioRecorderAI` feeds live frames.
 *
 * Each file is analyzed and logged in great detail (header, statistics per
 * file and per emitted chunk) so audio quality issues can be diagnosed offline.
 *
 * NOT for production use.
 *
 * **Architecture (clean-architecture split):**
 *  - This object is the public **facade** — public API + last-run state.
 *  - **Model** (one class per file, all in this package):
 *    [Source], [LowPassConfig], [NotchConfig], [RnNoiseConfig], [AGCConfig],
 *    [AudioInfo], [AudioStats].
 *  - [com.commcrete.stardust.util.audio.AudioFeederEngine]      — orchestration of the per-source pipeline.
 *  - [AudioFileLoader]        — WAV / MediaCodec decoding to mono 16-bit PCM.
 *  - [com.commcrete.stardust.util.audio.AudioDsp]               — down-mix, resample, anti-alias, AI gain, FFT.
 *  - [AudioStatsAnalyzer]     — PCM stats + spectral fingerprint + tone alert.
 *  - [AudioArtifactWriter]    — WAV + token (txt/bin) writers.
 *
 *
 * Usage:
 * ```
 * AudioTestFeeder.feed(
 *     context = ctx,
 *     pathToSaveFilesIn = "deviceA-run1",
 *     carrier = currentCarrier,
 *     sources = listOf(
 *         Source(File("/sdcard/test/deviceA.wav"), label = "device-A"),
 *         Source(File("/sdcard/test/deviceB.wav"), label = "device-B"),
 *     ),
 *     realtimePacing = true,
 *     // Opt in to actually transmit each encoded chunk over the live
 *     // BLE/USB pathway, exactly like a real PTT recording would:
 *     destinationId = "DEV-001",
 *     withSend = true,
 * )
 * ```
 */
object AudioTestFeeder {

    const val TAG = "AudioTestFeeder"

    /** Target format expected by the AI pipeline (see `AudioRecorderAI`). */
    const val TARGET_SAMPLE_RATE = 24_000
    const val TARGET_CHANNELS = 1
    const val TARGET_BITS_PER_SAMPLE = 16
    const val CHUNK_DURATION_MS = 500
    const val SAMPLES_PER_CHUNK = (TARGET_SAMPLE_RATE * CHUNK_DURATION_MS / 1000L).toInt() // 12 000

//    private val feederScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
//    private var currentJob: Job? = null
//
//
//    // ---------------- Public model types ----------------
//    //
//    // The public model is now spread across one-class-per-file modules in this
//    // package (clean-architecture model layer):
//    //
//    //   • [Source]            — AudioFeederSource.kt
//    //   • [LowPassConfig]     — AudioLowPassConfig.kt
//    //   • [NotchConfig]       — AudioNotchConfig.kt
//    //   • [RnNoiseConfig]     — AudioRnNoiseConfig.kt
//    //   • [AGCConfig]         — AudioAgcConfig.kt
//    //   • [AudioInfo]         — AudioInfo.kt
//    //   • [AudioStats]        — AudioStats.kt
//    //
//    // They keep their original short names so callers continue to use them as
//    // `Source(...)`, `LowPassConfig(...)`, etc. (no qualifier needed inside
//    // this package; otherwise import them from
//    // `com.commcrete.stardust.util.audio`).
//
//    // ---------------- Public API ----------------
//
//    /**
//     * Feed the given audio [sources] into the AI pipeline as if they were just
//     * recorded. Cancels the previous job (if any) first.
//     *
//     * @param profile           per-device DSP configuration. The feeder
//     *                          honors the same gating semantics as the live
//     *                          path in `PttAudioProcessor.process`:
//     *                          - `profile == null` → no filters (resample only).
//     *                          - `profile.isActive == false` → no filters
//     *                            even if individual stages are populated.
//     *                          - Otherwise each stage is gated on its own
//     *                            non-null + `enabled = true` flag.
//     *                          Convenient construction:
//     *                          `PttAudioProcessor.getAiRecorderDefaultProfilePreset(deviceType)`
//     *                          or
//     *                          `PttAudioProcessor.getAiActiveRecorderProfile(deviceType)`.
//     * @param destinationId     real BLE/USB destination ID. Combined with
//     *                          [withSend], gates whether each encoded chunk is
//     *                          handed off to `DataManager.sendDataToBle(...)`
//     *                          via the same path the live PTT recorder uses.
//     * @param withSend          when `true` AND [destinationId] is non-blank,
//     *                          encoded chunks are transmitted to the device
//     *                          identified by [destinationId] using the exact
//     *                          same pathway as a live PTT recording (AI path:
//     *                          `PttSendManager.sendData` → `SEND_PTT_AI`;
//     *                          CODEC2 path: `Codec2SendPipeline.sendPacket` →
//     *                          `SEND_PTT`). When `false` (default), the feeder
//     *                          only generates artifacts and never touches the
//     *                          radio, regardless of [destinationId].
//     * @param realtimePacing if true, paces chunks like AudioRecord does;
//     *                       if false, pushes them back-to-back.
//     * @param outputDir      directory for per-source artifacts (input WAV
//     *                       snapshots, encoder-input mirrors).
//     * @param onDone         optional completion callback (IO dispatcher).
//     */
//    fun feed(
//        context: Context,
//        carrier: Carrier?,
//        codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
//        sources: List<Source>,
//        realtimePacing: Boolean = true,
//        outputDir: File? = null,
//        destinationId: String? = null,
//        withSend: Boolean = false,
//        chatId: String,
//        savePerFilterStage: Boolean = false,
//        onDone: (() -> Unit)? = null,
//    ): Job {
//        stop()
//        val sendTo: String? = destinationId?.takeIf { withSend && it.isNotBlank() }
//
//
//        val isAi = codeType == RecorderUtils.CODE_TYPE.AI
//        if (isAi) {
//            val pttInterface: PttInterface? = sendTo?.let { FeederPttInterface(chatId, destinationId) }
//            PttSendManager.init(pttInterface)
//        }
//
//        val job = feederScope.launch {
//            val effectiveOutputDir = outputDir ?: AudioArtifactWriter.defaultArtifactDir(context, sendTo ?: "artifact-only")
//            effectiveOutputDir.mkdirs()
//
//            val isCodec2 = codeType == RecorderUtils.CODE_TYPE.CODEC2
//
//            // Per-filter debug writers: one DebugRawWavWriter per filter
//            // step, created lazily on first callback. Keyed by "stepIdx-name".
//            val debugWriters = if (savePerFilterStage)
//                LinkedHashMap<String, DebugRawWavWriter>()
//            else null
//
//            try {
//                sources.forEachIndexed { idx, src ->
//                    if (!isActive) return@forEachIndexed
//                    Timber.Forest.tag(TAG).i("── [%d/%d] Source: %s (%s)", idx + 1, sources.size, src.label, src.file.absolutePath)
//
//                    // Set up per-filter debug hook if requested.
//                    if (savePerFilterStage) {
//                        // Close writers from previous source.
//                        debugWriters?.values?.forEach { runCatching { it.stop() } }
//                        debugWriters?.clear()
//
//                        PttAudioProcessor.onFilterStepDebug = { sampleRate, stepIdx, filterName, snapshot ->
//                            val key = "%02d-%s".format(stepIdx, filterName)
//                            val writer = debugWriters!!.getOrPut(key) {
//                                DebugRawWavWriter().apply {
//                                    start(
//                                        context = context,
//                                        sampleRate = sampleRate,
//                                        channels = 1,
//                                        bitsPerSample = 16,
//                                        fileNamePrefix = "${src.label}-$key",
//                                        outputDir = effectiveOutputDir,
//                                    )
//                                }
//                            }
//                            writer.append(snapshot, snapshot.size)
//                        }
//                    }
//
//                    // ── Per-source session: mirrors real PTT key-down →
//                    // record → key-up cycle. Each file gets fresh filter
//                    // state, fresh encoder/decoder state, and its own
//                    // output artifact.
//
//                    // AI path: restart PttSendManager session.
//                    val session: PttSession? =
//                        if (isAi) PttSendManager.restart() else null
//
//                    // CODEC2 path: fresh pipeline + decoder + data buffer
//                    // per source (mirrors AudioRecorderCodec2 lifecycle).
//                    val decodedCodec2Data = if (isCodec2) ArrayList<Byte>() else null
//                    val codec2FrameDecoder = if (isCodec2)
//                        Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
//                    else null
//                    val codec2Pipeline = if (isCodec2) {
//                        // Reset filter chain for the new "session".
//                        PttAudioProcessor.reset()
//                        AudioFeederEngine.createCodec2Pipeline(
//                            context = context,
//                            destination = sendTo,
//                            carrier = carrier,
//                            onEncodedFrame = { encodedFrame ->
//                                val byteBuffer = codec2FrameDecoder!!.readFrame(encodedFrame)
//                                val bDataCodec = byteBuffer.array()
//                                for (b in bDataCodec) decodedCodec2Data!!.add(b)
//                            },
//                        )
//                    } else {
//                        null
//                    }
//
//                    AudioFeederEngine.feedSingle(
//                        context = context,
//                        chatId = chatId,
//                        receiverId = sendTo,
//                        carrier = carrier,
//                        source = src,
//                        codeType = codeType,
//                        codec2Pipeline = codec2Pipeline,
//                        realtimePacing = realtimePacing,
//                        artifactDir = effectiveOutputDir,
//                    )
//
//                    // ── Finalize this source's session before the next.
//
//                    if (isAi && session != null) {
//                        Timber.Forest.tag(TAG).i("  ⏳ Finishing AI session ${session.id} for ${src.label}")
//                        PttSendManager.finish(session)
//                        PttSendManager.awaitFinalized(session)
//                        Timber.Forest.tag(TAG).i("  ✔ AI session ${session.id} finalized")
//                    }
//
//                    if (isCodec2) {
//                        Timber.Forest.tag(TAG).i("  ⏳ Flushing CODEC2 pipeline for ${src.label}")
//                        codec2Pipeline?.finish()
//                        // Write per-source decoded artifact.
//                        if (decodedCodec2Data != null && decodedCodec2Data.isNotEmpty()) {
//                            runCatching {
//                                val artifactDir = RecorderUtils.dirToSaveFile
//                                if (!artifactDir.exists()) artifactDir.mkdirs()
//                                val mirrorName = "${System.currentTimeMillis()}-${src.label}-codec2_decoded.wav"
//                                val mirror = File(artifactDir, mirrorName)
//                                val pcmBytes = decodedCodec2Data.toByteArray()
//                                mirror.writeBytes(buildWavHeader(pcmBytes.size, sampleRate = 8000, bitDepth = 16, channels = 1) + pcmBytes)
//                                Timber.Forest.tag(TAG).i("  ✔ CODEC2 decoded artifact → %s", mirror.absolutePath)
//                            }.onFailure { t ->
//                                Timber.Forest.tag(TAG).e(t, "Failed to write CODEC2 decoded artifact for ${src.label}")
//                            }
//                        }
//                    }
//                }
//            } catch (t: Throwable) {
//                Timber.Forest.tag(TAG).e(t, "Feeder failed")
//            } finally {
//                // Clean up per-filter debug writers and hook.
//                if (savePerFilterStage) {
//                    PttAudioProcessor.onFilterStepDebug = null
//                    debugWriters?.values?.forEach { runCatching { it.stop() } }
//                    debugWriters?.clear()
//                }
//                // Detach our feeder-local PttInterface so a subsequent
//                // production `RecorderUtils.startAIRecording` call cleanly
//                // re-registers DataManager without our stale adapter
//                // racing it. Cheap, idempotent.
//                if (isAi) {
//                    runCatching { PttSendManager.init() }
//                }
//                onDone?.invoke()
//            }
//        }
//        currentJob = job
//        return job
//    }
//
//    fun buildWavHeader(pcmSize: Int, sampleRate: Int, bitDepth: Int, channels: Int): ByteArray {
//        val byteRate = sampleRate * channels * bitDepth / 8
//        val blockAlign = (channels * bitDepth / 8).toShort()
//        return ByteArrayOutputStream(44).apply {
//            fun Int.le4() = byteArrayOf(toByte(), shr(8).toByte(), shr(16).toByte(), shr(24).toByte())
//            fun Short.le2() = byteArrayOf(toByte(), toInt().shr(8).toByte())
//            write("RIFF".toByteArray())
//            write((36 + pcmSize).le4())
//            write("WAVE".toByteArray())
//            write("fmt ".toByteArray())
//            write(16.le4())                          // subchunk1 size
//            write(1.toShort().le2())                 // PCM format
//            write(channels.toShort().le2())
//            write(sampleRate.le4())
//            write(byteRate.le4())
//            write(blockAlign.le2())
//            write(bitDepth.toShort().le2())
//            write("data".toByteArray())
//            write(pcmSize.le4())
//        }.toByteArray()
//    }
//
//
//    /**
//     * Minimal [PttInterface] used while [feed] is running with `withSend`
//     * enabled. `getDestenation()` returns the id passed to [feed]; everything
//     * else delegates to `DataManager` so we go through exactly the same
//     * BLE/USB write path as a live PTT recording.
//     */
//    private class FeederPttInterface(private val chatId: String, private val dest: String) : PttInterface {
//        override fun getChatId(): String = chatId
//
//        override fun getSource(): String = DataManager.getSource()
//        override fun getDestination(): String = dest
//        override fun sendDataToBle(bittelPackage: StardustPackage) {
//            DataManager.sendDataToBle(bittelPackage)
//        }
//    }
//
//
//    /**
//     * Save raw recordings without any DSP or encoding. Writes only:
//     *  - `*-original_source.wav` — byte-for-byte copy of the input file
//     *
//     * Same format as the feeder's original-source artifact, so saved
//     * files can be fed later via [feed] for offline testing.
//     */
//    fun saveOnly(
//        context: Context,
//        sources: List<Source>,
//        outputDir: File? = null,
//        onDone: (() -> Unit)? = null,
//    ): Job {
//        stop()
//        val job = feederScope.launch {
//            val effectiveOutputDir = outputDir
//                ?: AudioArtifactWriter.defaultArtifactDir(context, "saved-recordings")
//            effectiveOutputDir.mkdirs()
//
//            try {
//                sources.forEachIndexed { idx, src ->
//                    if (!isActive) return@forEachIndexed
//                    Timber.Forest.tag(TAG).i("── [%d/%d] saveOnly: %s", idx + 1, sources.size, src.label)
//                    AudioFeederEngine.saveOnly(
//                        source = src,
//                        artifactDir = effectiveOutputDir,
//                    )
//                }
//            } catch (t: Throwable) {
//                Timber.Forest.tag(TAG).e(t, "saveOnly failed")
//            } finally {
//                onDone?.invoke()
//            }
//        }
//        currentJob = job
//        return job
//    }
//
//    /** Stop the currently running feeder, if any. */
//    fun stop() {
//        currentJob?.cancel()
//        currentJob = null
//    }
//
//
//    /** Convenience: feed a list of file paths without building [Source] manually. */
//    fun feedFiles(
//        context: Context,
//        carrier: Carrier?,
//        codeType: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI,
//        files: List<File>,
//        realtimePacing: Boolean = true,
//        outputDir: File? = null,
//        destinationId: String? = null,
//        withSend: Boolean = false,
//    ): Job = feed(
//        context = context,
//        carrier = carrier,
//        codeType = codeType,
//        sources = files.map { Source(it) },
//        realtimePacing = realtimePacing,
//        outputDir = outputDir,
//        destinationId = destinationId,
//        withSend = withSend,
//    )
}