package com.commcrete.stardust.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogOnceTest {

    @Test
    fun aKeyIsFirstOnceUntilReset() {
        val once = LogOnce()

        assertTrue(once.first("drop"))
        assertFalse(once.first("drop"))
        assertTrue(once.first("other"))

        once.reset()
        assertTrue(once.first("drop"))
    }

    @Test
    fun resetReportsOnlyKeysThatRepeated() {
        val once = LogOnce()
        repeat(5) { once.first("drop") }
        once.first("single")

        assertEquals(mapOf("drop" to 4), once.reset())
        assertEquals(emptyMap<String, Int>(), once.reset())
    }

    @Test
    fun theMasterSwitchSkipsBuildingEvenErrorMessages() {
        var built = false
        StardustLog.enabled = false
        try {
            StardustLog.e("Test") { built = true; "never" }
            StardustLog.w(LogType.PTT, "Test") { built = true; "never" }
        } finally {
            StardustLog.enabled = true
        }

        assertFalse(built)
    }

    @Test
    fun loggingWithoutAndroidNeverThrows() {
        // A JVM test has no logcat: every call must fail inside the logger, not in the caller.
        StardustLog.e("Test", "no logcat here", IllegalStateException())
        StardustLog.w(LogType.PTT, "Test", "no logcat here")
        StardustLog.e("Test") { error("message failed") }
    }
}
