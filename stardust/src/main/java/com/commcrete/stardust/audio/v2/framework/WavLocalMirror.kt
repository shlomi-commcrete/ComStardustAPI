package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.ai.codec.WavHelper
import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.util.StardustLog
import java.io.File

/**
 * Framework ring — accumulates a recording's SELF-DECODED PCM and writes it to a WAV on finalize
 * (R3's per-session mirror). One instance per recording; the save directory/file is a constructor arg
 * (README invariant #4 — no global save dir). Only wired in when `DataManager.getSavePTTFilesRequired()`.
 *
 * The chunks come from [com.commcrete.stardust.audio.v2.application.send.RecordingSession]'s mirror
 * decode of the transmitted frames, so the file is what the receiver reconstructs — matching the
 * legacy AI (`PttSendManager`) and CODEC2 (`AudioRecorderCodec2`) local files. Unlike legacy CODEC2,
 * which wrote headerless raw PCM into a `.pcm` file, this always writes a real RIFF/WAVE header via
 * [WavHelper.writeWavFile], at the decoder's own rate (CODEC2 8 kHz, WavTokenizer 24 kHz).
 */
class WavLocalMirror(private val file: File) : LocalMirror {

    private companion object {
        const val TAG = "WavLocalMirror"
    }

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
            WavHelper.writeWavFile(ShortArray(samples.size) { samples[it] }, sampleRateHz, file)
        }.onFailure { StardustLog.e(TAG, "Local PTT mirror not written", it) }
        samples.clear()
    }
}
