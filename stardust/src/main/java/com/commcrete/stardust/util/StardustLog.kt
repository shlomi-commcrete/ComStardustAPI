package com.commcrete.stardust.util

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * `android.util.Log` that also keeps the lines in rotating files, for the Logs export.
 *
 * Logcat alone loses them: its ring buffer is shared with every app on the device and
 * ATAK fills it within minutes, and a restart changes the pid the export filters by.
 *
 * Every call still reaches logcat. Debug lines reach the file only when [writeDebugToFile]
 * is set — the host sets it from its own build type, because this SDK ships as a release
 * AAR and its own `BuildConfig.DEBUG` is always false.
 *
 * The file is shipped output: operators export it. Never pass callsigns, coordinates,
 * IDs, message content or keys.
 */
object StardustLog {

    private const val TAG = "StardustLog"

    private const val CURRENT_FILE = "stardust.log"
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    /** The current file plus three rotated ones: [MAX_FILE_BYTES] each, 4 MB in all. */
    private const val MAX_FILES = 4

    /** Lines waiting for the writer. A burst past this is counted and reported in the file. */
    private const val QUEUE_CAPACITY = 2_000

    /** After the file fails, lines go to logcat only for this long before the file is tried again. */
    private const val RETRY_MS = 30_000L

    /** How long [logFiles] and [deleteFiles] wait for the writer to reach them. */
    private const val WRITER_TIMEOUT_MS = 2_000L

    /** False keeps `d()` lines out of the file; they still reach logcat. */
    @JvmStatic
    @Volatile
    var writeDebugToFile: Boolean = false

    private sealed interface Command {
        class Line(val text: String) : Command
        class Flush(val done: CompletableDeferred<Unit>) : Command
        class Delete(val done: CompletableDeferred<Boolean>) : Command
    }

    private val commands = Channel<Command>(QUEUE_CAPACITY)
    private val started = AtomicBoolean(false)
    private val dropped = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName(TAG))

    @Volatile
    private var logDir: File? = null

    // Touched only by the writer coroutine.
    private var out: OutputStream? = null
    private var outBytes = 0L
    private var retryAfterMs = 0L

    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * Starts writing to [dir]. Lines logged before this are queued, up to [QUEUE_CAPACITY],
     * and written once it runs.
     */
    fun start(dir: File) {
        logDir = dir
        if (started.compareAndSet(false, true)) scope.launch { writeLoop() }
    }

    /** Verbose lines are debug lines here: one level fewer to reason about. */
    @JvmStatic
    @JvmOverloads
    fun v(tag: String, msg: String, tr: Throwable? = null): Int = d(tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    fun d(tag: String, msg: String, tr: Throwable? = null): Int {
        if (writeDebugToFile) enqueue('D', tag, msg, tr)
        return Log.d(tag, msg, tr)
    }

    @JvmStatic
    @JvmOverloads
    fun i(tag: String, msg: String, tr: Throwable? = null): Int {
        enqueue('I', tag, msg, tr)
        return Log.i(tag, msg, tr)
    }

    @JvmStatic
    @JvmOverloads
    fun w(tag: String, msg: String, tr: Throwable? = null): Int {
        enqueue('W', tag, msg, tr)
        return Log.w(tag, msg, tr)
    }

    /** `Log.w(tag, tr)`: the throwable is the whole message. */
    @JvmStatic
    fun w(tag: String, tr: Throwable): Int = w(tag, "", tr)

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, msg: String, tr: Throwable? = null): Int {
        enqueue('E', tag, msg, tr)
        return Log.e(tag, msg, tr)
    }

    /**
     * The log files, oldest first, with every line queued before this call written.
     * Only what is on disk if the writer does not answer within [WRITER_TIMEOUT_MS].
     */
    suspend fun logFiles(): List<File> {
        if (started.get()) {
            val done = CompletableDeferred<Unit>()
            val flushed = withTimeoutOrNull(WRITER_TIMEOUT_MS) {
                commands.send(Command.Flush(done))
                done.await()
            }
            if (flushed == null) Log.w(TAG, "Log writer did not flush in time; exporting what is on disk")
        }
        val dir = logDir ?: return emptyList()
        return (MAX_FILES - 1 downTo 0).map { fileAt(dir, it) }.filter { it.exists() }
    }

    /** Deletes every log file — on logout. False if one is left or the writer did not answer. */
    suspend fun deleteFiles(): Boolean {
        if (!started.get()) return true
        val done = CompletableDeferred<Boolean>()
        return withTimeoutOrNull(WRITER_TIMEOUT_MS) {
            commands.send(Command.Delete(done))
            done.await()
        } ?: false.also { Log.w(TAG, "Log writer did not delete the log files in time") }
    }

    private fun enqueue(level: Char, tag: String, msg: String, tr: Throwable?) {
        if (commands.trySend(Command.Line(format(level, tag, msg, tr))).isFailure) {
            dropped.incrementAndGet()
        }
    }

    /** Formatted on the caller's thread so the time is when the line was logged, not written. */
    private fun format(level: Char, tag: String, msg: String, tr: Throwable?): String = buildString {
        append(timeFormat.format(Instant.now())).append(' ')
        append(Process.myPid()).append(' ').append(Process.myTid()).append(' ')
        append(level).append('/').append(tag).append(": ").append(msg).append('\n')
        if (tr != null) append(Log.getStackTraceString(tr)).append('\n')
    }

    private suspend fun writeLoop() {
        while (true) {
            handle(commands.receive())
            // Drain what queued meanwhile, then flush once per batch rather than per line.
            while (true) handle(commands.tryReceive().getOrNull() ?: break)
            flushStream()
        }
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Line -> write(command.text)
            is Command.Flush -> {
                flushStream()
                command.done.complete(Unit)
            }
            is Command.Delete -> {
                closeStream()
                val dir = logDir
                val allGone = dir == null ||
                    (0 until MAX_FILES).map { fileAt(dir, it) }.all { !it.exists() || it.delete() }
                if (!allGone) Log.w(TAG, "Some log files could not be deleted")
                dropped.set(0)
                command.done.complete(allGone)
            }
        }
    }

    private fun write(text: String) {
        val stream = openStream() ?: run {
            dropped.incrementAndGet()
            return
        }
        val lost = dropped.getAndSet(0)
        try {
            if (lost > 0) {
                writeBytes(stream, format('W', TAG, "$lost log lines not written: queue full or file unavailable", null))
            }
            writeBytes(stream, text)
            if (outBytes >= MAX_FILE_BYTES) rotate()
        } catch (e: Exception) {
            dropped.addAndGet(lost + 1)
            fileFailed(e)
        }
    }

    private fun writeBytes(stream: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        stream.write(bytes)
        outBytes += bytes.size
    }

    private fun openStream(): OutputStream? {
        out?.let { return it }
        val dir = logDir ?: return null
        if (System.currentTimeMillis() < retryAfterMs) return null
        return try {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the log directory")
            val file = fileAt(dir, 0)
            outBytes = file.length()
            BufferedOutputStream(FileOutputStream(file, true)).also { out = it }
        } catch (e: Exception) {
            fileFailed(e)
            null
        }
    }

    /** stardust.log, then stardust.1.log … stardust.3.log, oldest last. */
    private fun fileAt(dir: File, index: Int): File =
        if (index == 0) File(dir, CURRENT_FILE) else File(dir, "stardust.$index.log")

    private fun rotate() {
        closeStream()
        val dir = logDir ?: return
        for (index in MAX_FILES - 1 downTo 1) {
            val from = fileAt(dir, index - 1)
            val to = fileAt(dir, index)
            if (!from.exists()) continue
            to.delete()
            // A file that will not move would otherwise grow without bound.
            if (!from.renameTo(to)) {
                Log.w(TAG, "Log rotation could not rename ${from.name}; deleting it")
                from.delete()
            }
        }
    }

    private fun flushStream() {
        try {
            out?.flush()
        } catch (e: Exception) {
            fileFailed(e)
        }
    }

    /** Any failure of the file must not stop the writer: logcat still has every line. */
    private fun fileFailed(e: Exception) {
        Log.e(TAG, "Log file unavailable; logcat only for ${RETRY_MS / 1000}s", e)
        closeStream()
        retryAfterMs = System.currentTimeMillis() + RETRY_MS
    }

    private fun closeStream() {
        try {
            out?.close()
        } catch (e: IOException) {
            // The stream is being discarded; its failure is already reported or the next open reports it.
        }
        out = null
        outBytes = 0L
    }
}
