package com.commcrete.stardust.audio.v2.adapter

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.receive.PttReceiveCoordinator
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.framework.PttReceiveStore
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage

/**
 * Adapter ring — turns an incoming decrypted [StardustPackage] into domain types and hands it to the
 * receive coordinator. This is the ONLY place a framework `StardustPackage` is read; nothing framework
 * crosses into the application ring.
 *
 * `StardustPackageHandler.handlePTT` / `handlePTTAI` delegate here when the feature flag is on. The
 * codec is resolved by opcode, the stream key by sender, and terminality by the LAST part-type bit.
 */
class StardustPackageRouter(
    private val receive: PttReceiveCoordinator,
    private val store: PttReceiveStore,
) {

    /**
     * [opcodeOverride] lets the caller pin the codec by semantic intent rather than the raw wire
     * opcode — `handlePTT` uses it because a SPEECH-typed SEND_MESSAGE also lands there but doesn't
     * carry the SEND_PTT opcode. Falls back to the package's own opcode when null.
     */
    fun onPackage(pkg: StardustPackage, opcodeOverride: Int? = null) {
        val opcode = opcodeOverride ?: pkg.stardustOpCode.codeID
        val codec = CodecRegistry.byOpcode(opcode) ?: return
        val data = pkg.data ?: return

        val payload = ByteArray(data.size) { data[it].toByte() }
        val streamKey = StreamKey(pkg.getSourceAsString())
        val isTerminal =
            pkg.stardustControlByte.stardustPartType == StardustControlByte.StardustPartType.LAST

        val frame = EncodedFrame(
            codecId = codec.codecId,
            payload = payload,
            isTerminal = isTerminal,
        )
        // App-integration: first packet sets up the history row + startedReceivingPTT (has the pkg here);
        // receivePTT is delivered as decoded PCM from the decode path (PttReceiveStore).
        val encoderType = if (codec.codecId == CodecId.CODEC2) EncoderType.CODEC2 else EncoderType.AI
        store.onPacket(pkg, streamKey, encoderType)
        receive.onFrame(opcode, streamKey, frame)
    }
}
