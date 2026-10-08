package com.commcrete.stardust.stardust

import android.util.Log
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.util.FileReceiver
import com.commcrete.stardust.util.TextPartPacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Joins the parts of a long incoming text back into one message.
 *
 * A sender splits a long text into several SEND_MESSAGE packages; every part but the last goes
 * out with LAST unset. Parts are joined when they come from the same sender, in the same chat
 * (so a private and a group message sent side by side never mix), over the same carrier.
 *
 * The first part opens a RECEIVING row; every further part is appended to that same row, which
 * stays RECEIVING throughout. It is settled once: RECEIVED when a part with LAST arrives, or
 * FAILED — keeping the parts that did arrive — when no further part has arrived for
 * [continuationWindowMs] (restarted on every part, see [TextPartPacing.continuationWindowMs]) or
 * the link is lost. LAST is the only completeness signal the wire carries — a part does not say
 * how many there are — so a text that never saw it cannot be called whole. A text that arrives
 * whole (LAST on the first part) never opens a row and is saved RECEIVED directly, as it always
 * was. The startup sweep applies the same rule to a row a dead process left open.
 *
 * Every event goes through one channel with one consumer, so parts are applied in the order they
 * were received, and a timeout can never interleave with the part that would have cancelled it.
 */
internal class IncomingTextAssembler(
    private val scope: CoroutineScope,
    private val sink: Sink,
    /** Longest silence after a part before the message is settled, by the carrier it arrived on. */
    private val continuationWindowMs: (StardustAPIPackage) -> Long =
        { TextPartPacing.continuationWindowMs(it.carrier) },
    /** Injected so the class runs in plain JVM tests, where android.util.Log is not available. */
    private val log: (message: String, error: Throwable?) -> Unit = ::logToLogcat,
) {

    /** What makes two parts belong to the same message. */
    data class Key(
        val senderId: String,
        val chatId: String,
        val groupId: String?,
        val deliveryType: Int,
    )

    /** Where the assembled message goes. Every call is made from the single consumer, in order. */
    interface Sink {
        /** Inserts the RECEIVING row for a message whose first part just arrived; returns its id. */
        suspend fun open(pkg: StardustAPIPackage, text: String): Long?

        /** Writes the text joined so far onto the RECEIVING row. */
        suspend fun append(messageId: Long, text: String)

        /**
         * The message is whole: settle row [messageId] RECEIVED with [text], or — when there is
         * no row ([messageId] null: a single-part text, or the insert failed) — save it RECEIVED.
         */
        suspend fun complete(messageId: Long?, pkg: StardustAPIPackage, text: String)

        /**
         * The message ended without its LAST part: settle row [messageId] FAILED for [failure]
         * with the [text] that arrived, or — when there is no row (the insert failed) — save it
         * FAILED. [failure] is MISSING for the gap timeout, DISCONNECTED for a lost link.
         */
        suspend fun abandon(
            messageId: Long?,
            pkg: StardustAPIPackage,
            text: String,
            failure: FileReceiver.FileFailure,
        )
    }

    private sealed class Event {
        class Part(val key: Key, val pkg: StardustAPIPackage, val text: String, val isLast: Boolean) : Event()
        class Timeout(val key: Key, val generation: Int) : Event()
        object SettleAll : Event()
    }

    private class Pending(
        val messageId: Long?,
        val pkg: StardustAPIPackage,
        val text: StringBuilder,
        var generation: Int = 0,
        var timer: Job? = null,
    )

    private val events = Channel<Event>(Channel.UNLIMITED)
    /** Insertion-ordered so [settleAll] completes the oldest message first. */
    private val pending = LinkedHashMap<Key, Pending>()

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Failed to apply incoming text event", e)
                }
            }
        }
    }

    /** Queues one received part. Call in receive order; never blocks. */
    fun onPart(key: Key, pkg: StardustAPIPackage, text: String, isLast: Boolean) {
        events.trySend(Event.Part(key, pkg, text, isLast))
    }

    /**
     * Ends every message still open, as failed with what has arrived. For a lost link: no
     * further part can join any of them, which is the same reason the window ends one.
     */
    fun settleAll() {
        events.trySend(Event.SettleAll)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Part -> onPartEvent(event)
            is Event.Timeout -> {
                val open = pending[event.key] ?: return
                if (open.generation != event.generation) return
                pending.remove(event.key)
                log("text ended without LAST after ${continuationWindowMs(open.pkg)}ms", null)
                sink.abandon(open.messageId, open.pkg, open.text.toString(), FileReceiver.FileFailure.MISSING)
            }
            Event.SettleAll -> {
                if (pending.isEmpty()) return
                log("settling ${pending.size} incoming text(s) still open", null)
                val all = pending.values.toList()
                pending.clear()
                all.forEach {
                    it.timer?.cancel()
                    sink.abandon(it.messageId, it.pkg, it.text.toString(), FileReceiver.FileFailure.DISCONNECTED)
                }
            }
        }
    }

    private suspend fun onPartEvent(part: Event.Part) {
        val open = pending[part.key]
        if (open == null) {
            if (part.isLast) {
                sink.complete(null, part.pkg, part.text)
                return
            }
            val id = sink.open(part.pkg, part.text)
            val started = Pending(id, part.pkg, StringBuilder(part.text))
            pending[part.key] = started
            arm(part.key, started)
            return
        }

        open.timer?.cancel()
        open.text.append(part.text)
        if (part.isLast) {
            pending.remove(part.key)
            sink.complete(open.messageId, open.pkg, open.text.toString())
        } else {
            open.messageId?.let { sink.append(it, open.text.toString()) }
            arm(part.key, open)
        }
    }

    private fun arm(key: Key, open: Pending) {
        val generation = ++open.generation
        open.timer = scope.launch {
            delay(continuationWindowMs(open.pkg))
            events.send(Event.Timeout(key, generation))
        }
    }

    companion object {
        private const val TAG = "IncomingTextAssembler"

        private fun logToLogcat(message: String, error: Throwable?) {
            if (error != null) Log.e(TAG, message, error) else Log.d(TAG, message)
        }
    }
}
