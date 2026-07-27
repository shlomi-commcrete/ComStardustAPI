package com.commcrete.stardust.audio.v2.adapter

import com.commcrete.stardust.audio.v2.application.send.PttSendCoordinator
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.framework.PttSendRouting
import com.commcrete.stardust.audio.v2.framework.SendRoute
import com.commcrete.stardust.util.Carrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Adapter ring — the seam a (future) `RecorderUtils` patch calls when the feature flag is on, in place
 * of the legacy `AudioRecorderCodec2` path. Translates a key-down/up into [PttSendCoordinator] calls
 * and registers per-recording transport routing BEFORE the recording starts (via the `beforeStart`
 * hook), so no frame is emitted before its destination is known.
 */
class RecorderUtilsBridge(
    private val send: PttSendCoordinator,
    private val routing: PttSendRouting,
    private val scope: CoroutineScope,
) {
    @Volatile private var currentId: RecordingId? = null

    /** Key-down. [source] is this device's id, [destination] the peer id, [carrier] the radio (or null for default). */
    suspend fun startRecording(codecId: CodecId, source: String, destination: String, carrier: Carrier?) {
        val id = send.restart(codecId, StreamKey(destination)) { newId ->
            routing.register(newId, SendRoute(source, destination, carrier))
        }
        currentId = id
        // Release routing once the recording (including its post-key-up drain) has fully finalized.
        scope.launch {
            send.awaitFinalized(id)
            routing.release(id)
        }
    }

    /** Key-up: release the mic; encoding + the ordered send continue in the background. */
    suspend fun stopRecording() {
        currentId?.let { send.finish(it) }
    }
}
