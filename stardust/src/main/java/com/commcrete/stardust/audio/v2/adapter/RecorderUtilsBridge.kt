package com.commcrete.stardust.audio.v2.adapter

import com.commcrete.stardust.PttRecordingError
import com.commcrete.stardust.audio.v2.application.send.PttSendCoordinator
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TerminalReason
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.audio.v2.framework.PttSendRouting
import com.commcrete.stardust.audio.v2.framework.PttSendStore
import com.commcrete.stardust.audio.v2.framework.SendRoute
import com.commcrete.stardust.util.Carrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Adapter ring — the seam `RecorderUtils` calls when the feature flag is on, in place of the legacy
 * `AudioRecorderCodec2` path. Translates a key-down/up into [PttSendCoordinator] calls and registers
 * per-recording transport routing BEFORE the recording starts (via the `beforeStart` hook), so no frame
 * is emitted before its destination is known.
 *
 * **Key-down/key-up are ordered by a command channel, not by the caller's coroutines.** [startRecording]
 * and [stopRecording] are deliberately non-suspend and only `trySend` onto [commands]: the ordering that
 * matters (press before release) exists at the call site, so it is captured there, synchronously. The
 * previous design launched each on its own `CoroutineScope(Dispatchers.Default)`, which imposed no
 * ordering at all — a short press could deliver the key-up first, find no recording id yet, no-op, and
 * leave the microphone running until the max-PTT watchdog fired ~45 s later.
 */
class RecorderUtilsBridge(
    private val send: PttSendCoordinator,
    private val routing: PttSendRouting,
    private val sendStore: PttSendStore,
    private val scope: CoroutineScope,
) {
    private sealed interface Cmd

    private class Start(
        val codecId: CodecId,
        val chatId: String,
        val source: String,
        val destination: String,
        val carrier: Carrier?,
        val startedAtMs: Long,
        /** Host-facing recording id minted by `RecorderUtils`; correlates the SENT/ERROR events. */
        val recordingId: String,
    ) : Cmd

    private object Stop : Cmd

    private val commands = Channel<Cmd>(Channel.UNLIMITED)

    @Volatile private var currentId: RecordingId? = null

    init {
        scope.launch {
            for (cmd in commands) {
                // Per-command isolation: a failure handling one key-down (e.g. the AI models never
                // loaded) must not kill the loop and take PTT down for the rest of the process.
                runCatching { handle(cmd) }
                    .onFailure {
                        Timber.tag(TAG).e(it, "PTT v2 command failed: ${cmd::class.simpleName}")
                        if (cmd is Start) {
                            RecorderUtils.notifyPttRecordingError(errorFor(it), cmd.recordingId)
                        }
                    }
            }
        }
    }

    /**
     * Key-down. [source] is this device's id, [destination] the peer id, [chatId] the chat the PTT
     * belongs to (for the history row), [carrier] the radio (or null for default).
     */
    fun startRecording(
        codecId: CodecId,
        chatId: String,
        source: String,
        destination: String,
        carrier: Carrier?,
        recordingId: String,
    ) {
        commands.trySend(
            Start(codecId, chatId, source, destination, carrier, System.currentTimeMillis(), recordingId)
        )
    }

    /** Key-up: release the mic. Encoding + the ordered send continue in the background. */
    fun stopRecording() {
        commands.trySend(Stop)
    }

    private suspend fun handle(cmd: Cmd) {
        when (cmd) {
            is Start -> handleStart(cmd)
            is Stop -> handleStop()
        }
    }

    private suspend fun handleStart(cmd: Start) {
        val id = send.restart(cmd.codecId, StreamKey(cmd.destination)) { newId ->
            routing.register(newId, SendRoute(cmd.source, cmd.destination, cmd.carrier))
        }
        currentId = id
        // After the recording finalizes (incl. its post-key-up drain + mirror WAV) AND the transmit gate
        // has committed every frame: release routing and persist the SENT history row. Routing must
        // outlive the transmit — releasing it at finalize drops the recording's still-queued tail,
        // because BleSendTransport silently skips any frame whose route is gone.
        scope.launch {
            val reason = send.awaitFinalized(id)
            send.awaitTransmitted(id)
            routing.release(id)
            sendStore.onFinalized(id, cmd.chatId, cmd.destination, cmd.codecId, cmd.startedAtMs)
            // Only a clean LAST means the whole recording reached the link. TIMEOUT means the gate's
            // watchdog discarded the tail; ERROR/CANCELLED mean the pipeline gave up — none of those are
            // "sent". A more specific error (e.g. MIC_UNAVAILABLE) already reported wins over this one.
            if (reason == TerminalReason.LAST) {
                RecorderUtils.notifyPttRecordingSent(cmd.recordingId)
            } else {
                RecorderUtils.notifyPttRecordingError(PttRecordingError.UNKNOWN, cmd.recordingId)
            }
        }
    }

    /** An encoder that never became available is worth distinguishing from a generic pipeline failure. */
    private fun errorFor(t: Throwable): PttRecordingError =
        if (t is UninitializedPropertyAccessException || t is IllegalStateException) {
            PttRecordingError.ENCODER_UNAVAILABLE
        } else {
            PttRecordingError.UNKNOWN
        }

    private suspend fun handleStop() {
        val id = currentId ?: return
        currentId = null
        send.finish(id)
    }

    private companion object {
        const val TAG = "PttV2Bridge"
    }
}
