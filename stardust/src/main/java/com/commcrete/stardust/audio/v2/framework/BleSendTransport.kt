package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.SendTransport
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import kotlin.random.Random

/**
 * Framework ring — puts a CODEC2 frame on the wire behind the [SendTransport] port. Ported from
 * `Codec2SendPipeline.sendPacket`: resolves the carrier/delivery, applies the SUFFIX-collision
 * randomization (a Stardust-framing concern that belongs here, not in the codec encoder), stamps the
 * part type from [EncodedFrame.isTerminal], recomputes the XOR check, and hands off to DataManager.
 *
 * Called ONLY by the transmit gate, so it is a single writer. Routing is per-recording via
 * [PttSendRouting], keyed by [EncodedFrame.owner].
 *
 * TODO(ack): CODEC2 is ACK-tracked, but `DataManager.sendDataToBle` is fire-and-enqueue; the legacy
 * resend/ACK bookkeeping lives deeper in ClientConnection. Until that is surfaced as a suspend point,
 * `send` returns after enqueue and the gate's per-frame deadline provides the only ceiling.
 */
class BleSendTransport(private val routing: PttSendRouting) : SendTransport {

    override suspend fun send(frame: EncodedFrame) {
        val owner = frame.owner ?: return
        val route = routing.get(owner) ?: return
        val radio = CarriersUtils.getRadioToSend(route.carrier, functionalityType = FunctionalityType.PTT) ?: return

        // Resolve the wire opcode from the frame's codec (CODEC2 → SEND_PTT 0x15, WavTokenizer → SEND_PTT_AI 0x3A).
        val opcode = CodecRegistry.byCodecId(frame.codecId).sendOpcode
        val stardustOpCode = StardustPackageUtils.StardustOpCode.values().firstOrNull { it.codeID == opcode } ?: return

        val audioIntArray = StardustPackageUtils.byteArrayToIntArray(frame.payload)
        // SUFFIX-collision randomization is a SEND_PTT (CODEC2) concern only — never mutate AI token payloads.
        if (opcode == StardustPackageUtils.StardustOpCode.SEND_PTT.codeID && audioIntArray.endsWithSuffix()) {
            val num = Random.nextInt(0, 41)
            audioIntArray[audioIntArray.lastIndex] = num
            audioIntArray[audioIntArray.lastIndex - 1] = num
        }

        val pkg = StardustPackageUtils.getStardustPackage(
            source = route.source,
            destination = route.destination,
            stardustOpCode = stardustOpCode,
            data = audioIntArray,
        )
        pkg.stardustControlByte.stardustPartType =
            if (frame.isTerminal) StardustControlByte.StardustPartType.LAST
            else StardustControlByte.StardustPartType.MESSAGE
        pkg.stardustControlByte.stardustDeliveryType = radio.deliveryType
        pkg.checkXor = StardustPackageUtils.getCheckXor(pkg.getStardustPackageToCheckXor())

        DataManager.sendDataToBle(pkg)
    }

    private fun Array<Int>.endsWithSuffix(): Boolean {
        if (size < SUFFIX.size) return false
        val off = size - SUFFIX.size
        for (i in SUFFIX.indices) if (this[off + i] != SUFFIX[i]) return false
        return true
    }

    private companion object {
        // Same tail-collision sentinel as legacy Codec2SendPipeline.SUFFIX — kept identical for wire compat.
        val SUFFIX = arrayOf(-50, -10, -128, -4, -17, 104, 0, 0)
    }
}
