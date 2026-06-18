package com.commcrete.stardust.audio.v2.codec.codec2

import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.audio.v2.codec.DecoderSession
import com.commcrete.stardust.audio.v2.codec.EncoderSession
import com.commcrete.stardust.stardust.StardustPackageUtils.StardustOpCode
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlin.random.Random

/**
 * v2 wrapper around the legacy CODEC2 encode/decode pipeline.
 *
 * Replaces the `Codec2Encoder` + bit-packing + 77-byte BLE chunking
 * that lives inline in [com.commcrete.stardust.util.audio.Codec2SendPipeline] and
 * the `Codec2Decoder` field in [com.commcrete.stardust.util.audio.PlayerUtils].
 *
 * # Why CODEC2 needs sessions
 *
 * CODEC2 mode 700C produces 4-byte frames from 320-sample (40 ms)
 * chunks. Two frames pack into a 7-byte chunk; eleven 7-byte chunks
 * make a 77-byte BLE packet. That packing happens **across encode
 * calls** — so the encoder session must hold:
 *  - leftover input samples that didn't fill a 320-sample frame,
 *  - the half-pair byte buffer when we have one frame but not yet a
 *    pair,
 *  - the partial BLE packet under 77 bytes.
 *
 * All of that lives in [Codec2EncoderSession]. The flush logic that
 * zero-pads the incomplete frame, fakes a second frame for the pair,
 * and sends the half-filled packet marked as LAST lives there too.
 *
 * # Tail-collision sanitizer
 *
 * The CODEC2 output occasionally produces a tail that matches the
 * Stardust start-of-text sentinel (`[-50,-10,-128,-4,-17,104,0,0]`).
 * The legacy code randomized the last two bytes when this happened
 * — same logic is preserved here in [sanitizePayload].
 */
class Codec2AudioCodec : AudioCodec {

    override val id: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.CODEC2
    override val sampleRateHz: Int = 8_000
    override val opCodeSend: StardustOpCode = StardustOpCode.SEND_PTT
    override val opCodesReceive: Set<StardustOpCode> = setOf(StardustOpCode.SEND_PTT)

    /** CODEC2 packets are sent raw — no prefix byte. */
    override val sendPayloadPrefix: ByteArray = ByteArray(0)

    override fun sanitizePayload(payload: ByteArray): ByteArray {
        if (payload.size < SUFFIX.size) return payload
        // Compare last N bytes against SUFFIX. SUFFIX is in `Int` form
        // because the legacy code worked in IntArray (Stardust packet
        // bytes get widened); we compare as Byte for cheapness here.
        val tailStart = payload.size - SUFFIX.size
        var matches = true
        for (i in SUFFIX.indices) {
            if (payload[tailStart + i].toInt() and 0xFF != SUFFIX[i] and 0xFF) {
                matches = false
                break
            }
        }
        if (!matches) return payload
        // Match the legacy mutation exactly:
        //   val num = Random.nextInt(0, 41)
        //   array[lastIndex] = num
        //   array[lastIndex - 1] = num
        val out = payload.copyOf()
        val num = Random.nextInt(0, 41).toByte()
        out[out.lastIndex] = num
        out[out.lastIndex - 1] = num
        return out
    }

    override fun newEncoderSession(): EncoderSession = Codec2EncoderSession()

    override fun newDecoderSession(): DecoderSession = Codec2DecoderSession()

    companion object {
        /**
         * Sentinel that the receiver interprets as start-of-text in
         * other protocols — payloads ending in this must be randomized.
         * Same array literal as `Codec2SendPipeline.SUFFIX`.
         */
        internal val SUFFIX = intArrayOf(-50, -10, -128, -4, -17, 104, 0, 0)
    }
}

