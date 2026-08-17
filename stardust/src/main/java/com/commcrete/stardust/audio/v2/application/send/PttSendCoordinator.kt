package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.application.port.Clock
import com.commcrete.stardust.audio.v2.application.port.KeepAlive
import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.application.port.MessageStore
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Application layer — the send entry point (R2 single capture, R3 hand-off).
 *
 * A `class` rather than an `object` so its ports are constructor-injected (testable with fakes —
 * this is the resolution of the "coordinators as objects vs injected" open decision). The host wires
 * one instance and the `RecorderUtilsBridge` adapter delegates key-down/up to it behind the flag.
 *
 * [restart] holds [restartMutex] across "stop previous capture + reserve start-order ticket + build
 * fresh session + launch". Two things fall out:
 *  - R2: exactly one [CaptureSource] (mic) is ever live — the previous one is stopped before the next
 *    starts. The mic is released on key-up, NOT at finalize, so the previous recording keeps
 *    encoding/sending while this one captures (R3).
 *  - R4: because [TransmitSequencer.reserve] runs inside the same mutex, ticket order == start order.
 *
 * Per-recording collaborators (capture / dsp / encoder / mirror) are built fresh here — never shared —
 * which is the structural basis of isolation.
 */
class PttSendCoordinator(
    private val sequencer: TransmitSequencer,
    private val captureProvider: () -> CaptureSource,
    private val dspFactory: (targetRateHz: Int) -> PttAudioProcessorV2,
    private val mirrorFactory: (RecordingId) -> LocalMirror,
    private val store: MessageStore,
    private val keepAlive: KeepAlive,
    private val clock: Clock,
    private val watchdogMs: Long,
    private val scope: CoroutineScope,
) {
    private val restartMutex = Mutex()
    private val idSeq = AtomicLong(0L)

    // TODO(eviction): finalized sessions are retained so awaitFinalized() always resolves; add an
    //  idle/size-bounded eviction once the receive side and tests exist.
    private val sessions = ConcurrentHashMap<RecordingId, RecordingSession>()

    /**
     * Begin a new recording for [codecId] toward [peer]. Stops any in-flight capture first.
     *
     * [beforeStart] runs synchronously under the restart mutex with the freshly-minted [RecordingId],
     * BEFORE the session starts capturing/encoding — the framework wires transport routing here so a
     * frame can never reach the wire before its destination is registered.
     */
    suspend fun restart(
        codecId: CodecId,
        peer: StreamKey,
        beforeStart: (RecordingId) -> Unit = {},
    ): RecordingId = restartMutex.withLock {
        // Free the single mic; do NOT await the previous recording's drain — it keeps sending.
        currentCapture?.let { runCatching { it.stopCapture() } }

        val codec = CodecRegistry.byCodecId(codecId)
        val id = RecordingId(idSeq.incrementAndGet())
        beforeStart(id)
        val session = RecordingSession(
            id = id,
            peer = peer,
            codec = codec,
            capture = captureProvider(),
            dsp = dspFactory(codec.sampleRateHz),
            encoder = codec.newEncoderSession(id),
            outbound = sequencer.reserve(id),
            mirror = mirrorFactory(id),
            store = store,
            keepAlive = keepAlive,
            clock = clock,
            watchdogMs = watchdogMs,
            scope = scope,
        )
        sessions[id] = session
        currentCapture = session
        session.start()
        id
    }

    /** Key-up for [id]: release its mic. Encoding + the ordered send continue in the background. */
    suspend fun finish(id: RecordingId) = restartMutex.withLock {
        currentCapture?.takeIf { it.id == id }?.let {
            runCatching { it.stopCapture() }
            currentCapture = null
        }
        Unit
    }

    /** Suspends until [id] has fully finalized (terminal LAST / ERROR / TIMEOUT / CANCELLED). */
    suspend fun awaitFinalized(id: RecordingId): TerminalReason =
        sessions[id]?.awaitFinalized() ?: TerminalReason.CANCELLED

    // The single session currently holding the mic (null between key-up and the next key-down).
    @Volatile
    private var currentCapture: RecordingSession? = null
}
