package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.application.send.PttAudioProcessorV2
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.util.audio.PttAudioProcessor
import com.commcrete.stardust.util.audio.StreamingPolyphaseResampler

/**
 * Framework ring — the per-session send DSP: input gain → filter chain → resample-to-target.
 *
 * Chain parity with legacy: `AudioRecorderCodec2` applied [gainProvider]'s gain per sample in its capture
 * loop and then called `RecorderUtils.preprocessChunkForEncoding` → [PttAudioProcessor.process], which
 * runs HPF/Notch/RNNoise/Dynamics/AGC/LPF before resampling. Doing gain + resample only (as this class
 * originally did) dropped that entire chain, which matters most for CODEC2 700C — a low-level,
 * unprocessed input makes the vocoder output nearly silent.
 *
 * Division of labour with [PttAudioProcessor]: it is called with `nativeRate == targetRate` so it ONLY
 * filters, and the resample is done here through a per-instance [StreamingPolyphaseResampler]. That
 * keeps resampler phase/history per recording (R3) instead of in the processor's process-wide singleton.
 *
 * Remaining caveat (unchanged from legacy): [PttAudioProcessor]'s filter chain is itself a process-wide
 * singleton, so two genuinely concurrent recordings would share biquad/RNNoise state. Legacy relied on
 * `RecorderUtils.recordingInProgress` to prevent that overlap and so does this; per-session filter
 * instances are the real fix and are tracked as v2 open decision #1.
 */
class ResampleGainDsp(
    private val targetRateHz: Int,
    private val noiseSuppressionEnabled: Boolean,
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

        // nativeRate == targetRate → filter only, no resample (that is this class's job, per-session).
        val filtered = PttAudioProcessor.process(
            pcmArray = gained,
            nativeRate = chunk.sampleRateHz,
            targetRate = chunk.sampleRateHz,
            enableNoiseCancellation = noiseSuppressionEnabled,
        )

        if (chunk.sampleRateHz == targetRateHz) return PcmChunk(filtered, targetRateHz, chunk.owner)
        val resampled = ensureResampler(chunk.sampleRateHz).process(filtered)
        return PcmChunk(resampled, targetRateHz, chunk.owner)
    }

    override fun flush(): PcmChunk {
        val rs = resampler ?: return PcmChunk(ShortArray(0), targetRateHz, lastOwner)
        return PcmChunk(rs.flush(), targetRateHz, lastOwner)
    }

    override fun close() {
        resampler?.reset()
        resampler = null
        // Releases native RNNoise state and forces a fresh chain for the next recording — the same
        // reason legacy called this from PttSendManager.restart.
        runCatching { PttAudioProcessor.reset() }
    }

    private fun ensureResampler(src: Int): StreamingPolyphaseResampler {
        val existing = resampler
        if (existing != null && srcRate == src) return existing
        srcRate = src
        return StreamingPolyphaseResampler(src, targetRateHz).also { resampler = it }
    }
}
