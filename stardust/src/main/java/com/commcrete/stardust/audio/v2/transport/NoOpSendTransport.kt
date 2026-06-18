package com.commcrete.stardust.audio.v2.transport

import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.util.Carrier

/**
 * [SendTransport] that swallows every packet.
 *
 * Used by the test feeder when running in **artifact-only** mode —
 * the encoder + DSP + mirror pipeline still runs end-to-end (so the
 * local WAV under `WavLocalMirror` is produced), but no BLE traffic
 * is generated. Replaces the legacy
 * [com.commcrete.stardust.util.audio.AudioFeederEngine] convention of
 * passing `destinationProvider = { null }` into `Codec2SendPipeline` /
 * skipping `PttSendManager.addNewFrame`.
 */
object NoOpSendTransport : SendTransport {

    override suspend fun send(
        codec: AudioCodec,
        payload: ByteArray,
        isLast: Boolean,
        carrier: Carrier?,
        source: String,
        destination: String?,
    ) {
        // Intentionally empty.
    }
}

