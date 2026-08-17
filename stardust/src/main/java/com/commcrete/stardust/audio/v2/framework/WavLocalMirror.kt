package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.ai.codec.WavHelper
import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import java.io.File

/**
 * Framework ring — accumulates a recording's post-DSP PCM and writes it to a WAV on finalize (R3's
 * per-session mirror). One instance per recording; the save directory/file is a constructor arg
 * (README invariant #4 — no global save dir). Only wired in when `DataManager.getSavePTTFilesRequired()`.
 *
 * Note: this mirrors the ENCODER INPUT (post-gain/resample), not a self-decode of the transmitted
 * bytes. A true decode-mirror would run the decoder on the encoded frames; deferred until needed.
 */
class WavLocalMirror(private val file: File) : LocalMirror {

    private val samples = ArrayList<Short>()
    private var sampleRateHz = 0

    override suspend fun accept(pcm: PcmChunk) {
        if (sampleRateHz == 0) sampleRateHz = pcm.sampleRateHz
        for (s in pcm.samples) samples.add(s)
    }

    override suspend fun finalizeMirror() {
        if (samples.isEmpty() || sampleRateHz == 0) return
        runCatching {
            file.parentFile?.mkdirs()
            WavHelper.createWavFile(ShortArray(samples.size) { samples[it] }, sampleRateHz, file)
        }
        samples.clear()
    }
}
