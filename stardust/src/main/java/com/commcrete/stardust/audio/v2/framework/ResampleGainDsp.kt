package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.application.send.PttAudioProcessorV2
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.util.audio.StreamingPolyphaseResampler

/**
 * Framework ring — a per-session DSP: input gain + resample-to-target, using a per-instance
 * [StreamingPolyphaseResampler] so two concurrent recordings never share resampler phase (R3).
 *
 * TODO(rnnoise): the legacy path also runs RNNoise (the only active filter). Per-session RNNoise
 * needs its native state externalized into this instance; until then this DSP is gain + resample
 * only. That is the same shape as decision #1 for WavTokenizer and should be resolved together.
 */
class ResampleGainDsp(
    private val targetRateHz: Int,
    private val gainProvider: () -> Float,
) : PttAudioProcessorV2 {

    private var resampler: StreamingPolyphaseResampler? = null
    private var srcRate = -1
    private var lastOwner: RecordingId? = null

    override fun process(chunk: PcmChunk): PcmChunk {
        val gain = gainProvider()
        val gained = ShortArray(chunk.samples.size) { i ->
            (chunk.samples[i] * gain)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .toInt()
                .toShort()
        }
        lastOwner = chunk.owner
        if (chunk.sampleRateHz == targetRateHz) return PcmChunk(gained, targetRateHz, chunk.owner)
        val resampled = ensureResampler(chunk.sampleRateHz).process(gained)
        return PcmChunk(resampled, targetRateHz, chunk.owner)
    }

    override fun flush(): PcmChunk {
        val rs = resampler ?: return PcmChunk(ShortArray(0), targetRateHz, lastOwner)
        return PcmChunk(rs.flush(), targetRateHz, lastOwner)
    }

    override fun close() {
        resampler?.reset()
        resampler = null
    }

    private fun ensureResampler(src: Int): StreamingPolyphaseResampler {
        val existing = resampler
        if (existing != null && srcRate == src) return existing
        srcRate = src
        return StreamingPolyphaseResampler(src, targetRateHz).also { resampler = it }
    }
}
