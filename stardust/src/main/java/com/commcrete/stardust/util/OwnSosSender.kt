package com.commcrete.stardust.util

import android.location.Location
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.model.StardustAppEventPackage.StardustAppEventType
import com.commcrete.stardust.transport.isUsable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * This user's own SOS, from the trigger until the radio has transmitted it. The one owner of
 * that lifecycle, so the host can show "sent" only for an SOS that went out.
 *
 * What the radio says. Per the firmware spec, the SOS (0x05) is answered by RECEIVE_SOS 0x85 with
 * Ack (0x01: received, send process started) or Nack (0x02); the process's end is reported later by
 * an interrupt (SEND_SOS_INTERRUPT 0xF9). Observed on device: the Ack ~150 ms after the write, then
 * a TXStart and, ~2 s on, a TXFinish on one transceiver; the radio repeats the SOS every 30 s and
 * sends 0xF9 after the 7th. A received SOS never produces 0x85.
 *
 * Retried until the radio answers — through no link, no local identity yet, a lost write or a lost
 * reply. A Nack stops it in [State.Refused] (the operator retries or cancels). An accepted SOS is
 * never re-sent, so receivers do not get it twice. Held in memory only; process death drops it.
 */
object OwnSosSender {

    private const val LOG_TAG = "OwnSosSender"

    /** RECEIVE_SOS's first byte: Ack — received, send process started (spec; observed `[1,0,0,0]`). */
    internal const val ACCEPTED = 1

    /** RECEIVE_SOS's first byte: Nack — the radio will not send it (spec; not yet observed). */
    internal const val REFUSED = 2

    /** Observed reply ~150 ms after the write; generous for a busy link. */
    internal const val REPLY_TIMEOUT_MS = 2_000L

    internal const val RETRY_DELAY_MS = 3_000L

    /** Observed TXFinish ~2 s after acceptance; allows for a channel busy receiving. */
    internal const val TX_CONFIRM_MS = 15_000L

    private const val EVENT_BUFFER = 16

    sealed interface State {
        object Idle : State

        /** Not accepted by the radio yet; retried until it is, or cancelled. */
        data class Pending(val waitingFor: Waiting, val attempts: Int) : State

        /** The radio accepted it (0x85) and has not reported transmitting it yet. */
        object Accepted : State

        /**
         * Accepted, but no transmission reported within [TX_CONFIRM_MS]. The radio holds it and
         * may still transmit; [Sent] follows if it reports doing so.
         */
        object AcceptedUnconfirmed : State

        /** The radio reported transmitting it. */
        object Sent : State

        /**
         * The radio answered Nack: it will not send this SOS, and nothing is retried on its own —
         * the operator retries ([retry]) or cancels. Nothing is held on the radio.
         */
        object Refused : State
    }

    enum class Waiting {
        /** No usable radio link. */
        RADIO,
        /** Linked, but the radio has not reported its Device ID — nothing to address it from. */
        IDENTITY,
        /** Written; waiting for the radio's reply. */
        RADIO_REPLY,
    }

    // Seams for the unit tests, as TxEndGate has: the radio boundary, logging and the dispatcher.
    /** Null when an SOS can be written now; otherwise what it waits for. */
    internal var sendable: () -> Flow<Waiting?> = {
        combine(DataManager.connectionState(), RegisteredUserUtils.currentUserFlow) { link, _ ->
            when {
                !link.isUsable -> Waiting.RADIO
                requireLocalSrcDst() == null -> Waiting.IDENTITY
                else -> null
            }
        }
    }
    internal var log: (String, Throwable?) -> Unit = { msg, e -> StardustLog.i(LOG_TAG, msg, e) }
    internal var dispatcher: CoroutineDispatcher = Dispatchers.Default

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null

    /** An attempt was written, so the radio may hold this SOS even if it never answered. */
    @Volatile private var written = false

    // Channels, not shared flows: the radio's reply can land before the waiting side subscribes.
    // Drained before every write, so only answers to that write are read.
    private val replies = Channel<Array<Int>?>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    private val txEvents = Channel<TxEvent>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)

    private data class TxEvent(val type: StardustAppEventType, val xcvr: Int?)

    /** Starts sending this user's SOS from [location]. False when one is already under way. */
    fun send(location: Location): Boolean = start(
        write = { SOSUtils.writeSos(location) },
        record = { SOSUtils.recordSentSos(location) },
    )

    /**
     * [send] without the location, which a JVM test cannot build. [write] makes one attempt and
     * is false when nothing could be addressed; [record] files the SOS once transmitted.
     */
    internal fun start(write: () -> Boolean, record: suspend () -> Unit): Boolean = synchronized(lock) {
        if (_state.value != State.Idle) return false
        launch(write, record)
        true
    }

    /** Sends a [State.Refused] SOS again, from the same location. False when it is not refused. */
    fun retry(): Boolean = synchronized(lock) {
        val (write, record) = last ?: return false
        if (_state.value != State.Refused) return false
        launch(write, record)
        true
    }

    /**
     * Stops this SOS wherever it is. True when the radio holds nothing to cancel — nothing was
     * written, or the radio refused it; false when it may hold it and the caller must send the cancel.
     */
    fun cancel(): Boolean = synchronized(lock) {
        val current = _state.value
        val nothingOnRadio = (current is State.Pending && !written) || current == State.Refused
        stop()
        nothingOnRadio
    }

    /** The current SOS's write and record, kept so a refused one can be retried as it was. */
    private var last: Pair<() -> Boolean, suspend () -> Unit>? = null

    private fun launch(write: () -> Boolean, record: suspend () -> Unit) {
        last = write to record
        written = false
        drain()
        _state.value = State.Pending(Waiting.RADIO, attempts = 0)
        job = CoroutineScope(SupervisorJob() + dispatcher).launch { run(write, record) }
    }

    /** The radio ended this user's SOS (cancelled on the radio, or acknowledged). */
    internal fun onEnded() = synchronized(lock) {
        if (_state.value != State.Idle) log("own SOS ended by the radio", null)
        stop()
    }

    /** RECEIVE_SOS 0x85 from the local radio: its answer to the SOS written to it. */
    internal fun onRadioReply(data: Array<Int>?) {
        // The byte only: an unobserved refusal shows up in the log the first time it happens.
        log("radio SOS reply first byte=${data?.firstOrNull()}", null)
        replies.trySend(data)
    }

    /** A radio TX event; read only while this SOS waits on its transmission. */
    internal fun onTxEvent(type: StardustAppEventType?, xcvr: Int?) {
        if (type != StardustAppEventType.TXStart && type != StardustAppEventType.TXFinish &&
            type != StardustAppEventType.TxEnd) return
        val current = _state.value
        if (current is State.Pending || current == State.Accepted || current == State.AcceptedUnconfirmed) {
            txEvents.trySend(TxEvent(type, xcvr))
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        written = false
        last = null
        _state.value = State.Idle
    }

    private enum class Answer { ACCEPTED, REFUSED, NONE }

    private suspend fun run(write: () -> Boolean, record: suspend () -> Unit) {
        var attempts = 0
        while (true) {
            awaitSendable(attempts)
            attempts++
            when (attempt(write, attempts)) {
                Answer.ACCEPTED -> break
                Answer.REFUSED -> {
                    _state.value = State.Refused
                    log("SOS refused by the radio (Nack) after $attempts attempt(s)", null)
                    return
                }
                Answer.NONE -> delay(RETRY_DELAY_MS)
            }
        }
        _state.value = State.Accepted
        log("SOS accepted by the radio after $attempts attempt(s)", null)

        if (withTimeoutOrNull(TX_CONFIRM_MS) { awaitTransmission() } == null) {
            _state.value = State.AcceptedUnconfirmed
            log("SOS accepted, transmission not reported within ${TX_CONFIRM_MS}ms", null)
            awaitTransmission()
        }
        _state.value = State.Sent
        log("SOS transmitted", null)
        try {
            record()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The SOS is out; only its chat row is missing, and nothing depends on it to show the
            // SOS live.
            log("SOS transmitted but could not be recorded", e)
        }
    }

    /**
     * One write and the wait for its reply. A failure to write or to hear back is one more retry,
     * never the end: the operator would be left on "sending" with nothing trying.
     */
    private suspend fun attempt(write: () -> Boolean, attempt: Int): Answer {
        val reply = try {
            drain()
            // False: identity cleared since awaitSendable; the next round waits for it again.
            if (!write()) return Answer.NONE
            written = true
            _state.value = State.Pending(Waiting.RADIO_REPLY, attempt)
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { replies.receive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("SOS attempt $attempt failed to write; retrying", e)
            return Answer.NONE
        }
        return when (reply?.firstOrNull()) {
            ACCEPTED -> Answer.ACCEPTED
            REFUSED -> Answer.REFUSED
            else -> {
                // Neither Ack nor Nack (or nothing): not an answer, so it is asked again.
                log("SOS attempt $attempt unanswered (" +
                    (if (reply == null) "no reply" else "reply ${reply.firstOrNull()}") + "); retrying", null)
                Answer.NONE
            }
        }
    }

    /** Suspends until an SOS can be written, saying what it waits for meanwhile. */
    private suspend fun awaitSendable(attempts: Int) {
        sendable().first { waiting ->
            if (waiting != null) _state.value = State.Pending(waiting, attempts)
            waiting == null
        }
    }

    /**
     * The transmission that follows acceptance: the first TXStart names the transceiver, and its
     * TXFinish (or TxEnd) completes it. By transceiver, not by sosXCVR: the event numbers
     * transceivers by preset position, the configuration by delivery type.
     */
    private suspend fun awaitTransmission() {
        var xcvr: Int? = null
        while (true) {
            val event = txEvents.receive()
            when {
                event.type == StardustAppEventType.TXStart && xcvr == null -> xcvr = event.xcvr
                event.type != StardustAppEventType.TXStart && xcvr != null && event.xcvr == xcvr -> return
            }
        }
    }

    private fun drain() {
        while (replies.tryReceive().isSuccess) Unit
        while (txEvents.tryReceive().isSuccess) Unit
    }
}
