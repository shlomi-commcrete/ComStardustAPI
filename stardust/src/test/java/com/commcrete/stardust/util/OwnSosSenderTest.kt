package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.StardustAppEventPackage.StardustAppEventType.TXFinish
import com.commcrete.stardust.stardust.model.StardustAppEventPackage.StardustAppEventType.TXStart
import com.commcrete.stardust.util.OwnSosSender.State
import com.commcrete.stardust.util.OwnSosSender.Waiting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An SOS reads "sent" only once the radio reported transmitting it, is retried until the radio
 * accepts it, and is never written again after that. The radio is faked: [ready] stands for the
 * link and the local identity, [writes] counts what reached it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnSosSenderTest {

    private val ready = MutableStateFlow<Waiting?>(null)
    private var writes = 0
    private var writable = true
    private var records = 0

    @Before
    fun setUp() {
        OwnSosSender.cancel()
        OwnSosSender.sendable = { ready }
        OwnSosSender.log = { _, _ -> }
    }

    @After
    fun tearDown() {
        OwnSosSender.cancel()
    }

    /**
     * runTest advances virtual time until idle when the body ends, and a pending SOS retries
     * forever — so every test stops it before that, not in @After.
     */
    private fun sosTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            OwnSosSender.cancel()
        }
    }

    private fun TestScope.start(): Boolean {
        OwnSosSender.dispatcher = StandardTestDispatcher(testScheduler)
        return OwnSosSender.start(
            write = { if (writable) { writes++; true } else false },
            record = { records++ },
        )
    }

    private val accepted = arrayOf(OwnSosSender.ACCEPTED, 0, 0, 0)

    private fun transmit(xcvr: Int = 1) {
        OwnSosSender.onTxEvent(TXStart, xcvr)
        OwnSosSender.onTxEvent(TXFinish, xcvr)
    }

    @Test
    fun `accepted and transmitted reads Sent, after one write, and is recorded once`() = sosTest {
        start(); runCurrent()
        assertEquals(State.Pending(Waiting.RADIO_REPLY, 1), OwnSosSender.state.value)

        OwnSosSender.onRadioReply(accepted); runCurrent()
        assertEquals(State.Accepted, OwnSosSender.state.value)

        transmit(); runCurrent()
        assertEquals(State.Sent, OwnSosSender.state.value)
        assertEquals(1, writes)
        assertEquals(1, records)
    }

    @Test
    fun `accepted alone is not Sent`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(accepted); runCurrent()

        OwnSosSender.onTxEvent(TXStart, 1); runCurrent()

        assertEquals(State.Accepted, OwnSosSender.state.value)
        assertEquals(0, records)
    }

    @Test
    fun `no reply is retried until the radio accepts`() = sosTest {
        start(); runCurrent()
        advanceTimeBy(OwnSosSender.REPLY_TIMEOUT_MS + OwnSosSender.RETRY_DELAY_MS + 1)
        assertEquals(2, writes)

        OwnSosSender.onRadioReply(accepted); runCurrent()
        assertEquals(State.Accepted, OwnSosSender.state.value)
    }

    @Test
    fun `a reply other than accepted is retried`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(arrayOf(0, 0, 0, 0)); runCurrent()
        assertTrue(OwnSosSender.state.value is State.Pending)

        advanceTimeBy(OwnSosSender.RETRY_DELAY_MS + 1)
        assertEquals(2, writes)
    }

    @Test
    fun `nothing is written while there is no radio, and it goes once there is`() = sosTest {
        ready.value = Waiting.RADIO
        start(); runCurrent()
        assertEquals(State.Pending(Waiting.RADIO, 0), OwnSosSender.state.value)
        assertEquals(0, writes)

        ready.value = null; runCurrent()
        assertEquals(1, writes)
    }

    @Test
    fun `an accepted SOS is never written again, even when its transmission is not reported`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(accepted); runCurrent()

        advanceTimeBy(OwnSosSender.TX_CONFIRM_MS + 1)
        assertEquals(State.AcceptedUnconfirmed, OwnSosSender.state.value)

        advanceTimeBy(10 * OwnSosSender.TX_CONFIRM_MS)
        assertEquals(1, writes)

        transmit(); runCurrent()
        assertEquals(State.Sent, OwnSosSender.state.value)
    }

    @Test
    fun `a finish on another transceiver does not count`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(accepted); runCurrent()

        OwnSosSender.onTxEvent(TXStart, 1)
        OwnSosSender.onTxEvent(TXFinish, 2); runCurrent()

        assertEquals(State.Accepted, OwnSosSender.state.value)
    }

    @Test
    fun `cancel before anything was written leaves nothing to cancel on the radio`() = sosTest {
        writable = false
        start(); runCurrent()

        assertTrue(OwnSosSender.cancel())
        assertEquals(State.Idle, OwnSosSender.state.value)
    }

    @Test
    fun `cancel after a write tells the caller to cancel on the radio, and stops retrying`() = sosTest {
        start(); runCurrent()

        assertFalse(OwnSosSender.cancel())
        advanceTimeBy(10 * OwnSosSender.RETRY_DELAY_MS)
        assertEquals(1, writes)
        assertEquals(State.Idle, OwnSosSender.state.value)
    }

    @Test
    fun `the radio ending the SOS returns to Idle`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(accepted); runCurrent()
        transmit(); runCurrent()

        OwnSosSender.onEnded()

        assertEquals(State.Idle, OwnSosSender.state.value)
    }

    private val nack = arrayOf(OwnSosSender.REFUSED, 0, 0, 0)

    @Test
    fun `a Nack stops in Refused and is not retried on its own`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(nack); runCurrent()
        assertEquals(State.Refused, OwnSosSender.state.value)

        advanceTimeBy(10 * OwnSosSender.RETRY_DELAY_MS)
        assertEquals(1, writes)
    }

    @Test
    fun `a refused SOS can be retried, and goes out again`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(nack); runCurrent()

        assertTrue(OwnSosSender.retry()); runCurrent()
        assertEquals(2, writes)
        OwnSosSender.onRadioReply(accepted); runCurrent()
        assertEquals(State.Accepted, OwnSosSender.state.value)
    }

    @Test
    fun `cancelling a refused SOS leaves nothing to cancel on the radio`() = sosTest {
        start(); runCurrent()
        OwnSosSender.onRadioReply(nack); runCurrent()

        assertTrue(OwnSosSender.cancel())
        assertFalse(OwnSosSender.retry())
    }

    @Test
    fun `a second trigger while one is under way is refused`() = sosTest {
        assertTrue(start()); runCurrent()
        assertFalse(start())
        assertEquals(1, writes.also { runCurrent() })
    }
}
