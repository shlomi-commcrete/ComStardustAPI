package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.util.Carrier
import java.util.concurrent.ConcurrentHashMap

/** Per-recording Stardust routing: where a recording's packets go and over which carrier. */
data class SendRoute(
    val source: String,
    val destination: String,
    val carrier: Carrier?,
)

/**
 * Framework ring — maps [RecordingId] → [SendRoute] so [BleSendTransport] can address each frame to
 * the correct peer even while a later recording is already processing.
 *
 * The routing MUST be registered before the recording emits any frame; [PttSendCoordinator.restart]'s
 * `beforeStart` hook guarantees that ordering. Released when the recording finalizes.
 */
class PttSendRouting {
    private val routes = ConcurrentHashMap<RecordingId, SendRoute>()

    fun register(id: RecordingId, route: SendRoute) { routes[id] = route }
    fun release(id: RecordingId) { routes.remove(id) }
    fun get(id: RecordingId): SendRoute? = routes[id]
}
