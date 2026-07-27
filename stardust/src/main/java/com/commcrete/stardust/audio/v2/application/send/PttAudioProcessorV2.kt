package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.domain.PcmChunk

/**
 * Application layer — per-session DSP contract (R3).
 *
 * Unlike the legacy process-wide `PttAudioProcessor` singleton (with its global `reset()`), one
 * instance is created per [RecordingSession], so recording N+1's filter/resampler state can never
 * collide with recording N's. The concrete implementation (RNNoise + `StreamingPolyphaseResampler`
 * + make-up gain, wrapping the existing filters) lives in the framework ring and is constructed with
 * the codec's target rate already fixed — this contract stays codec- and Android-agnostic.
 */
interface PttAudioProcessorV2 : AutoCloseable {

    /** Apply gain + noise suppression + resample-to-target to one captured chunk. */
    fun process(chunk: PcmChunk): PcmChunk

    /** Flush the resampler's polyphase tail at key-up. Returns an empty-sample chunk when nothing is buffered. */
    fun flush(): PcmChunk

    override fun close()
}
