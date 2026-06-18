package com.commcrete.stardust.audio.v2.codec

/**
 * Per-stream decoder state.
 *
 * The receive coordinator keeps one of these per `(from|source)`
 * stream key so multiple concurrent talkers can be decoded without
 * their continuity bleeding into each other. For codecs whose
 * underlying decoder is a stateful singleton (AI's `WavTokenizerDecoder`,
 * with mutable `index`/`cutTokens`/`loop`), the implementation
 * snapshot-restores around every [decode] call internally — callers
 * don't need to know about it.
 *
 * **NOT thread-safe.** Every call to [decode] / [reset] / [close]
 * MUST happen inside a [CodecRegistry.withCodec] block, which is the
 * same mutex held by [EncoderSession.encode] and by the local-mirror
 * self-decode path. That single chokepoint is what guarantees the
 * underlying singleton's per-stream state can't be corrupted by
 * interleaved calls.
 */
interface DecoderSession : AutoCloseable {

    /**
     * Drop all per-stream continuity so the next [decode] call
     * behaves as if this were the first chunk of a brand-new stream.
     *
     * Called by [com.commcrete.stardust.audio.v2.session.PttReceiveCoordinator]
     * when a stream has been silent for longer than the gap threshold
     * (currently 2s), so the receiver doesn't carry stale
     * head-cut tokens into the next PTT on the same channel.
     *
     * Cost contract: implementations MUST treat this as cheap (a few
     * field assignments). The v2 design relies on `reset()` being a
     * no-op so per-stream decoder pooling is not needed.
     */
    fun reset()

    /**
     * Decode one packet's payload (already stripped of
     * [AudioCodec.sendPayloadPrefix]) into PCM at
     * [AudioCodec.sampleRateHz].
     *
     * Implementations are responsible for save/restore of the
     * underlying singleton's per-stream state across the call so
     * other streams' decoders can multiplex over the same instance.
     */
    fun decode(payload: ByteArray): ShortArray

    override fun close()
}

