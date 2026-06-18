package com.commcrete.stardust.audio.v2.capture

import kotlinx.coroutines.flow.Flow

/**
 * Source of raw PCM chunks driving a [com.commcrete.stardust.audio.v2.session.RecordingSession].
 *
 * Decouples session lifecycle from where the audio actually comes from.
 * Two implementations ship with v2:
 *  - [MicCaptureSource] — real-time `AudioRecord` capture for live PTT.
 *  - [FileCaptureSource] — chunked playback of a pre-recorded WAV /
 *    PCM for the test feeder. Replaces `AudioFeederEngine`'s inline
 *    chunk loop so the feeder shares the same encode pipeline as live
 *    recording (the property the codebase was hand-maintaining today).
 *
 * # Threading contract
 *
 * [start] returns a cold [Flow] of [CaptureChunk]s. The session
 * launches a single collector on `Dispatchers.IO` (or whichever
 * coroutine dispatcher [com.commcrete.stardust.audio.v2.session.RecordingSession]
 * uses). Implementations MUST NOT emit from multiple coroutines.
 *
 * [stop] cancels the underlying capture (closing the `AudioRecord` /
 * file handle); the Flow then completes. Calling [stop] on a source
 * that was never started, or twice, is a no-op.
 */
interface CaptureSource {

    /**
     * Sample rate of every chunk emitted from [start]. This is the
     * **native capture rate**, which may differ from the codec's
     * target rate — the per-session [com.commcrete.stardust.audio.v2.dsp.PttAudioProcessorV2]
     * resamples on demand.
     */
    val nativeSampleRateHz: Int

    /**
     * Best-effort identifier of the actual hardware route in use
     * (e.g. `AudioDeviceInfo.TYPE_BUILTIN_MIC`,
     * `TYPE_USB_DEVICE`, `TYPE_BLUETOOTH_SCO`). Used by the per-session
     * DSP to pick the right `RecordingDeviceType` profile.
     *
     * `null` when the source cannot determine the route (e.g.
     * [FileCaptureSource], or `AudioRecord.routedDevice` returning
     * null on some devices).
     */
    val deviceTypeHint: Int?

    /**
     * Start emitting chunks of [chunkDurationMs] worth of PCM.
     *
     * Returned chunks are at [nativeSampleRateHz]. The last chunk of
     * a session may be partial (when [stop] arrives mid-buffer); it
     * is signalled by [CaptureChunk.isPartial] = true and the
     * session's encoder treats it as the trailing chunk to flush.
     *
     * Cold Flow — collection starts the actual capture. The session
     * may only collect once per source.
     */
    fun start(chunkDurationMs: Int): Flow<CaptureChunk>

    /**
     * Stop the underlying capture as soon as possible. Cancels any
     * blocked `AudioRecord.read` / file read. Safe to call from any
     * thread and at any point in the source lifecycle.
     */
    fun stop()
}

/**
 * One PCM chunk emitted by a [CaptureSource].
 *
 * @property pcm    raw 16-bit mono samples at [CaptureSource.nativeSampleRateHz].
 *                  Length is the configured `chunkDurationMs` worth of
 *                  samples, EXCEPT when [isPartial] = true (final chunk
 *                  shorter than requested duration).
 * @property index  zero-based chunk index within this capture session
 *                  — used by diagnostics and by the DSP processor's
 *                  per-chunk dedupe logging.
 * @property isPartial true if this is the trailing partial chunk
 *                  emitted right before the Flow completes. Sessions
 *                  use this to drive [com.commcrete.stardust.audio.v2.codec.EncoderSession.flush].
 */
data class CaptureChunk(
    val pcm: ShortArray,
    val index: Int,
    val isPartial: Boolean = false,
) {
    // Auto-generated equals/hashCode for ShortArray fields rely on
    // reference equality, which is wrong for data classes — override
    // so tests comparing CaptureChunks compare contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureChunk) return false
        return index == other.index &&
                isPartial == other.isPartial &&
                pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var h = pcm.contentHashCode()
        h = 31 * h + index
        h = 31 * h + isPartial.hashCode()
        return h
    }
}

