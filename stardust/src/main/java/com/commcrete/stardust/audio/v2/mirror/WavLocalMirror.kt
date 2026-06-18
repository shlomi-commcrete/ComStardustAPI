package com.commcrete.stardust.audio.v2.mirror

import com.commcrete.aiaudio.media.WavHelper
import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.audio.v2.codec.DecoderSession
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory accumulator that decodes every just-encoded chunk and
 * writes the result to a `.wav` file on finalize.
 *
 * Replaces the AI `frameBuffer` + `WavHelper.createWavFile` pair from
 * [com.commcrete.stardust.ai.codec.PttSendManager.finalizeSession] AND
 * the CODEC2 `data` ArrayList + `os.write` pair from
 * [com.commcrete.stardust.util.audio.AudioRecorderCodec2.writeAudioDataToFile].
 *
 * # Constructor args
 *
 * @property saveDir   the directory the WAV mirror is written into.
 *                     **Replaces the global `RecorderUtils.dirToSaveFile`**
 *                     mutable static — each session passes its own
 *                     directory, so the test feeder and live recording
 *                     can write to different roots without race
 *                     conditions.
 * @property enabled   gating predicate. Evaluated once per [onEncoded]
 *                     call AND once on [finalize] — if it ever returns
 *                     `false`, accumulation stops and no file is
 *                     written. Lambda so the host can read
 *                     `DataManager.getSavePTTFilesRequired(ctx)`
 *                     lazily (the user setting can flip during a
 *                     recording).
 * @property mirrorFileNameOverride
 *                     optional override for the WAV filename. When
 *                     null, the [finalize]-time `targetFile` is used
 *                     verbatim. The feeder uses this to force a
 *                     `-ptt_finish.wav` suffix that matches the
 *                     legacy mirror naming (so existing test
 *                     consumers still find the artifact).
 */
class WavLocalMirror(
    private val saveDir: File,
    private val enabled: () -> Boolean,
    private val mirrorFileNameOverride: String? = null,
) : LocalMirror {

    /**
     * Decoded PCM chunks in encode order. Concatenated on [finalize]
     * into one `ShortArray` for [WavHelper.createWavFile].
     *
     * `ArrayList` over `MutableList` is intentional — we want the
     * indexed get used by `flatMap { it.asIterable() }` and we know
     * the size won't be reallocated mid-session (one chunk per
     * encoded packet, typical recording = a few hundred).
     */
    private val frameBuffer: ArrayList<ShortArray> = ArrayList()

    private val finalized = AtomicBoolean(false)

    override suspend fun onEncoded(
        codec: AudioCodec,
        decoder: DecoderSession,
        payload: ByteArray,
    ) {
        if (!enabled()) return
        if (finalized.get()) return
        try {
            // Caller already holds CodecRegistry.withCodec — safe to
            // call decoder.decode() without re-acquiring.
            val pcm = decoder.decode(payload)
            if (pcm.isNotEmpty()) frameBuffer.add(pcm)
        } catch (t: Throwable) {
            // A failed mirror decode is a non-fatal diagnostic loss —
            // the actual transmitted packet was already sent. Log and
            // keep going.
            Timber.w(t, "WavLocalMirror: per-chunk decode failed")
        }
    }

    override suspend fun finalize(codec: AudioCodec, targetFile: File): File? {
        if (!finalized.compareAndSet(false, true)) {
            Timber.d("WavLocalMirror: finalize() already ran for $targetFile — ignoring")
            return null
        }
        if (!enabled()) {
            Timber.d("WavLocalMirror: finalize() called but save disabled — skipping")
            return null
        }
        if (frameBuffer.isEmpty()) {
            Timber.d("WavLocalMirror: finalize() — no frames accumulated, skipping file")
            return null
        }
        val output = resolveOutputFile(targetFile)
        try {
            output.parentFile?.takeIf { !it.exists() }?.mkdirs()
            val combined = frameBuffer.flatMap { it.asIterable() }.toShortArray()
            WavHelper.createWavFile(combined, codec.sampleRateHz, output)
            Timber.d("WavLocalMirror: wrote ${combined.size} samples to ${output.absolutePath}")
            return output
        } catch (t: Throwable) {
            Timber.e(t, "WavLocalMirror: failed to write WAV to ${output.absolutePath}")
            return null
        }
    }

    override fun close() {
        frameBuffer.clear()
    }

    private fun resolveOutputFile(targetFile: File): File {
        val name = mirrorFileNameOverride ?: targetFile.name
        // If targetFile's parent matches saveDir, write straight there;
        // otherwise resolve under saveDir so the mirror is grouped with
        // other artifacts from the same session source.
        val targetParent = targetFile.parentFile?.canonicalFile
        val mySaveDir = saveDir.canonicalFile
        return if (targetParent == mySaveDir) targetFile else File(saveDir, name)
    }
}

