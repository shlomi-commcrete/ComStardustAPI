package com.commcrete.stardust.audio.v2.application.send

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.application.port.Clock
import com.commcrete.stardust.audio.v2.application.port.KeepAlive
import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.application.port.MaxPttTimeoutNotifier
import com.commcrete.stardust.audio.v2.application.port.MessageStore
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
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
    private val captureProvider: (codecId: CodecId, nativeRateHz: Int) -> CaptureSource,
    private val dspFactory: (targetRateHz: Int) -> PttAudioProcessorV2,
    private val mirrorFactory: (RecordingId) -> LocalMirror,
    private val store: MessageStore,
    private val keepAlive: KeepAlive,
    private val clock: Clock,
    private val watchdogMs: Long,
    /** How long a session may take to actually release the mic once asked — see [RecordingSession]. */
    private val captureStopGraceMs: Long,
    /** Ceiling on how long one recording is tracked before it is retired — see [trackCompletion]. */
    private val finalizeTimeoutMs: Long,
    /** How many recordings may be encoding at once — see [makeRoomForOneMore]. */
    private val maxConcurrentRecordings: Int,
    private val maxTimeout: MaxPttTimeoutNotifier,
    private val scope: CoroutineScope,
) {
    private val restartMutex = Mutex()
    private val idSeq = AtomicLong(0L)

    /** Recordings still in flight. Evicted by [trackCompletion] once there is nothing left to wait on. */
    private val sessions = ConcurrentHashMap<RecordingId, RecordingSession>()

    /**
     * How the last [OUTCOME_HISTORY] evicted recordings ended, so [awaitFinalized] can still answer for
     * one after its session is gone. Without it, eviction would silently turn every late query into the
     * unknown-id fallback — a recording that sent perfectly would be reported to the host as CANCELLED.
     *
     * A recording is entered here BEFORE it leaves [sessions], so no lookup can fall between the two.
     */
    private val outcomes: MutableMap<RecordingId, TerminalReason> = Collections.synchronizedMap(
        object : LinkedHashMap<RecordingId, TerminalReason>(OUTCOME_HISTORY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RecordingId, TerminalReason>) =
                size > OUTCOME_HISTORY
        }
    )

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
        makeRoomForOneMore()

        val codec = CodecRegistry.byCodecId(codecId)
        val id = RecordingId(idSeq.incrementAndGet())
        beforeStart(id)
        val outbound = sequencer.reserve(id)
        // The ticket is already queued at the gate, so anything that throws while building the session
        // (an unavailable native encoder, a mic that won't open) MUST still seal the buffer — otherwise
        // its channel never closes, the gate's head blocks on it forever, and PTT send dies process-wide.
        val session = try {
            RecordingSession(
                id = id,
                peer = peer,
                codec = codec,
                capture = captureProvider(codecId, codec.sampleRateHz),
                dsp = dspFactory(codec.sampleRateHz),
                encoder = codec.newEncoderSession(id),
                outbound = outbound,
                mirror = mirrorFactory(id),
                store = store,
                keepAlive = keepAlive,
                clock = clock,
                watchdogMs = watchdogMs,
                captureStopGraceMs = captureStopGraceMs,
                maxTimeout = maxTimeout,
                scope = scope,
            )
        } catch (t: Throwable) {
            outbound.seal(TerminalReason.ERROR)
            throw t
        }
        sessions[id] = session
        currentCapture = session
        session.start()
        trackCompletion(id, session)
        id
    }

    /**
     * Admission control: abort the oldest recordings still encoding until one more can start without
     * exceeding [maxConcurrentRecordings]. Called under [restartMutex], so the count it acts on is the
     * count the new recording will join.
     *
     * Overlap is the design (recording N drains and sends while N+1 captures), but nothing bounded how
     * much of it could pile up: a mashed PTT button, or a wire slow enough that every recording sits in
     * `outbound.offer`, stacks encoders — and on the AI codec each of those is a PyTorch module plus,
     * when the local mirror is on, a decoder running a second forward pass per frame. Bounding it costs
     * the oldest recording's tail; not bounding it costs the process.
     *
     * Only recordings that have not yet finalized are counted or aborted. A finalized one still waiting
     * on the transmit gate has already closed its encoder and DSP, so it is holding nothing worth
     * reclaiming — and aborting it would throw away audio the user has already spoken for no gain.
     */
    private suspend fun makeRoomForOneMore() {
        val encoding = sessions.values.filter { !it.isFinalized }.sortedBy { it.id.value }
        val excess = encoding.size - (maxConcurrentRecordings - 1)
        if (excess <= 0) return
        encoding.take(excess).forEach { runCatching { it.abort() } }
    }

    /**
     * Abort every recording still in flight — the pipeline is going away. Stops the microphone rather
     * than only cancelling, so the device is released even when the capture thread is parked in a
     * native read that a cancellation alone would never reach.
     */
    suspend fun abortAll() = restartMutex.withLock {
        currentCapture = null
        sessions.values.forEach { runCatching { it.abort() } }
    }

    /**
     * Retire [id] once there is nothing left for anyone to await on it — that is, after the transmit
     * gate has committed its last frame, which is strictly later than its terminal seal.
     *
     * Retention used to be unconditional so that [awaitFinalized] always resolved, which made the map a
     * leak that grew by one recording (and its capture adapter, DSP and encoder) per key-down for the
     * life of the process. Keeping only the [TerminalReason] costs an enum reference instead.
     *
     * [finalizeTimeoutMs] is the backstop for the case the awaits never resolve at all — a drain wedged
     * inside a native forward pass, say. Eviction then records [TerminalReason.TIMEOUT]; the session
     * itself is not cancelled here, it is simply no longer tracked, so a recording that is merely
     * extraordinarily slow still finishes sending.
     */
    private fun trackCompletion(id: RecordingId, session: RecordingSession) {
        scope.launch {
            val reason = withTimeoutOrNull(finalizeTimeoutMs) {
                val sealedAs = session.awaitFinalized()
                session.awaitTransmitted()
                sealedAs
            }
            outcomes[id] = reason ?: TerminalReason.TIMEOUT
            sessions.remove(id)
        }
    }

    /**
     * Recordings still being tracked. Bounded by how many can be in flight at once, NOT by how many
     * the process has ever made — a number that keeps climbing across key-downs is the leak this
     * count exists to catch.
     */
    internal val liveRecordings: Int get() = sessions.size

    /**
     * Recordings still encoding — the ones actually holding an encoder, a DSP chain and, on the AI
     * codec, a model. This is what [maxConcurrentRecordings] bounds; [liveRecordings] counts those plus
     * the ones that have finished and are only waiting their turn on the wire.
     */
    internal val encodingRecordings: Int get() = sessions.values.count { !it.isFinalized }

    /** Key-up for [id]: release its mic. Encoding + the ordered send continue in the background. */
    suspend fun finish(id: RecordingId) = restartMutex.withLock {
        currentCapture?.takeIf { it.id == id }?.let {
            runCatching { it.stopCapture() }
            currentCapture = null
        }
        Unit
    }

    /**
     * Suspends until [id] has fully finalized (terminal LAST / ERROR / TIMEOUT / CANCELLED).
     *
     * An already-retired recording answers from [outcomes] without suspending. CANCELLED is the answer
     * for an id this coordinator has no record of at all — one that never started, or one retired so
     * long ago that [OUTCOME_HISTORY] newer recordings have pushed it out.
     */
    suspend fun awaitFinalized(id: RecordingId): TerminalReason =
        sessions[id]?.awaitFinalized() ?: outcomes[id] ?: TerminalReason.CANCELLED

    /**
     * Suspends until the transmit gate has put every frame of [id] on the transport. Later than
     * [awaitFinalized] — callers that release per-recording send state (routing) must await THIS.
     *
     * Returns at once for a retired recording, which is not a shortcut: retirement is defined as having
     * already awaited this (or having given up on it after [finalizeTimeoutMs]).
     */
    suspend fun awaitTransmitted(id: RecordingId) {
        sessions[id]?.awaitTransmitted()
    }

    // The single session currently holding the mic (null between key-up and the next key-down).
    @Volatile
    private var currentCapture: RecordingSession? = null

    private companion object {
        /** Recordings whose outcome outlives their session. The host only ever asks about the newest. */
        const val OUTCOME_HISTORY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
