package com.commcrete.stardust.util

import android.util.Log
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
 * The log file keeps lines logcat drops; each type logs only what its switch allows, and
 * errors always. Files rotate hourly, age out after 7 days, and the logger never throws.
 *
 * [StardustLog] is a process-wide object, so every test shares one directory and starts
 * from an empty one by deleting the files through the logger itself. Time is a fake clock
 * that each test moves past the flood window, so lines from one test never throttle the next.
 */
@RunWith(AndroidJUnit4::class)
class StardustLogTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "stardust_log_test")
    private val defaultSwitches = StardustLog.switches
    private var now = System.currentTimeMillis()

    @Before
    fun setUp() = runBlocking {
        now += 60 * 60 * 1000L
        StardustLog.clock = { now }
        StardustLog.start(dir)
        assertTrue(StardustLog.deleteFiles())
    }

    @After
    fun tearDown() {
        StardustLog.enabled = true
        StardustLog.output = LogOutput.BOTH
        StardustLog.isDebugBuild = false
        StardustLog.switches = defaultSwitches
        StardustLog.clock = System::currentTimeMillis
    }

    private fun fileText(): String = runBlocking {
        StardustLog.logFiles().joinToString("") { it.readText() }
    }

    private fun switchPtt(release: Int, debug: Int) {
        StardustLog.switches = defaultSwitches + (LogType.PTT to StardustLog.Switch(release, debug))
    }

    @Test
    fun byDefaultInfoAndAboveAreLogged() {
        for (debugBuild in listOf(false, true)) {
            StardustLog.isDebugBuild = debugBuild
            StardustLog.d(LogType.PTT, "Test", "debug-line-$debugBuild")
            StardustLog.i(LogType.PTT, "Test", "info-line-$debugBuild")
            StardustLog.w(LogType.PTT, "Test", "warn-line-$debugBuild")
        }

        val text = fileText()
        assertFalse(text.contains("debug-line"))
        assertTrue(text.contains("I/PTT/Test: info-line-false"))
        assertTrue(text.contains("I/PTT/Test: info-line-true"))
        assertTrue(text.contains("W/PTT/Test: warn-line-true"))
    }

    @Test
    fun aDebugSwitchLogsDebugLinesOfThatTypeOnly() {
        StardustLog.isDebugBuild = true
        switchPtt(release = Log.INFO, debug = Log.DEBUG)
        StardustLog.d(LogType.PTT, "Test", "ptt-debug")
        StardustLog.d(LogType.FILES, "Test", "files-debug")

        val text = fileText()
        assertTrue(text.contains("D/PTT/Test: ptt-debug"))
        assertFalse(text.contains("files-debug"))
    }

    @Test
    fun aReleaseBuildNeverLogsDebugLines() {
        StardustLog.isDebugBuild = false
        switchPtt(release = Log.DEBUG, debug = Log.DEBUG)
        StardustLog.d(LogType.PTT, "Test", "ptt-debug")
        StardustLog.i(LogType.PTT, "Test", "ptt-info")

        val text = fileText()
        assertFalse(text.contains("ptt-debug"))
        assertTrue(text.contains("I/PTT/Test: ptt-info"))
    }

    @Test
    fun errorsAreLoggedEvenWhenTheirAreaIsSwitchedOff() {
        switchPtt(release = Log.ERROR, debug = Log.ERROR)
        StardustLog.w(LogType.PTT, "Test", "warn-line")
        StardustLog.e("Test", "error-line", IllegalStateException("boom"))

        val text = fileText()
        assertFalse(text.contains("warn-line"))
        assertTrue(text.contains("E/Test: error-line"))
        assertTrue(text.contains("IllegalStateException: boom"))
    }

    @Test
    fun theMasterSwitchTurnsOffEveryLineErrorsIncluded() {
        StardustLog.enabled = false
        var built = false
        StardustLog.w(LogType.PTT, "Test", "warn-while-off")
        StardustLog.e("Test", "error-while-off")
        StardustLog.e("Test") { built = true; "lazy-while-off" }
        StardustLog.enabled = true
        StardustLog.e("Test", "error-after-on")

        val text = fileText()
        assertFalse(built)
        assertFalse(text.contains("while-off"))
        assertTrue(text.contains("error-after-on"))
    }

    @Test
    fun logcatOutputKeepsLinesOutOfTheFile() {
        StardustLog.output = LogOutput.LOGCAT
        StardustLog.e("Test", "logcat-only")
        StardustLog.output = LogOutput.FILE
        StardustLog.e("Test", "file-only")

        val text = fileText()
        assertFalse(text.contains("logcat-only"))
        assertTrue(text.contains("E/Test: file-only"))
    }

    @Test
    fun aLazyMessageIsNotBuiltWhenTheLineIsOff() {
        var built = false
        StardustLog.d(LogType.PTT, "Test") { built = true; "never" }

        assertFalse(built)
    }

    @Test
    fun aMessageThatThrowsIsDroppedWithoutReachingTheCaller() {
        StardustLog.e("Test") { throw IllegalStateException("bad message") }
        StardustLog.e("Test", "next-line")

        assertTrue(fileText().contains("next-line"))
    }

    @Test
    fun aFloodingTagIsHeldBackAndSummarised() {
        repeat(80) { StardustLog.w(LogType.PTT, "Flood", "flood-line-$it") }
        now += 11_000
        StardustLog.w(LogType.PTT, "Flood", "after-window")

        val text = fileText()
        assertTrue(text.contains("flood-line-49"))
        assertFalse(text.contains("flood-line-50"))
        assertTrue(text.contains("30 lines from this tag held back"))
        assertTrue(text.contains("after-window"))
    }

    @Test
    fun deleteFilesRemovesEverythingAndLoggingResumes() = runBlocking {
        StardustLog.w(LogType.GENERAL, "Test", "before-delete")
        assertTrue(StardustLog.logFiles().isNotEmpty())

        assertTrue(StardustLog.deleteFiles())
        assertTrue(StardustLog.logFiles().isEmpty())

        StardustLog.w(LogType.GENERAL, "Test", "after-delete")
        val text = fileText()
        assertFalse(text.contains("before-delete"))
        assertTrue(text.contains("after-delete"))
    }

    @Test
    fun filesOlderThanSevenDaysAreDeleted() = runBlocking {
        StardustLog.e("Test", "eight-days-ago")
        StardustLog.logFiles()

        now += 8 * 24 * 60 * 60 * 1000L
        // The first line after the hour closes the old file and purges; the next opens a new one.
        StardustLog.e("Test", "rotate")
        StardustLog.e("Test", "today")

        val text = fileText()
        assertFalse(text.contains("eight-days-ago"))
        assertTrue(text.contains("today"))
    }

    @Test
    fun sizeStaysWithinTheBudget() = runBlocking {
        val line = "x".repeat(1_000)
        // ~6 MB through a 4 MB budget; each batch fits the flood guard and the queue.
        repeat(120) {
            repeat(50) { StardustLog.e("Size", line) }
            StardustLog.logFiles()
            now += 11_000
        }

        val files = StardustLog.logFiles()
        val total = files.sumOf { it.length() }
        // The budget is applied when a file closes, so the open file can add up to one more.
        assertTrue("total $total bytes", total <= 5L * 1024 * 1024)
        files.forEach { assertTrue("${it.name} is ${it.length()} bytes", it.length() < 1_100_000) }
        assertEquals(files, files.sortedBy { it.lastModified() })
    }
}
