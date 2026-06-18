package com.commcrete.stardust.audio.v2.transport

import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.util.Carrier

/**
 * Outbound wire for encoded PTT payloads.
 *
 * Decouples the encode pipeline from the radio so:
 *  - the test feeder can run without actually transmitting
 *    ([NoOpSendTransport]),
 *  - future transports (e.g. file capture for replay, IP relay) can
 *    be plugged in without touching the encoder or coordinator.
 *
 * The default production impl ([BleSendTransport]) wraps
 * `DataManager.sendDataToBle` + `StardustPackageUtils.getStardustPackage`.
 */
interface SendTransport {

    /**
     * Send one encoded packet.
     *
     * @param codec        the codec the payload came from; used for
     *                     opcode + prefix lookup.
     * @param payload      already-encoded, prefix-prepended, sanitized
     *                     payload (the coordinator handles those steps
     *                     before calling).
     * @param isLast       wire-level LAST control flag. Set when the
     *                     payload is the final one of a stream (via
     *                     [com.commcrete.stardust.audio.v2.codec.AudioCodec.isLastPacket]).
     * @param carrier      radio carrier override; null falls back to
     *                     the consumer's default.
     * @param source       sender identifier — e.g. local user ID.
     * @param destination  recipient identifier — `null` is a valid
     *                     no-op (artifact-only feeder runs use this).
     */
    suspend fun send(
        codec: AudioCodec,
        payload: ByteArray,
        isLast: Boolean,
        carrier: Carrier?,
        source: String,
        destination: String?,
    )
}

