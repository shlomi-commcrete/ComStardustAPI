package com.commcrete.stardust.util

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets a multi-package send (a long text, a file) move to its next package as soon as the radio
 * reports the previous one transmitted, instead of always waiting out the fixed interval.
 *
 * "TxEnd" here means either radio event that ends a transmission: TxEnd, or TXFinish (sent when
 * the transmission also emptied the transceiver's queue — the usual case, since a paced sender
 * hands over one package at a time). Each transmission is assumed to end with ONE of the two;
 * a firmware that sent both for the same package would release two tickets for it.
 *
 * Each package sent registers a [Ticket]. The radio's TxEnd event carries no packet id, only
 * the transceiver, so tickets are matched in FIFO order per carrier — the order the radio
 * transmits its queue. Whichever comes first, the TxEnd or the sender's interval timer,
 * claims the ticket; the other then does nothing, so a package is never sent twice.
 *
 * A ticket the timer claimed stays queued until its own TxEnd arrives (or it goes stale):
 * that late event belongs to the package already sent, and if the ticket were dropped it would
 * release the NEXT package early. A ticket is stale after [STALE_FACTOR] times its timeout, which
 * keeps the queue bounded on firmware that does not report TxEnd per package.
 */
internal object TxEndGate {

    private const val TAG = "TxEndGate"

    /** How many timeouts an unanswered ticket waits for its TxEnd before it is dropped. */
    private const val STALE_FACTOR = 3

    class Ticket internal constructor(
        /**
         * [Carrier.index] — the transceiver's position in the preset — the package went out on.
         * Only a TxEnd from this same transceiver answers the ticket.
         */
        internal val carrierIndex: Int,
        internal val expiresAtMs: Long,
        private val onTxEnd: () -> Unit,
    ) {
        private val claimed = AtomicBoolean(false)

        /**
         * Called by the sender when its interval ran out. True when the timer won and the sender
         * should send its next package; false when a TxEnd already claimed this ticket and is
         * sending it.
         */
        fun claimByTimeout(): Boolean = claimed.compareAndSet(false, true)

        internal fun fireTxEnd(): Boolean {
            if (!claimed.compareAndSet(false, true)) return false
            onTxEnd()
            return true
        }
    }

    private val tickets = ArrayDeque<Ticket>()

    /** Injected in tests. */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var log: (String) -> Unit = { Log.d(TAG, it) }

    /**
     * Registers the package just handed to the radio on [carrier]. [onTxEnd] runs — on the
     * thread that delivered the event — if its TxEnd arrives before the sender's [timeoutMs]
     * timer claims the ticket. It must cancel that timer before it sends anything.
     */
    fun register(carrier: Carrier, timeoutMs: Long, onTxEnd: () -> Unit): Ticket {
        val ticket = Ticket(carrier.index, clock() + timeoutMs * STALE_FACTOR, onTxEnd)
        synchronized(tickets) {
            dropStale()
            tickets.addLast(ticket)
        }
        return ticket
    }

    /**
     * The radio finished transmitting a package on transceiver [carrierIndex] — the event's
     * `xcvr`, the position in the preset. Consumes the oldest ticket on that transceiver only:
     * a TxEnd from another carrier says nothing about this send. An event that did not carry its
     * transceiver (null) cannot be attributed and is ignored; the senders' timers cover it.
     */
    fun onTxEnd(carrierIndex: Int?, event: String = "TxEnd") {
        if (carrierIndex == null) {
            log("$event without a transceiver ignored")
            return
        }
        val ticket = synchronized(tickets) {
            dropStale()
            val index = tickets.indexOfFirst { it.carrierIndex == carrierIndex }
            if (index < 0) null else tickets.removeAt(index)
        } ?: return
        if (ticket.fireTxEnd()) log("$event on carrier $carrierIndex released the next package")
    }

    /** The link is gone: nothing still queued will be answered. */
    fun clear() {
        synchronized(tickets) { tickets.clear() }
    }

    private fun dropStale() {
        val now = clock()
        tickets.removeAll { it.expiresAtMs <= now }
    }
}
