package com.commcrete.stardust.transport

import com.commcrete.stardust.util.StardustLog
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The host connection permit, as far as the JVM can reach it. Withdrawing the permit and every
 * refusal path go through BleDiag and the reconnect watchdog, which need Android — those are
 * verified on a device, not here.
 */
class ConnectionPermitTest {

    @Before
    fun quiet() {
        // StardustLog writes through android.util.Log, which JVM unit tests do not have.
        StardustLog.enabled = false
    }

    @After
    fun restore() {
        StardustLog.enabled = true
    }

    @Test
    fun `granted by default, so other hosts behave as before`() {
        assertTrue(ConnectionManager.isConnectionPermitted())
    }

    @Test
    fun `granting an already granted permit is a no-op`() {
        ConnectionManager.setConnectionPermitted(true)
        assertTrue(ConnectionManager.isConnectionPermitted())
    }
}
