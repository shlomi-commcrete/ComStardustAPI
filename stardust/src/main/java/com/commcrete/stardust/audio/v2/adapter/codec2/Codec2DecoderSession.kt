package com.commcrete.stardust.audio.v2.adapter.codec2

import com.commcrete.stardust.audio.v2.application.port.DecoderSession
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.util.audio.RecorderUtils
import com.ustadmobile.codec2.Codec2Decoder
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Adapter ring — CODEC2 700C decoder for one receive stream. Wraps the JNI [Codec2Decoder]; a fresh
 * instance per stream (R5), so two senders never share decode state.
 *
 * [decode] unpacks the payload back into 4-byte frames ([Codec2FramePacker.toFrames]), decodes each
 * to PCM16 (skipping all-zero padding frames), and returns the concatenated samples at 8 kHz. A bad
 * frame recreates the native decoder and is skipped, mirroring the legacy `handleBittelAudioMessage`.
 */
class Codec2DecoderSession : DecoderSession {

    private var decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)

    override suspend fun decode(frame: EncodedFrame): PcmChunk {
        val pcmBytes = ByteArrayOutputStream()
        for (f in Codec2FramePacker.toFrames(frame.payload)) {
            if (f.contentEquals(Codec2FramePacker.EMPTY_FRAME)) continue
            try {
                pcmBytes.write(decoder.readFrame(f).array())
            } catch (e: Exception) {
                runCatching { decoder.destroy() }
                decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
            }
        }
        return PcmChunk(bytesToShorts(pcmBytes.toByteArray()), SAMPLE_RATE_HZ, frame.owner)
    }

    override fun close() {
        runCatching { decoder.destroy() }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 8_000
    }
}
