package com.commcrete.stardust.audio.v2.capture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [CaptureSource] that emits pre-loaded PCM in chunks, replacing the
 * test feeder's inline encode loop.
 *
 * In v1 the test feeder ([com.commcrete.stardust.util.audio.AudioFeederEngine])
 * does its own capture-rate math, DSP, AND encode/send routing in a
 * single ~430-line function. v2 collapses that to:
 *   1. load PCM + native rate via the feeder's existing analyzer,
 *   2. wrap in [FileCaptureSource],
 *   3. hand to [com.commcrete.stardust.audio.v2.session.PttSendCoordinator]
 *      with the appropriate codec, transport (no-op for artifact-only
 *      runs), mirror, and DSP profile.
 *
 * No real-time clocking — the Flow emits chunks as fast as the
 * collector consumes them. Use `kotlinx.coroutines.delay(chunkDurationMs)`
 * in the collector if you want a realistic feed rate (the session does
 * not require it; the encoder doesn't care how fast PCM arrives).
 *
 * @property pcm                 source PCM samples, already at [nativeSampleRateHz].
 * @property nativeSampleRateHz  sample rate of [pcm] (typically 16000 / 24000 / 48000).
 * @property deviceTypeHint      hint for DSP profile selection (e.g. simulate a USB jbox).
 */
class FileCaptureSource(
    private val pcm: ShortArray,
    override val nativeSampleRateHz: Int,
    override val deviceTypeHint: Int? = null,
) : CaptureSource {

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    override fun start(chunkDurationMs: Int): Flow<CaptureChunk> {
        check(started.compareAndSet(false, true)) {
            "FileCaptureSource may only be started once — construct a new instance per feed."
        }
        return flow {
            val samplesPerChunk =
                (nativeSampleRateHz.toLong() * chunkDurationMs / 1000L).toInt()
            require(samplesPerChunk > 0) {
                "chunkDurationMs=$chunkDurationMs at $nativeSampleRateHz Hz yields zero samples per chunk"
            }
            var offset = 0
            var index = 0
            while (offset < pcm.size && !stopped.get()) {
                val remaining = pcm.size - offset
                val take = minOf(samplesPerChunk, remaining)
                val isPartial = take < samplesPerChunk
                val chunk = ShortArray(take)
                System.arraycopy(pcm, offset, chunk, 0, take)
                emit(CaptureChunk(
                    pcm = chunk,
                    index = index++,
                    isPartial = isPartial,
                ))
                offset += take
            }
            if (stopped.get()) {
                Timber.d("FileCaptureSource: stop requested mid-stream at chunk $index")
            }
        }
    }

    override fun stop() {
        stopped.set(true)
    }
}

