package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.config.CarrierType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A ticket registered with releasedByFinish = false (file sends) moves on TxEnd only. */
class TxEndGateFinishTest {

    private val released = mutableListOf<String>()
    private val hr = Carrier(0, CarrierType.HR)

    @Before
    fun setUp() {
        TxEndGate.clear()
        TxEndGate.clock = { 0L }
        TxEndGate.log = {}
    }

    @After
    fun tearDown() {
        TxEndGate.clear()
        TxEndGate.clock = System::currentTimeMillis
    }

    private fun registerFile(name: String) =
        TxEndGate.register(hr, 1_000, releasedByFinish = false) { released += name }

    @Test
    fun `TxEnd releases a file package`() {
        registerFile("a")
        TxEndGate.onTxEnd(hr.index, event = "TxEnd")
        assertEquals(listOf("a"), released)
    }

    @Test
    fun `TXFinish does not release a file package and leaves its timer free to`() {
        val ticket = registerFile("a")
        TxEndGate.onTxEnd(hr.index, event = "TXFinish")
        assertTrue(released.isEmpty())
        assertTrue("the timer must still be able to claim it", ticket.claimByTimeout())
    }

    @Test
    fun `TXFinish retires the file ticket, so the next TxEnd belongs to the next package`() {
        registerFile("first")
        registerFile("second")
        TxEndGate.onTxEnd(hr.index, event = "TXFinish")
        TxEndGate.onTxEnd(hr.index, event = "TxEnd")
        assertEquals(listOf("second"), released)
    }

    @Test
    fun `a default ticket is still released by TXFinish`() {
        TxEndGate.register(hr, 1_000) { released += "text" }
        TxEndGate.onTxEnd(hr.index, event = "TXFinish")
        assertEquals(listOf("text"), released)
    }
}
