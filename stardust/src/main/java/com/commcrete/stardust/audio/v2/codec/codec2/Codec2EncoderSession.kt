package com.commcrete.stardust.audio.v2.codec.codec2

import com.commcrete.stardust.audio.v2.codec.EncoderSession
import com.commcrete.stardust.util.audio.RecorderUtils
import com.ustadmobile.codec2.Codec2Encoder

/**
 * Per-recording CODEC2 encoder + packer + 77-byte packetizer.
 *
 * Replaces the encode + pack + accumulate logic from
 * [com.commcrete.stardust.util.audio.Codec2SendPipeline.enqueuePcm] /
 * `pushCodec2Frame` / `pushPacked7`. The behavior is intentionally
 * bit-identical so live and feeder paths keep producing byte-for-byte
 * comparable wire packets across the migration.
 *
 * # Pipeline (per [encode] call)
 *
 * ```
 * pcm @ 8 kHz mono
 *   ──► sampleRemainder buffer
 *   ──► whenever ≥ 320 samples buffered:
 *         ──► Codec2Encoder.encode(frame, chars)         (4-byte mode 700C frame)
 *         ──► if pending half-pair exists:
 *               ──► packTwoCodec2Frames(pending, new)   (4+4 → 7 bytes)
 *               ──► append to packetBuffer
 *               ──► flush packetBuffer in 77-byte chunks
 *             else:
 *               ──► stash as pending half-pair
 * ```
 *
 * Each completed 77-byte chunk is emitted from [encode] as one entry
 * in the returned list. A typical call processing one 40 ms frame
 * produces zero or one packet (depending on whether the buffer
 * filled). Calls processing longer chunks (e.g. the test feeder
 * pushing 500 ms at once) may emit several packets back-to-back.
 *
 * [flush] zero-pads any half-buffered samples, fakes a second frame
 * for the half-pair, and emits the partial 77-byte packet so the
 * coordinator can mark it LAST on the wire.
 *
 * **Threading.** Caller MUST hold
 * [com.commcrete.stardust.audio.v2.codec.CodecRegistry.withCodec] for
 * every call. The native [Codec2Encoder] instance is not thread-safe.
 */
internal class Codec2EncoderSession : EncoderSession {

    private val encoder = Codec2Encoder(RecorderUtils.CodecValues.MODE700.mode)

    /** PCM samples that didn't fill a complete 320-sample frame yet. */
    private val sampleRemainder = ArrayList<Short>()

    /**
     * If we encoded one frame but not yet a pair, its 4 bytes wait
     * here. Cleared once paired (or zero-padded during [flush]).
     */
    private var pending4Bytes: ByteArray? = null

    /** Accumulated 7-byte pairs; flushed in 77-byte chunks. */
    private val packetBuffer = ArrayList<Byte>()

    override fun encode(pcm: ShortArray): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        pcm.forEach { sampleRemainder.add(it) }
        val out = mutableListOf<ByteArray>()
        while (sampleRemainder.size >= CODEC2_FRAME_SAMPLES) {
            val frame = ShortArray(CODEC2_FRAME_SAMPLES)
            for (i in 0 until CODEC2_FRAME_SAMPLES) frame[i] = sampleRemainder[i]
            repeat(CODEC2_FRAME_SAMPLES) { sampleRemainder.removeAt(0) }
            pushCodec2Frame(frame, out)
        }
        return out
    }

    override fun flush(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        // 1. Zero-pad any half-frame of samples.
        if (sampleRemainder.isNotEmpty()) {
            val padded = ShortArray(CODEC2_FRAME_SAMPLES)
            for (i in sampleRemainder.indices) padded[i] = sampleRemainder[i]
            sampleRemainder.clear()
            pushCodec2Frame(padded, out)
        }
        // 2. Pair any lone encoded frame with a zero second frame.
        pending4Bytes?.let {
            val packed = packTwoCodec2Frames(it, ByteArray(CODEC2_FRAME_BYTES))
            packed.forEach { b -> packetBuffer.add(b) }
            // Drain whole 77-byte packets first…
            while (packetBuffer.size >= PTT_PACKET_BYTES) {
                val payload = packetBuffer.subList(0, PTT_PACKET_BYTES).toByteArray()
                repeat(PTT_PACKET_BYTES) { packetBuffer.removeAt(0) }
                out.add(payload)
            }
            pending4Bytes = null
        }
        // 3. …then emit whatever partial packet remains as the LAST one.
        if (packetBuffer.isNotEmpty()) {
            out.add(packetBuffer.toByteArray())
            packetBuffer.clear()
        }
        return out
    }

    override fun close() {
        // Native Codec2Encoder doesn't expose a release() that we own
        // — its lifetime is the JVM object, and we drop the reference
        // here. If a future revision adds a destroy() method, hook it
        // in here.
        sampleRemainder.clear()
        pending4Bytes = null
        packetBuffer.clear()
    }

    // ── internals ────────────────────────────────────────────────────

    private fun pushCodec2Frame(frame: ShortArray, out: MutableList<ByteArray>) {
        val chars = CharArray(RecorderUtils.CodecValues.MODE700.charNumOutput)
        encoder.encode(frame, chars)
        val bytes = ByteArray(chars.size) { i -> chars[i].code.toByte() }
        val pending = pending4Bytes
        if (pending == null) {
            pending4Bytes = bytes
        } else {
            val packed = packTwoCodec2Frames(pending, bytes)
            packed.forEach { packetBuffer.add(it) }
            pending4Bytes = null
            while (packetBuffer.size >= PTT_PACKET_BYTES) {
                val payload = packetBuffer.subList(0, PTT_PACKET_BYTES).toByteArray()
                repeat(PTT_PACKET_BYTES) { packetBuffer.removeAt(0) }
                out.add(payload)
            }
        }
    }

    /**
     * Pack two 4-byte CODEC2 mode-700C frames (28 useful bits each,
     * top nibble of byte 3 padding) into a 7-byte (56-bit) chunk.
     * Bit-identical to `Codec2SendPipeline.packTwoCodec2Frames`.
     */
    private fun packTwoCodec2Frames(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(7)
        out[0] = a[0]
        out[1] = a[1]
        out[2] = a[2]
        out[3] = ((a[3].toInt() and 0xF0) or ((b[0].toInt() and 0xFF) ushr 4)).toByte()
        out[4] = (((b[0].toInt() and 0xFF) shl 4) or ((b[1].toInt() and 0xFF) ushr 4)).toByte()
        out[5] = (((b[1].toInt() and 0xFF) shl 4) or ((b[2].toInt() and 0xFF) ushr 4)).toByte()
        out[6] = (((b[2].toInt() and 0xFF) shl 4) or ((b[3].toInt() and 0xFF) ushr 4)).toByte()
        return out
    }

    companion object {
        /** Codec2 mode 700C frame size — 40 ms @ 8 kHz. */
        private const val CODEC2_FRAME_SAMPLES = 320

        /** One mode-700C frame packs into 4 bytes. */
        private const val CODEC2_FRAME_BYTES = 4

        /**
         * BLE payload size — matches `Codec2SendPipeline.PTT_PACKET_BYTES`
         * and the legacy `WavRecorder.appendToArray` sentinel.
         */
        private const val PTT_PACKET_BYTES = 77
    }
}

