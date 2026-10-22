package com.commcrete.stardust.stardust

import com.commcrete.stardust.stardust.StardustInitConnectionHandler.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A missing licence or preset completes the handshake and leaves the radio usable (published as
 * ConnectionState.Ready with a warning); only an encryption-key failure blocks communication.
 */
class ConnectedOutcomeTest {

    @Test
    fun `licence and preset outcomes are connected`() {
        assertTrue(StardustInitConnectionHandler.isConnectedOutcome(State.SUCCESS))
        assertTrue(StardustInitConnectionHandler.isConnectedOutcome(State.NO_LICENSE))
        assertTrue(StardustInitConnectionHandler.isConnectedOutcome(State.PRESET_ERROR))
    }

    @Test
    fun `encryption key failure and unfinished handshakes are not connected`() {
        State.entries
            .filter { it != State.SUCCESS && it != State.NO_LICENSE && it != State.PRESET_ERROR }
            .forEach { assertFalse(it.name, StardustInitConnectionHandler.isConnectedOutcome(it)) }
    }
}
