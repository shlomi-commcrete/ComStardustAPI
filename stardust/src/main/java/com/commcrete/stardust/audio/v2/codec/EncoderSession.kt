package com.commcrete.stardust.audio.v2.codec

/**
 * Per-recording encoder state.
 *
 * Created by [AudioCodec.newEncoderSession] when a
 * [com.commcrete.stardust.audio.v2.session.RecordingSession] starts,
 * closed when the session finalizes. Holds whatever the underlying
 * codec needs to carry across chunks within a single PTT key-down
 * (e.g. CODEC2's `pending4Bytes` half-pair buffer, AI's tokenizer
 * model continuity).
 *
 * **NOT thread-safe.** Every call to [encode] / [flush] / [close]
 * MUST happen inside a [CodecRegistry.withCodec] block, so that:
 *   - the underlying ML/native singleton (`WavTokenizerEncoder`,
 *     `Codec2Encoder`) is not driven by two threads at once,
 *   - the optional self-decode in
 *     [com.commcrete.stardust.audio.v2.mirror.LocalMirror] cannot race
 *     the encode loop,
 *   - the receive-side decode for the same codec cannot interleave
 *     with our writes.
 */
interface EncoderSession : AutoCloseable {

    /**
     * Encode one chunk of PCM at [AudioCodec.sampleRateHz].
     *
     * May return:
     *  - **empty list** — encoder buffered the samples but didn't
     *    produce a complete packet yet (CODEC2 does this when fewer
     *    than 320 samples have accumulated for one frame, or only
     *    one of a pair is ready).
     *  - **one or more `ByteArray`s** — each is an encoded payload
     *    BEFORE [AudioCodec.sendPayloadPrefix] / [AudioCodec.sanitizePayload]
     *    are applied by the coordinator.
     */
    fun encode(pcm: ShortArray): List<ByteArray>

    /**
     * Drain any pending state and produce the final payload(s) of
     * the stream. For CODEC2 this zero-pads the incomplete frame
     * and emits the half-filled packet. For AI this is a no-op
     * unless the model needs an explicit "end of stream" token.
     *
     * The coordinator passes `flushed = true` into
     * [AudioCodec.isLastPacket] for every payload returned here, so
     * the wire-format `LAST` control bit is set on them.
     */
    fun flush(): List<ByteArray>

    override fun close()
}

