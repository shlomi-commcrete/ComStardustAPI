package com.commcrete.stardust.audio.v2.mirror

import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.audio.v2.codec.DecoderSession
import java.io.File

/**
 * Optional consumer that runs the just-encoded payload back through a
 * decoder to accumulate a local WAV "mirror" of what the receiver
 * will hear. Two existing call sites collapse here:
 *  - AI's per-chunk self-decode in
 *    [com.commcrete.stardust.ai.codec.PttSendManager.handleTokenizerChunk] +
 *    the `frameBuffer.flatMap { ... }` WAV write in `finalizeSession`,
 *  - CODEC2's `onEncodedFrame` self-decode in
 *    [com.commcrete.stardust.util.audio.Codec2SendPipeline] + the
 *    `data.toByteArray()` write in `AudioRecorderCodec2.writeAudioDataToFile`.
 *
 * # Threading + safety
 *
 *  - [onEncoded] runs on the encode coroutine — **inside the
 *    [com.commcrete.stardust.audio.v2.codec.CodecRegistry.withCodec]
 *    block that just produced the payload**. Implementations therefore
 *    do NOT need to re-acquire the codec mutex when calling
 *    [DecoderSession.decode] on the [decoder] argument.
 *  - The [decoder] passed to [onEncoded] is **owned by the mirror** —
 *    it's a freshly constructed [DecoderSession] just for the mirror's
 *    self-decode stream, distinct from the receive-side decoder
 *    sessions used by [com.commcrete.stardust.audio.v2.session.PttReceiveCoordinator].
 *    The mutex still serialises the underlying codec singleton, which
 *    is what protects correctness.
 *  - [finalize] runs once when the session is being torn down. Safe
 *    to do disk I/O; coordinator wraps the call in
 *    `withContext(NonCancellable)` so it completes even if the
 *    surrounding job was cancelled.
 *
 * # Implementations
 *
 *  - [WavLocalMirror] — accumulates decoded PCM in memory, writes a
 *    WAV file on [finalize] (gated by a predicate so the feature is
 *    cheap to disable).
 *  - [NoOpLocalMirror] — does nothing. Used when local mirroring is
 *    disabled, so callers don't have to null-check.
 */
interface LocalMirror : AutoCloseable {

    /**
     * Called once per encoded [payload] BEFORE the coordinator passes
     * it to the transport. [decoder] is the mirror's private decoder
     * session — call [DecoderSession.decode] on it (the codec mutex
     * is already held by the caller) to obtain PCM to accumulate.
     */
    suspend fun onEncoded(codec: AudioCodec, decoder: DecoderSession, payload: ByteArray)

    /**
     * Write accumulated PCM to a WAV file. Idempotent — second call
     * with the same [targetFile] is a no-op so the coordinator's
     * single-shot finalize CAS guard is reinforced.
     *
     * @param codec       used for [AudioCodec.sampleRateHz] to set the
     *                    WAV header.
     * @param targetFile  output WAV path. Implementations may create
     *                    parent directories.
     * @return the file that was written, or `null` if nothing was
     *         accumulated / mirroring was disabled.
     */
    suspend fun finalize(codec: AudioCodec, targetFile: File): File?

    /**
     * Release any buffered PCM. Called from [com.commcrete.stardust.audio.v2.session.RecordingSession]
     * after [finalize] completes.
     */
    override fun close()
}

