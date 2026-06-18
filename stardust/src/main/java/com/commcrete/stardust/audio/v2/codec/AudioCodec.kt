package com.commcrete.stardust.audio.v2.codec

import com.commcrete.stardust.stardust.StardustPackageUtils.StardustOpCode
import com.commcrete.stardust.util.audio.RecorderUtils

/**
 * Complete description of one PTT codec. **Stateless** — all per-stream
 * mutable state lives in the [EncoderSession] / [DecoderSession]
 * returned by [newEncoderSession] / [newDecoderSession].
 *
 * Implementations must be safe to register once at SDK init (via
 * [CodecRegistry.register]) and live for the entire process. They MUST
 * NOT hold any per-recording state themselves.
 *
 * Adding a new codec is exactly:
 *  1. implement this interface,
 *  2. implement [EncoderSession] + [DecoderSession] for it,
 *  3. add the wire opcode to [StardustOpCode],
 *  4. register at bootstrap.
 *
 * No other v2 type should ever need to change to support a new codec.
 */
interface AudioCodec {

    /** Stable codec identity — matches the public [RecorderUtils.CODE_TYPE] enum. */
    val id: RecorderUtils.CODE_TYPE

    /**
     * Sample rate the codec consumes (encode) AND produces (decode).
     * `PttAudioProcessorV2.targetRate` is set from this value, so the
     * preprocessor resamples capture-rate PCM down to whatever the
     * codec wants.
     */
    val sampleRateHz: Int

    /** Opcode for outbound packets sent via [com.commcrete.stardust.audio.v2.transport.SendTransport]. */
    val opCodeSend: StardustOpCode

    /**
     * Opcodes this codec owns on the receive side. Used by
     * [CodecRegistry.forOpCode] to dispatch incoming packets to the
     * correct codec. Most codecs have one; the set allows aliases.
     */
    val opCodesReceive: Set<StardustOpCode>

    /**
     * Bytes pre-pended to every outbound payload BEFORE
     * [sanitizePayload] runs. Currently used by AI to put the
     * model-type byte (`0x00`) at index 0 of every packet. CODEC2
     * returns an empty array.
     */
    val sendPayloadPrefix: ByteArray get() = ByteArray(0)

    /**
     * Last-mile mutation of a fully-prefixed payload, e.g. CODEC2's
     * tail-collision sanitizer (randomize trailing two bytes when they
     * match the start-of-text sentinel). Default is identity.
     *
     * The default impl is safe for codecs that don't need it — call
     * site never has to know.
     */
    fun sanitizePayload(payload: ByteArray): ByteArray = payload

    /**
     * Whether the packet about to be transmitted is the last one of
     * the current stream. [flushed] is `true` when the encoder is
     * draining (via [EncoderSession.flush]); codecs that mark "last"
     * based on packet size (legacy AI did `data.size != 30`) can
     * override.
     *
     * Default trusts the flush signal — recommended for new codecs.
     */
    fun isLastPacket(encodedPayloadBytes: Int, flushed: Boolean): Boolean = flushed

    /**
     * Create a fresh per-recording encoder state. The returned session
     * is NOT thread-safe — callers MUST hold
     * [CodecRegistry.withCodec] for the duration of every method call.
     */
    fun newEncoderSession(): EncoderSession

    /**
     * Create a fresh per-stream decoder state. Same threading rules
     * as [newEncoderSession]: caller MUST hold the codec mutex.
     *
     * On the receive side, [com.commcrete.stardust.audio.v2.session.PttReceiveCoordinator]
     * keeps one decoder session per `(from|source)` stream key.
     */
    fun newDecoderSession(): DecoderSession
}

