package com.commcrete.stardust.audio.v2.application.port

import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.PcmChunk

/**
 * Application layer — PORTS. Per-recording / per-stream codec sessions.
 *
 * A session owns 100% of its own mutable continuity state (for WavTokenizer: the tokenizer's
 * `index` / `cutTokens` / `loop`; for CODEC2: nothing — it is stateless per frame). Because each
 * recording/stream gets its OWN session instance, recording N+1 physically cannot mutate recording
 * N's state (R3), and two receive streams cannot bleed continuity into each other (R5).
 *
 * Both interfaces are invoked ONLY inside `CodecRegistry.withCodec(codecId) { … }` so calls into a
 * shared native runtime are serialized per codec (AI-send never blocks CODEC2-receive).
 */

/** Encoder for one recording. [drain] flushes the tail at key-up; its final frame is [EncodedFrame.isTerminal]. */
interface EncoderSession : AutoCloseable {

    /** Encode one captured+DSP'd chunk into zero or more wire frames. */
    suspend fun encode(chunk: PcmChunk): List<EncodedFrame>

    /** Flush remaining audio at key-up. The last returned frame MUST have `isTerminal = true`. */
    suspend fun drain(): List<EncodedFrame>

    /**
     * Release native resources / return a pooled module. MUST be called only after [drain] has
     * fully returned, so a still-computing forward pass never hands its module to recording N+1.
     */
    override fun close()
}

/** Decoder for one receive stream. Fresh per burst; closed on the terminal frame or an idle timeout. */
interface DecoderSession : AutoCloseable {

    /** Decode one wire frame to PCM for playback. */
    suspend fun decode(frame: EncodedFrame): PcmChunk

    override fun close()
}
