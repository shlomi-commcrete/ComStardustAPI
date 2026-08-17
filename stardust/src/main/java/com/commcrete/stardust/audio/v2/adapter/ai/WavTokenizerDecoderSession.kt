package com.commcrete.stardust.audio.v2.adapter.ai

import com.commcrete.stardust.ai.codec.AIModuleInitializer
import com.commcrete.stardust.ai.codec.WavTokenizerDecoder
import com.commcrete.stardust.audio.v2.application.port.DecoderSession
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.PcmChunk

/**
 * Adapter ring — WavTokenizer (neural) decoder for ONE receive stream (R5).
 *
 * The decoder is a shared singleton with per-stream continuity (`index`/`cutTokens`/`loop`). This
 * session holds THIS stream's [WavTokenizerDecoder.InternalState] plus its previous tokens/samples,
 * and brackets each decode with restore→decode→snapshot — exactly the legacy
 * `PttReceiveManager.handleTokenizerChunk` dance, but the per-stream state lives in the session
 * instead of a map entry. The caller ([ReceiveStream]) already holds `CodecRegistry.withCodec(...)`,
 * so these mutations of the shared decoder are serialized against every other AI encode/decode.
 *
 * A fresh instance per burst (the registry evicts on terminal frame / idle), so continuity is a clean
 * slate at stream start and every subsequent chunk is a continuation.
 */
class WavTokenizerDecoderSession : DecoderSession {

    private val decoder = AIModuleInitializer.wavTokenizerDecoder

    private var state: WavTokenizerDecoder.InternalState = WavTokenizerDecoder.InternalState.INITIAL
    private var lastTokens: List<Long>? = null
    private var lastSamples: ShortArray? = null
    private var started = false

    override suspend fun decode(frame: EncodedFrame): PcmChunk {
        val tokens = AiFramePacker.tokensOf(frame.payload)
        val modelType = AiFramePacker.modelTypeOf(frame.payload)

        val previousTokens = if (started) lastTokens else null
        val previousSamples = if (started) lastSamples else null

        if (!started) state = WavTokenizerDecoder.InternalState.INITIAL
        decoder.restoreInternalState(state)
        val pcm = decoder.decode(tokens, previousTokens, previousSamples, modelType)
        state = decoder.snapshotInternalState()

        lastTokens = tokens
        lastSamples = pcm
        started = true
        return PcmChunk(pcm, SAMPLE_RATE_HZ, null)
    }

    override fun close() {
        lastTokens = null
        lastSamples = null
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 24_000
    }
}
