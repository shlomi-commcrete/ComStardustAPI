package com.commcrete.stardust.room

import com.commcrete.stardust.util.DataManager
import timber.log.Timber
import java.io.File

/**
 * The one filesystem subtree this SDK owns inside the host's data directory.
 *
 * # Why a subtree rather than a name in `databases/`
 *
 * ATAK plugins are loaded into the `com.atakmap.app.civ` process under ATAK's
 * UID, so every plugin shares one data directory. A database dropped into
 * `databases/` under a generic name (`app_database`, as this SDK used to do)
 * collides with any other plugin that picks the same obvious name — and with
 * `fallbackToDestructiveMigration()` in play, whichever plugin opens second
 * silently drops and recreates the other's tables.
 *
 * Everything we persist therefore lives under a directory nobody else will
 * pick:
 *
 * ```
 * /data/user/0/com.atakmap.app.civ/files/stardust/
 *     db/stardust.db       (+ -wal / -shm siblings)
 * ```
 *
 * The second reason is [deleteAll]: a security wipe becomes one recursive
 * delete of a directory we own outright, instead of an enumerated list of file
 * names that has to be kept in step with every database we add.
 *
 * # Media
 *
 * PTT recordings and received files do **not** live under that root, because
 * the host picks where they go — it passes a location into
 * `StardustAPI.init`, and it may deliberately choose somewhere with more room
 * than internal storage. We honour that choice but never write into it
 * directly: [mediaRoot] is always a `stardust/` subdirectory *of* whatever the
 * host supplied, so the tree is still ours alone, and [deleteAll] removes it
 * along with the database.
 *
 * That replaces the previous arrangement, where per-chat directories were
 * created straight in the host's `filesDir` and the cleanup swept that
 * directory for anything whose name matched one of our chat ids — which would
 * happily have deleted an ATAK or other-plugin directory that happened to
 * share a name.
 *
 * # Why the directory is created here
 *
 * Room takes a *name*, not a `File`, and passes it to
 * `Context.getDatabasePath`. That honours a name beginning with `/` verbatim —
 * which is what makes [appDatabasePath] work — but it only ever calls a
 * single-level `mkdir()` on the parent. A nested path like `files/stardust/db`
 * needs `mkdirs()`, so we create the tree ourselves before handing the path to
 * Room rather than relying on that.
 *
 * Note this is deliberately *not* [DataManager.fileLocation]: that is a
 * host-supplied location for user-visible artifacts (recordings, attachments),
 * and the host is free to point it at shared storage. This root is private.
 */
internal object StardustStorage {

    private const val ROOT_DIR = "stardust"
    private const val DB_DIR = "db"
    private const val MEDIA_DIR = "media"
    // Names itself. This turns up alone in crash logs, `ls` output and bug
    // reports, where "app.db" would say nothing about whose database it is.
    private const val APP_DB_FILE = "stardust.db"
    private const val UNKNOWN_CHAT_DIR = "_unknown_chat"

    /** `files/stardust` — the root of everything this SDK owns. */
    val root: File get() = File(DataManager.appContext.filesDir, ROOT_DIR)

    /** `files/stardust/db` — Room databases only. */
    private val databaseDir: File get() = File(root, DB_DIR)

    /**
     * `<host-supplied location>/stardust` — PTT recordings and received files.
     * Null until [initMediaRoot] runs; [mediaRoot] falls back to the internal
     * subtree so nothing can write into the host's directory by accident.
     */
    @Volatile
    private var resolvedMediaRoot: File? = null

    /**
     * Resolves the media root from the location the host passed to
     * `StardustAPI.init` and creates it.
     *
     * The host's path is used as a *parent*, never written to directly — see
     * the class comment. A blank or unusable location falls back to the
     * internal subtree, which is always writable.
     *
     * @return the absolute path of the resolved root, for [DataManager.fileLocation].
     */
    fun initMediaRoot(hostLocation: String?): String {
        val hostRoot = hostLocation?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        val candidate = if (hostRoot != null) File(hostRoot, ROOT_DIR) else File(root, MEDIA_DIR)

        val usable = runCatching { candidate.exists() || candidate.mkdirs() }.getOrDefault(false)
        val resolved = if (usable) {
            candidate
        } else {
            Timber.w("Media root $candidate is not usable — falling back to internal storage")
            File(root, MEDIA_DIR).apply { mkdirs() }
        }

        resolvedMediaRoot = resolved
        Timber.d("StardustStorage: media root = $resolved")
        return resolved.absolutePath
    }

    /**
     * Where PTT recordings and received files go. Safe to read before
     * [initMediaRoot] — it just resolves to the internal subtree.
     */
    val mediaRoot: File
        get() = resolvedMediaRoot ?: File(root, MEDIA_DIR)

    /**
     * A per-chat media directory under [mediaRoot], created on demand.
     *
     * A null or blank [chatId] lands in [UNKNOWN_CHAT_DIR] rather than in a
     * directory literally named "null", which is what the old string
     * interpolation produced. Note `deleteChatFiles` will not reach that bucket
     * — it only sweeps directories matching a known chat id — but [deleteAll]
     * does.
     */
    fun chatDir(chatId: String?): File {
        val name = chatId?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_CHAT_DIR
        return File(mediaRoot, name).apply { if (!exists()) mkdirs() }
    }

    /**
     * A named directory inside the internal subtree, created on demand — for
     * config, logs and scratch. Keeps generic folder names like `config` out of
     * the host's shared files directory.
     */
    fun internalDir(name: String): File =
        File(root, name).apply { if (!exists()) mkdirs() }

    /**
     * Absolute path for the unified database, with the parent tree created.
     *
     * @throws IllegalStateException if the directory cannot be created — there
     *         is no sane fallback, and failing here is far easier to diagnose
     *         than the `SQLiteCantOpenDatabaseException` Room would throw next.
     */
    fun appDatabasePath(): String {
        val dir = databaseDir
        check(dir.exists() || dir.mkdirs()) {
            "Could not create the Stardust database directory at $dir"
        }
        val path = File(dir, APP_DB_FILE).absolutePath

        // Cheap guard on the one assumption this design rests on: that Room's
        // name -> Context.getDatabasePath round-trip leaves an absolute path
        // alone. If a future Room ever resolves it somewhere else (e.g. against
        // the no-backup directory), this logs it instead of silently opening a
        // database in the wrong place.
        val resolved = DataManager.appContext.getDatabasePath(path).absolutePath
        if (resolved != path) {
            Timber.w("Database path resolved to $resolved, expected $path")
        }
        return path
    }

    /**
     * Empties [mediaRoot] — every PTT recording and received file — while
     * leaving the root itself, the database and the extracted models in place.
     *
     * This is the logout wipe. `AppRepository.clearData` drops every chat and
     * message row, so the moment it returns these files are orphans that
     * nothing can reach but anyone with the device can still read. The database
     * file survives (an empty schema is what the next session wants) and so do
     * the models, which are expensive to re-extract from the APK.
     *
     * For the security wipe, which takes all of it, see [deleteAll].
     *
     * @return true if the directory is empty afterwards.
     */
    fun clearMedia(): Boolean {
        val dir = mediaRoot
        if (!dir.exists()) return true
        var allGone = true
        dir.listFiles()?.forEach { child ->
            val deleted = runCatching { child.deleteRecursively() }.getOrDefault(false)
            if (!deleted) {
                allGone = false
                Timber.w("StardustStorage.clearMedia: could not delete $child")
            }
        }
        Timber.d("StardustStorage.clearMedia: $dir cleared=$allGone")
        return allGone
    }

    /**
     * Recursively deletes everything this SDK owns — the internal subtree
     * (database included) and the media root, wherever the host put it.
     *
     * Close every database first (see `AppDatabase.closeAndClear`) or the open
     * handles will resurrect journal files as the process winds down.
     *
     * Only directories we created are touched: the media root is always our own
     * `stardust/` subdirectory of the host's location, never the host's
     * location itself.
     *
     * @return true if both trees are gone (including when they never existed).
     */
    fun deleteAll(): Boolean {
        // distinct() because the media root is inside `root` whenever the host
        // gave no location — deleting it twice would report a spurious failure.
        val targets = listOf(root, mediaRoot).distinctBy { it.absolutePath }
        var allGone = true
        targets.forEach { dir ->
            val deleted = runCatching { !dir.exists() || dir.deleteRecursively() }
                .getOrDefault(false)
            Timber.d("StardustStorage.deleteAll: $dir deleted=$deleted")
            if (!deleted) allGone = false
        }
        return allGone
    }
}
