package com.commcrete.stardust.audio.v2.adapter.codec2

import com.commcrete.stardust.audio.v2.application.port.EncoderSession
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.SequenceNumber
import com.commcrete.stardust.util.audio.RecorderUtils
import com.ustadmobile.codec2.Codec2Encoder

/**
 * Adapter ring — CODEC2 700C encoder for one recording. Wraps the JNI [Codec2Encoder] and the
 * [Codec2FramePacker] bit-packing; a fresh instance per recording gives R3 isolation for free
 * (700C is stateless per frame, so there is nothing to bleed anyway).
 *
 * [encode] accumulates 8 kHz PCM (already resampled by the per-session DSP) into whole 320-sample
 * frames, packs two-per-7-bytes, and emits an [EncodedFrame] for every full 77-byte packet.
 * [drain] zero-pads the tail and emits the remainder as the terminal frame (possibly empty) so the
 * receiver evicts promptly rather than waiting for the idle timeout.
 */
class Codec2EncoderSession(
    private val id: RecordingId,
    private val codecId: CodecId,
) : EncoderSession {

    private val encoder = Codec2Encoder(RecorderUtils.CodecValues.MODE700.mode)
    private val charOut = RecorderUtils.CodecValues.MODE700.charNumOutput

    private val sampleRemainder = ArrayList<Short>()
    private var pending4: ByteArray? = null
    private val packetBuffer = ArrayList<Byte>()
    private var seq = 0

    override suspend fun encode(chunk: PcmChunk): List<EncodedFrame> {
        val out = ArrayList<EncodedFrame>()
        for (s in chunk.samples) sampleRemainder.add(s)
        while (sampleRemainder.size >= Codec2FramePacker.FRAME_SAMPLES) {
            val frame = ShortArray(Codec2FramePacker.FRAME_SAMPLES) { sampleRemainder[it] }
            repeat(Codec2FramePacker.FRAME_SAMPLES) { sampleRemainder.removeAt(0) }
            pushFrame(frame, out)
        }
        return out
    }

    override suspend fun drain(): List<EncodedFrame> {
        val out = ArrayList<EncodedFrame>()
        if (sampleRemainder.isNotEmpty()) {
            val padded = ShortArray(Codec2FramePacker.FRAME_SAMPLES)
            for (i in sampleRemainder.indices) padded[i] = sampleRemainder[i]
            sampleRemainder.clear()
            pushFrame(padded, out)
        }
        pending4?.let {
            addPacked(Codec2FramePacker.packTwo(it, Codec2FramePacker.EMPTY_FRAME), out)
            pending4 = null
        }
        // Whatever is left (1..76 bytes) becomes the terminal frame. A ZERO-length payload is never
        // transmitted (legacy Codec2SendPipeline.finish only sent a non-empty remainder, and a
        // `length = 0` SPEECH package is not something the radio expects) — instead the terminal flag
        // moves onto the last real frame. If the recording produced nothing at all, nothing is sent and
        // the receiver ends the stream on its idle timeout, exactly as with legacy.
        val payload = ByteArray(packetBuffer.size) { packetBuffer[it] }
        packetBuffer.clear()
        if (payload.isNotEmpty()) {
            out.add(frameOf(payload, terminal = true))
        } else if (out.isNotEmpty()) {
            val last = out.removeAt(out.lastIndex)
            out.add(
                EncodedFrame(
                    codecId = last.codecId,
                    payload = last.payload,
                    isTerminal = true,
                    owner = last.owner,
                    seq = last.seq,
                )
            )
        }
        return out
    }

    override fun close() {
        // 700C encoder holds no cross-recording state; buffers are dropped with this instance.
        // (JNI encoder release intentionally not called here — mirrors legacy Codec2SendPipeline lifetime.)
        sampleRemainder.clear()
        packetBuffer.clear()
        pending4 = null
    }

    private fun pushFrame(frame: ShortArray, out: MutableList<EncodedFrame>) {
        val chars = CharArray(charOut)
        encoder.encode(frame, chars)
        val bytes = ByteArray(chars.size) { chars[it].code.toByte() }
        val pend = pending4
        if (pend == null) {
            pending4 = bytes
        } else {
            addPacked(Codec2FramePacker.packTwo(pend, bytes), out)
            pending4 = null
        }
    }

    private fun addPacked(seven: ByteArray, out: MutableList<EncodedFrame>) {
        seven.forEach { packetBuffer.add(it) }
        while (packetBuffer.size >= Codec2FramePacker.PACKET_BYTES) {
            val payload = ByteArray(Codec2FramePacker.PACKET_BYTES) { packetBuffer[it] }
            repeat(Codec2FramePacker.PACKET_BYTES) { packetBuffer.removeAt(0) }
            out.add(frameOf(payload, terminal = false))
        }
    }

    private fun frameOf(payload: ByteArray, terminal: Boolean) = EncodedFrame(
        owner = id,
        codecId = codecId,
        seq = SequenceNumber(seq++),
        payload = payload,
        isTerminal = terminal,
    )
}
