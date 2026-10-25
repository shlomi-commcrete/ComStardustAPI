package com.commcrete.stardust.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The log file keeps lines logcat drops, and keeps debug lines out unless the host asks.
 *
 * [StardustLog] is a process-wide object, so every test shares one directory and starts
 * from an empty one by deleting the files through the logger itself.
 */
@RunWith(AndroidJUnit4::class)
class StardustLogTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "stardust_log_test")

    @Before
    fun setUp() = runBlocking {
        StardustLog.start(dir)
        assertTrue(StardustLog.deleteFiles())
    }

    @After
    fun tearDown() {
        StardustLog.writeDebugToFile = false
    }

    private fun fileText(): String = runBlocking {
        StardustLog.logFiles().joinToString("") { it.readText() }
    }

    @Test
    fun debugLinesStayOutOfTheFileByDefault() {
        StardustLog.writeDebugToFile = false
        StardustLog.d("Test", "debug-line")
        StardustLog.i("Test", "info-line")

        val text = fileText()
        assertFalse(text.contains("debug-line"))
        assertTrue(text.contains("I/Test: info-line"))
    }

    @Test
    fun debugLinesReachTheFileWhenEnabled() {
        StardustLog.writeDebugToFile = true
        StardustLog.d("Test", "debug-line")

        assertTrue(fileText().contains("D/Test: debug-line"))
    }

    @Test
    fun warningsAndErrorsAlwaysReachTheFile() {
        StardustLog.w("Test", "warn-line")
        StardustLog.e("Test", "error-line", IllegalStateException("boom"))

        val text = fileText()
        assertTrue(text.contains("W/Test: warn-line"))
        assertTrue(text.contains("E/Test: error-line"))
        assertTrue(text.contains("IllegalStateException: boom"))
    }

    @Test
    fun deleteFilesRemovesEverythingAndLoggingResumes() = runBlocking {
        StardustLog.i("Test", "before-delete")
        assertTrue(StardustLog.logFiles().isNotEmpty())

        assertTrue(StardustLog.deleteFiles())
        assertTrue(StardustLog.logFiles().isEmpty())

        StardustLog.i("Test", "after-delete")
        val text = fileText()
        assertFalse(text.contains("before-delete"))
        assertTrue(text.contains("after-delete"))
    }

    @Test
    fun rotationKeepsAtMostFourFilesOfAboutOneMegabyte() = runBlocking {
        val line = "x".repeat(1_000)
        // ~6 MB through a 4 × 1 MB budget, in batches the queue can hold.
        repeat(60) {
            repeat(100) { StardustLog.i("Test", line) }
            StardustLog.logFiles()
        }

        val files = StardustLog.logFiles()
        assertEquals(4, files.size)
        assertEquals("stardust.log", files.last().name)
        files.forEach { assertTrue("${it.name} is ${it.length()} bytes", it.length() < 1_100_000) }
    }
}
