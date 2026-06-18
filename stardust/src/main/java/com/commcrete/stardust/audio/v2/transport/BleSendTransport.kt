package com.commcrete.stardust.audio.v2.transport

import android.content.Context
import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import timber.log.Timber

/**
 * Production [SendTransport] — writes encoded packets to the radio.
 *
 * Replaces the inline `StardustPackageUtils.getStardustPackage` +
 * `DataManager.sendDataToBle` call chain that lives at the end of
 * both [com.commcrete.stardust.ai.codec.PttSendManager.sendData] and
 * [com.commcrete.stardust.util.audio.Codec2SendPipeline.sendPacket].
 *
 * # Behavior preserved from the legacy paths
 *
 *  - Carrier lookup via [CarriersUtils.getRadioToSend] with
 *    `FunctionalityType.PTT` — same call shape as both legacy paths.
 *  - `StardustControlByte.stardustPartType` set to `LAST` or
 *    `MESSAGE` based on [isLast].
 *  - `stardustDeliveryType` set from the carrier's second tuple value.
 *  - XOR checksum recomputed via
 *    `StardustPackageUtils.getCheckXor(pkg.getStardustPackageToCheckXor())`.
 *
 * The `null` carrier and `null` destination cases are handled by
 * silently dropping the packet (matches `Codec2SendPipeline.sendPacket`
 * behavior — feeder artifact-only runs lean on this).
 *
 * # NOT preserved
 *
 *  - `onPacketSent` per-packet callback. The legacy CODEC2 path used
 *    it to enforce its `numOfPackage * 880 > maxSecondsPTT` packet
 *    timeout; v2 enforces a wall-clock watchdog in
 *    [com.commcrete.stardust.audio.v2.session.RecordingSession] instead,
 *    so transport doesn't need to count packets. Diagnostic hooks
 *    can be added back via [Timber] logs if needed.
 */
class BleSendTransport(
    private val context: Context,
) : SendTransport {

    override suspend fun send(
        codec: AudioCodec,
        payload: ByteArray,
        isLast: Boolean,
        carrier: Carrier?,
        source: String,
        destination: String?,
    ) {
        if (destination.isNullOrBlank()) {
            // Artifact-only / no-destination — silently drop, matching
            // Codec2SendPipeline.sendPacket's `dest ?: return` path.
            return
        }
        val radio = CarriersUtils.getRadioToSend(
            carrier = carrier,
            functionalityType = FunctionalityType.PTT,
        ) ?: run {
            Timber.w("BleSendTransport: no radio for carrier=$carrier — dropping packet")
            return
        }

        val intArray = StardustPackageUtils.byteArrayToIntArray(payload)
        val pkg = StardustPackageUtils.getStardustPackage(
            context = context,
            source = source,
            destenation = destination,
            stardustOpCode = codec.opCodeSend,
            data = intArray,
        )
        pkg.stardustControlByte.stardustPartType = if (isLast) {
            StardustControlByte.StardustPartType.LAST
        } else {
            StardustControlByte.StardustPartType.MESSAGE
        }
        pkg.stardustControlByte.stardustDeliveryType = radio.second
        pkg.checkXor = StardustPackageUtils.getCheckXor(pkg.getStardustPackageToCheckXor())
        DataManager.sendDataToBle(pkg)
    }
}

