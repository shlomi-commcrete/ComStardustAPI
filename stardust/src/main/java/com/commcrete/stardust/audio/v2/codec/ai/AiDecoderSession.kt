package com.commcrete.stardust.audio.v2.codec.ai

import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.stardust.audio.v2.codec.DecoderSession

/**
 * Per-stream AI decoder.
 *
 * Wraps the shared [WavTokenizerDecoder] singleton. The model has
 * mutable instance state (`index`, `cutTokens`, `loop`, `listEnergy`)
 * that gets clobbered if two streams call `decode()` interleaved on
 * the same instance.
 *
 * v2 solves the multiplexing by:
 *  1. caller (the receive coordinator) holds
 *     [com.commcrete.stardust.audio.v2.codec.CodecRegistry.withCodec] for the
 *     entire decode call,
 *  2. **inside** the call we save → restore → decode → save the
 *     decoder's [WavTokenizerDecoder.InternalState] so the next time
 *     a DIFFERENT stream's session runs through, it starts from its
 *     own saved state rather than ours.
 *
 * Per-stream continuity (`previousTokens` / `previousSamples`) is
 * kept here in [lastUnpack] / [lastPcm]. [reset] clears all of it —
 * called when the receive coordinator detects a >2s gap and treats
 * the next packet as the start of a fresh stream on this channel.
 *
 * **Cost contract.** [reset] is a no-op-equivalent — just three field
 * writes. The v2 architecture relies on this so we don't need to
 * pool decoder sessions. Verified by reading the legacy
 * `PttReceiveManager` code paths.
 */
internal class AiDecoderSession(
    private val decoder: WavTokenizerDecoder,
    private val modelTypeProvider: () -> WavTokenizerDecoder.ModelType,
) : DecoderSession {

    /**
     * Snapshot of the decoder's internal state captured AFTER this
     * session's most recent decode. Restored before the next decode
     * so a different stream's intervening calls cannot pollute it.
     * Starts as [WavTokenizerDecoder.InternalState.INITIAL] (= fresh
     * stream).
     */
    private var savedState: WavTokenizerDecoder.InternalState =
        WavTokenizerDecoder.InternalState.INITIAL

    /** Previous chunk's unpacked tokens — fed into the next [decode] as continuity. */
    private var lastUnpack: List<Long>? = null

    /** Previous chunk's decoded PCM — fed into the next [decode] as continuity. */
    private var lastPcm: ShortArray? = null

    override fun reset() {
        savedState = WavTokenizerDecoder.InternalState.INITIAL
        lastUnpack = null
        lastPcm = null
    }

    override fun decode(payload: ByteArray): ShortArray {
        val unpack = BitPacking12.unpack12(payload)
        // Restore THIS stream's snapshot before invoking decode so we
        // pick up where we left off, not where some other stream did.
        decoder.restoreInternalState(savedState)
        val pcm = decoder.decode(unpack, lastUnpack, lastPcm, modelTypeProvider())
        // Capture state for the NEXT decode on this stream.
        savedState = decoder.snapshotInternalState()
        lastUnpack = unpack
        lastPcm = pcm
        return pcm
    }

    override fun close() {
        // No per-session resources to release — model is process-wide.
    }
}

