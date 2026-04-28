package com.example.chunkrecorder

/**
 * Pluggable in-place noise suppression stage for [com.commcrete.stardust.ai.codec.AudioRecorderAI].
 *
 * Lifecycle:
 *  1. [init] is called once when recording starts, with the recorder's sample rate.
 *  2. [process] is called repeatedly with PCM 16-bit mono buffers (in-place).
 *  3. [release] is called when recording stops.
 *
 * Implementations must be **stateful** across [process] calls (filter / NN state
 * persists between buffers) but must be re-initialized in [init].
 */
interface NoiseProcessor {
    /** @param sampleRate The recorder's PCM sample rate in Hz (e.g. 24000). */
    fun init(sampleRate: Int)

    /**
     * Denoise [buffer] in place. Implementations are responsible for any internal
     * resampling and frame-buffering needed by the underlying algorithm.
     *
     * @param buffer mono int16 PCM samples
     * @param length number of valid samples in [buffer]
     */
    fun process(buffer: ShortArray, length: Int)

    fun release()
}

