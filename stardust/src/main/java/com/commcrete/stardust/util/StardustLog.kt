package com.commcrete.stardust.util

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a log line is about. Each type is switched on and off by level in [StardustLog]'s
 * table, so one area — PTT, say — can be traced without the noise of every other.
 */
internal enum class LogType {
    PTT,
    BLE,
    USB,
    /** Link state across transports: connect, disconnect, port mode, wake locks, keepalive. */
    CONNECTION,
    /** Radio packets and events in and out: framing, reassembly, dispatch, TX/RX events. */
    PACKETS,
    /** Reading and writing the radio's configuration, presets and leases. */
    CONFIG,
    TEXT,
    LOCATION,
    SOS,
    FILES,
    /** The database, contacts and stored preferences. */
    STORAGE,
    GENERAL,
}

/**
 * Lets a line inside a loop — per frame, per packet, per poll — log once per run instead of
 * once per pass. The loop's owner holds one and calls [reset] when a new run starts (a PTT
 * session, a file transfer, a link); [reset] returns how often each key repeated, so the
 * owner can log one summary line.
 *
 * ```
 * if (logOnce.first("no-owner")) StardustLog.w(LogType.PTT, TAG, "ptt frame dropped: no owner")
 * ```
 */
internal class LogOnce {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** True the first time [key] is seen since the last [reset]; counts every call. */
    fun first(key: String): Boolean =
        counts.getOrPut(key) { AtomicInteger() }.incrementAndGet() == 1

    /** Forgets every key. Returns how many times each key repeated after its first line, omitting keys that did not. */
    fun reset(): Map<String, Int> {
        val repeats = counts.mapValues { it.value.get() - 1 }.filterValues { it > 0 }
        counts.clear()
        return repeats
    }
}

/**
 * `android.util.Log` that also keeps the lines in files, for the Logs export.
 *
 * Logcat alone loses them: its ring buffer is shared with every app on the device and
 * ATAK fills it within minutes, and a restart changes the pid the export filters by.
 *
 * Errors ([e]) are always logged. Every other line carries a [LogType] and is logged — to
 * logcat and the file alike — only when that type's switch reaches its level. Which switch
 * applies follows [isDebugBuild]: the host sets it from its own build type, because this SDK
 * ships as a release AAR and its own `BuildConfig.DEBUG` is always false.
 *
 * Logging never throws and never blocks: a failure inside the logger drops the line and is
 * counted. A line is only formatted on the writer's thread, and the lambda overloads build
 * the message only when the line is enabled — use them on any per-frame or per-packet path.
 * Past [FLOOD_LINES] `i`/`w`/`e` lines from one tag within [FLOOD_WINDOW_MS], further lines
 * are counted and summarised instead of written.
 *
 * Files: one per hour and per process start, kept at most [MAX_AGE_MS] (7 days) and
 * [MAX_TOTAL_BYTES] in all, oldest deleted first.
 *
 * The file is shipped output: operators export it. Callsigns, coordinates, IDs, message
 * content, keys and raw packets go only in `d` lines, which a release build never logs.
 */
object StardustLog {

    private const val TAG = "StardustLog"

    /** The lowest level a [LogType] logs at, in a release and in a debug build. */
    internal class Switch(val release: Int, val debug: Int)

    private val DEFAULT_SWITCH = Switch(release = Log.INFO, debug = Log.INFO)

    /**
     * The switches. Static for now: change a line and rebuild. Levels are `Log.DEBUG`,
     * `Log.INFO` or `Log.WARN`; `Log.ERROR` leaves only errors, which are never switched off.
     * A release level below `Log.INFO` is raised to it — see [isEnabled].
     *
     * INFO is the timeline: connects, link loss, handshake steps. DEBUG is development trace.
     * To trace PTT in a debug build: `LogType.PTT to Switch(release = Log.INFO, debug = Log.DEBUG)`.
     */
    internal var switches: Map<LogType, Switch> = mapOf(
        LogType.PTT to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.BLE to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.USB to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.CONNECTION to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.PACKETS to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.CONFIG to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.TEXT to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.LOCATION to Switch(release = Log.INFO, debug = Log.INFO),
        // Release logs at INFO (the clamp in isEnabled), so the SOS send progress is in field logs.
        LogType.SOS to Switch(release = Log.DEBUG, debug = Log.DEBUG),
        LogType.FILES to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.STORAGE to Switch(release = Log.INFO, debug = Log.INFO),
        LogType.GENERAL to Switch(release = Log.INFO, debug = Log.INFO),
    )
        set(value) {
            field = value
            releaseLevels = levelsOf(value) { maxOf(it.release, Log.INFO) }
            debugLevels = levelsOf(value) { it.debug }
        }

    // Resolved once per table so a log call is an array read, not a map lookup. In memory only.
    @Volatile private var releaseLevels = levelsOf(switches) { maxOf(it.release, Log.INFO) }
    @Volatile private var debugLevels = levelsOf(switches) { it.debug }

    private fun levelsOf(table: Map<LogType, Switch>, pick: (Switch) -> Int) =
        IntArray(LogType.entries.size) { pick(table[LogType.entries[it]] ?: DEFAULT_SWITCH) }

    /**
     * True when the host is a debug build: the debug column of the switches applies. False
     * by default, so a host that never sets it logs as a release build.
     */
    @JvmStatic
    @Volatile
    var isDebugBuild: Boolean = false

    private const val FILE_PREFIX = "stardust"
    private const val FILE_SUFFIX = ".log"
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    /** A file is closed after this long even if not full, so old lines age out with whole files. */
    private const val MAX_FILE_SPAN_MS = 60L * 60 * 1000
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
    /** One file an hour for 7 days is 168; restarts add more. Past this, the oldest go. */
    private const val MAX_FILE_COUNT = 256

    /** Lines waiting for the writer. A burst past this is counted and reported in the file. */
    private const val QUEUE_CAPACITY = 2_000

    /** After the file fails, lines go to logcat only for this long before the file is tried again. */
    private const val RETRY_MS = 30_000L

    /** How long [logFiles] and [deleteFiles] wait for the writer to reach them. */
    private const val WRITER_TIMEOUT_MS = 2_000L

    /** Flood guard: at most this many `i`/`w`/`e` lines per tag per window; `d` is opt-in and exempt. */
    private const val FLOOD_LINES = 50
    private const val FLOOD_WINDOW_MS = 10_000L
    private const val MAX_FLOOD_TAGS = 512

    /** The wall clock. A seam for tests of rotation and age. */
    internal var clock: () -> Long = System::currentTimeMillis

    private class Line(
        val timeMs: Long,
        val pid: Int,
        val tid: Int,
        val level: Char,
        val type: LogType?,
        val tag: String,
        val msg: String,
        val tr: Throwable?,
    )

    private sealed interface Command {
        class Write(val line: Line) : Command
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
    private var outStartedMs = 0L
    private var retryAfterMs = 0L

    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    private class FloodWindow(var startMs: Long, var lines: Int, var suppressed: Int)

    private val floods = ConcurrentHashMap<String, FloodWindow>()

    /**
     * Starts writing to [dir]. Lines logged before this are queued, up to [QUEUE_CAPACITY],
     * and written once it runs. Each start writes to a new file.
     */
    fun start(dir: File) {
        logDir = dir
        if (started.compareAndSet(false, true)) scope.launch { writeLoop() }
    }

    /**
     * Whether a [type] line at [priority] is logged. A release build never logs below
     * `Log.INFO`, whatever the table says: debug lines may carry identities (see the class doc).
     */
    internal fun isEnabled(type: LogType, priority: Int): Boolean {
        val levels = if (isDebugBuild) debugLevels else releaseLevels
        return priority >= levels[type.ordinal]
    }

    /** Debug: development trace. The only level that may carry identities or payloads. */
    @JvmStatic
    @JvmOverloads
    @JvmName("d")
    internal fun d(type: LogType, tag: String, msg: String, tr: Throwable? = null): Int =
        if (isEnabled(type, Log.DEBUG)) emit(Log.DEBUG, type, tag, msg, tr) else 0

    @JvmStatic
    @JvmOverloads
    @JvmName("i")
    internal fun i(type: LogType, tag: String, msg: String, tr: Throwable? = null): Int =
        if (isEnabled(type, Log.INFO)) emit(Log.INFO, type, tag, msg, tr) else 0

    @JvmStatic
    @JvmOverloads
    @JvmName("w")
    internal fun w(type: LogType, tag: String, msg: String, tr: Throwable? = null): Int =
        if (isEnabled(type, Log.WARN)) emit(Log.WARN, type, tag, msg, tr) else 0

    /** `Log.w(tag, tr)`: the throwable is the whole message. */
    @JvmStatic
    @JvmName("w")
    internal fun w(type: LogType, tag: String, tr: Throwable): Int = w(type, tag, "", tr)

    /** Always logged, to logcat and the file: an error is never switched off. */
    @JvmStatic
    @JvmOverloads
    fun e(tag: String, msg: String, tr: Throwable? = null): Int = emit(Log.ERROR, null, tag, msg, tr)

    // Lambda forms: the message is built only when the line is enabled. A message that
    // throws while being built is dropped like any other logger failure.

    internal inline fun d(type: LogType, tag: String, tr: Throwable? = null, msg: () -> String): Int =
        if (isEnabled(type, Log.DEBUG)) emitLazy(Log.DEBUG, type, tag, tr, msg) else 0

    internal inline fun i(type: LogType, tag: String, tr: Throwable? = null, msg: () -> String): Int =
        if (isEnabled(type, Log.INFO)) emitLazy(Log.INFO, type, tag, tr, msg) else 0

    internal inline fun w(type: LogType, tag: String, tr: Throwable? = null, msg: () -> String): Int =
        if (isEnabled(type, Log.WARN)) emitLazy(Log.WARN, type, tag, tr, msg) else 0

    internal inline fun e(tag: String, tr: Throwable? = null, msg: () -> String): Int =
        emitLazy(Log.ERROR, null, tag, tr, msg)

    internal inline fun emitLazy(priority: Int, type: LogType?, tag: String, tr: Throwable?, msg: () -> String): Int {
        val text = try {
            msg()
        } catch (t: Throwable) {
            // A log message must never take the caller down; the line is lost and counted.
            dropped.incrementAndGet()
            return 0
        }
        return emit(priority, type, tag, text, tr)
    }

    /** Logcat plus the file queue. Never throws: whatever fails, the caller carries on. */
    internal fun emit(priority: Int, type: LogType?, tag: String, msg: String, tr: Throwable?): Int {
        return try {
            if (priority > Log.DEBUG && !admit(tag, priority)) return 0
            enqueue(priority, type, tag, msg, tr)
            when (priority) {
                Log.DEBUG -> Log.d(tag, msg, tr)
                Log.INFO -> Log.i(tag, msg, tr)
                Log.WARN -> Log.w(tag, msg, tr)
                else -> Log.e(tag, msg, tr)
            }
        } catch (t: Throwable) {
            // Logcat or the queue failed (a JVM test without Android, a closed channel): drop the line.
            dropped.incrementAndGet()
            0
        }
    }

    /**
     * The flood guard: false once [tag] has logged [FLOOD_LINES] lines in this window. The first
     * line of the next window reports how many were held back.
     */
    private fun admit(tag: String, priority: Int): Boolean {
        val now = clock()
        // Tags built per call would grow this without bound; forgetting every window costs one burst at most.
        if (floods.size > MAX_FLOOD_TAGS) floods.clear()
        val window = floods.getOrPut(tag) { FloodWindow(now, 0, 0) }
        val suppressed: Int
        synchronized(window) {
            if (now - window.startMs >= FLOOD_WINDOW_MS) {
                suppressed = window.suppressed
                window.startMs = now
                window.lines = 0
                window.suppressed = 0
            } else {
                suppressed = 0
            }
            if (window.lines >= FLOOD_LINES) {
                window.suppressed++
                return false
            }
            window.lines++
        }
        if (suppressed > 0) {
            val note = "$suppressed lines from this tag held back in ${FLOOD_WINDOW_MS / 1000}s (flood guard)"
            enqueue(Log.WARN, null, tag, note, null)
            Log.w(tag, note)
        }
        return true
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
        return try {
            listLogFiles(dir)
        } catch (e: Exception) {
            // An unreadable directory exports nothing; the export lists that as a problem.
            emptyList()
        }
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

    /** Only cheap work on the caller's thread: capture time and thread, hand the rest to the writer. */
    private fun enqueue(priority: Int, type: LogType?, tag: String, msg: String, tr: Throwable?) {
        val level = when (priority) {
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            else -> 'E'
        }
        val line = Line(clock(), Process.myPid(), Process.myTid(), level, type, tag, msg, tr)
        if (commands.trySend(Command.Write(line)).isFailure) dropped.incrementAndGet()
    }

    /** `2026-11-04 10:15:02.123Z 1234 5678 W/PTT/PttSendManager: message` — the type is absent on errors. */
    private fun format(line: Line): String = buildString {
        append(timeFormat.format(Instant.ofEpochMilli(line.timeMs))).append(' ')
        append(line.pid).append(' ').append(line.tid).append(' ')
        append(line.level).append('/')
        if (line.type != null) append(line.type.name).append('/')
        append(line.tag).append(": ").append(line.msg).append('\n')
        if (line.tr != null) append(Log.getStackTraceString(line.tr)).append('\n')
    }

    private suspend fun writeLoop() {
        purge()
        while (true) {
            try {
                handle(commands.receive())
                // Drain what queued meanwhile, then flush once per batch rather than per line.
                while (true) handle(commands.tryReceive().getOrNull() ?: break)
                flushStream()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The writer must outlive any single bad line or disk error; logcat still has every line.
                dropped.incrementAndGet()
            }
        }
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Write -> write(command.line)
            is Command.Flush -> {
                flushStream()
                command.done.complete(Unit)
            }
            is Command.Delete -> {
                closeStream()
                val dir = logDir
                val allGone = dir == null || listLogFiles(dir).all { !it.exists() || it.delete() }
                if (!allGone) Log.w(TAG, "Some log files could not be deleted")
                dropped.set(0)
                command.done.complete(allGone)
            }
        }
    }

    private fun write(line: Line) {
        val stream = openStream() ?: run {
            dropped.incrementAndGet()
            return
        }
        val lost = dropped.getAndSet(0)
        try {
            if (lost > 0) {
                val note = Line(clock(), Process.myPid(), Process.myTid(), 'W', null, TAG,
                    "$lost log lines not written: queue full, file unavailable or logger failure", null)
                writeBytes(stream, format(note))
            }
            writeBytes(stream, format(line))
            if (outBytes >= MAX_FILE_BYTES || clock() - outStartedMs >= MAX_FILE_SPAN_MS) {
                closeStream()
                purge()
            }
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

    /** A new file per open: named by its start time, so names sort oldest first. */
    private fun openStream(): OutputStream? {
        out?.let { return it }
        val dir = logDir ?: return null
        val now = clock()
        if (now < retryAfterMs) return null
        return try {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the log directory")
            var file = File(dir, "$FILE_PREFIX-$now$FILE_SUFFIX")
            // Two opens in one millisecond (a rotation right after a start) must not share a file.
            var suffix = 1
            while (file.exists()) file = File(dir, "$FILE_PREFIX-$now-${suffix++}$FILE_SUFFIX")
            outBytes = 0L
            outStartedMs = now
            BufferedOutputStream(FileOutputStream(file, true)).also { out = it }
        } catch (e: Exception) {
            fileFailed(e)
            null
        }
    }

    /**
     * Every `stardust*.log` in [dir], oldest first. Ordered by last write, so the files of the
     * earlier rotating scheme (stardust.log, stardust.1.log …) sort and age out with the rest.
     */
    private fun listLogFiles(dir: File): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?: emptyArray())
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))

    /** Deletes files older than [MAX_AGE_MS], then the oldest until the rest fit the size and count caps. */
    private fun purge() {
        val dir = logDir ?: return
        try {
            val oldestKept = clock() - MAX_AGE_MS
            val files = listLogFiles(dir).toMutableList()
            val current = files.lastOrNull().takeIf { out != null }
            files.removeAll { file -> file != current && file.lastModified() < oldestKept && file.delete() }
            var total = files.sumOf { it.length() }
            while (files.size > 1 && (total > MAX_TOTAL_BYTES || files.size > MAX_FILE_COUNT)) {
                val oldest = files.removeAt(0)
                if (oldest == current) break
                total -= oldest.length()
                if (!oldest.delete()) Log.w(TAG, "Old log file could not be deleted")
            }
        } catch (e: Exception) {
            // Purging is housekeeping: a failure leaves old files for the next purge.
            Log.w(TAG, "Log purge failed", e)
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
        try {
            Log.e(TAG, "Log file unavailable; logcat only for ${RETRY_MS / 1000}s", e)
        } catch (t: Throwable) {
            // No logcat (a JVM test): nothing else to report to.
        }
        closeStream()
        retryAfterMs = clock() + RETRY_MS
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
