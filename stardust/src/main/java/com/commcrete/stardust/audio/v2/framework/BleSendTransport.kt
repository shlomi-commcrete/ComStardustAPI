package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.util.LogOnce
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.StardustLog
import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.SendTransport
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.AppEvents
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

    /** Plain var: the transmit gate is this class's only caller, one frame at a time. */
    private var lastLoggedRadio: Pair<Long, Int>? = null

    /**
     * Per-frame drops log once per recording. A frame from a new owner starts a new run: the previous
     * run's repeats are summarised then, so the last recording's summary waits for the next one.
     * Plain var for the same single-caller reason as [lastLoggedRadio].
     */
    private val logOnce = LogOnce()
    private var logOnceOwner: RecordingId? = null

    override suspend fun send(frame: EncodedFrame) {
        if (frame.owner != null && frame.owner != logOnceOwner) {
            logOnceOwner = frame.owner
            for ((key, repeats) in logOnce.reset()) {
                if (key == KEY_NO_OPCODE) {
                    StardustLog.e(AppEvents.TX_RX_TRACE, "ptt frame DROPPED x$repeats more: $key")
                } else {
                    StardustLog.w(LogType.PTT, AppEvents.TX_RX_TRACE, "ptt frame DROPPED x$repeats more: $key")
                }
            }
        }
        val owner = frame.owner ?: run {
            // Logs once per recording; summarised when the next recording's first frame arrives.
            if (logOnce.first("no owner")) {
                StardustLog.w(LogType.PTT, AppEvents.TX_RX_TRACE, "ptt frame DROPPED: no owner")
            }
            return
        }
        val route = routing.get(owner) ?: run {
            // Logs once per recording; summarised when the next recording's first frame arrives.
            if (logOnce.first("route released")) {
                StardustLog.w(LogType.PTT, AppEvents.TX_RX_TRACE, "ptt frame of v2 ${owner.value} DROPPED: route released")
            }
            return
        }
        val radio = CarriersUtils.getRadioToSend(route.carrier, functionalityType = FunctionalityType.PTT) ?: run {
            // Logs once per recording; summarised when the next recording's first frame arrives.
            if (logOnce.first("no PTT radio")) {
                StardustLog.w(LogType.PTT, AppEvents.TX_RX_TRACE, "ptt frame of v2 ${owner.value} DROPPED: no PTT radio for RD ${route.carrier?.index}")
            }
            return
        }
        // Once per recording, and again if Auto resolves to a different radio mid-recording.
        val resolved = owner.value to radio.index
        if (resolved != lastLoggedRadio) {
            lastLoggedRadio = resolved
            StardustLog.d(
                LogType.PTT,
                AppEvents.TX_RX_TRACE,
                "ptt v2 ${owner.value} sends on carrier ${radio.index} (${radio.deliveryType}), requested ${route.carrier?.index ?: "auto"}",
            )
        }

        // Resolve the wire opcode from the frame's codec (CODEC2 → SEND_PTT 0x15, WavTokenizer → SEND_PTT_AI 0x3A).
        val opcode = CodecRegistry.byCodecId(frame.codecId).sendOpcode
        val stardustOpCode = pttOpCodeFor(opcode) ?: run {
            // The port returns nothing, so the drop is logged: once per recording, summarised when
            // the next recording's first frame arrives.
            if (logOnce.first(KEY_NO_OPCODE)) {
                StardustLog.e(AppEvents.TX_RX_TRACE, "ptt frame of v2 ${owner.value} DROPPED: no PTT opcode for codec ${frame.codecId}")
            }
            return
        }

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
        // getStardustPackage hands back the OpCode enum constant's OWN StardustControlByte instance.
        // Mutating it in place would rewrite that shared constant for every future package of this
        // opcode (and ClientConnection mutates `stardustServer` on it too), so swap in a copy first.
        pkg.stardustControlByte = pkg.stardustControlByte.copy(
            stardustPartType =
                if (frame.isTerminal) StardustControlByte.StardustPartType.LAST
                else StardustControlByte.StardustPartType.MESSAGE,
            stardustDeliveryType = radio.deliveryType,
        )
        pkg.checkXor = StardustPackageUtils.getCheckXor(pkg.getStardustPackageToCheckXor())

        DataManager.sendDataToBle(pkg)
    }

    /**
     * Map a codec's wire opcode to its PTT [StardustPackageUtils.StardustOpCode].
     *
     * This MUST NOT be done with `values().firstOrNull { it.codeID == opcode }`: `SEND_MESSAGE`,
     * `SEND_SOS` and `SEND_PTT` all carry `codeID = 0x15`, and `values()` is declaration order, so that
     * lookup resolves 0x15 to SEND_MESSAGE — whose control byte says PackageType=DATA + DEMAND_ACK
     * instead of SPEECH + NO_DEMAND_ACK. The radio then never treats the packet as voice, and a peer
     * dispatches it to `handleText` instead of `handlePTT`.
     */
    private fun pttOpCodeFor(opcode: Int): StardustPackageUtils.StardustOpCode? = when (opcode) {
        StardustPackageUtils.StardustOpCode.SEND_PTT.codeID -> StardustPackageUtils.StardustOpCode.SEND_PTT
        StardustPackageUtils.StardustOpCode.SEND_PTT_AI.codeID -> StardustPackageUtils.StardustOpCode.SEND_PTT_AI
        else -> null
    }

    private fun Array<Int>.endsWithSuffix(): Boolean {
        if (size < SUFFIX.size) return false
        val off = size - SUFFIX.size
        for (i in SUFFIX.indices) if (this[off + i] != SUFFIX[i]) return false
        return true
    }

    private companion object {
        /** The one [logOnce] key whose first line is an e; the others are w. */
        const val KEY_NO_OPCODE = "no PTT opcode for codec"

        // Same tail-collision sentinel as legacy Codec2SendPipeline.SUFFIX — kept identical for wire compat.
        val SUFFIX = arrayOf(-50, -10, -128, -4, -17, 104, 0, 0)
    }
}
