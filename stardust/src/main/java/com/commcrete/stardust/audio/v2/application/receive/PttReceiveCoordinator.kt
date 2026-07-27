package com.commcrete.stardust.audio.v2.application.receive

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.Gain
import com.commcrete.stardust.audio.v2.domain.StreamKey

/**
 * Application layer — the receive entry point (R5) and the UI-facing volume API (R6).
 *
 * The adapter-ring `StardustPackageRouter` does all `StardustPackage` parsing and calls [onFrame]
 * with already-parsed DOMAIN types, so no framework type crosses into this ring. Dispatch is by
 * opcode via the registry table — adding a codec needs no edit here. An unknown opcode (untrusted
 * wire data) is dropped rather than throwing.
 *
 * A `class` with an injected [StreamRegistry] (consistent with [com.commcrete.stardust.audio.v2.application.send.PttSendCoordinator]);
 * the host wires one instance and the `VolumeUiAdapter` calls [setVolume]/[setMuted].
 */
class PttReceiveCoordinator(private val registry: StreamRegistry) {

    /** Route one received frame to its stream, creating the stream on first packet. */
    fun onFrame(opcode: Int, key: StreamKey, frame: EncodedFrame) {
        val codec = CodecRegistry.byOpcode(opcode) ?: return
        registry.getOrCreate(key, codec).onFrame(frame)
    }

    /** UI: set the playback level for one incoming PTT (does not un-mute). */
    fun setVolume(key: StreamKey, gain: Gain) = registry.setVolume(key, gain)

    /** UI: mute/un-mute one incoming PTT independently of all others. */
    fun setMuted(key: StreamKey, isMuted: Boolean) = registry.setMuted(key, isMuted)
}
