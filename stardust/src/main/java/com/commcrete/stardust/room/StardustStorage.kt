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
 *     media/               (only when the host supplied no location)
 *     models/ locks/ logs/ config/
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
 * ## When the host's location *is* our root
 *
 * A host that passes its own `filesDir` — which ATAK does — makes
 * `<location>/stardust` resolve to exactly [root]. The media root and the SDK
 * root then alias, and [clearMedia], whose job is to delete every child of the
 * media root, deletes `db/` and the open database with it. The primary
 * connection survives on the unlinked inode, so nothing fails until Room opens
 * a second one — typically on the next login, as
 * `SQLiteCantOpenDatabaseException: Directory … doesn't exist`.
 *
 * [initMediaRoot] therefore refuses any location that would contain the
 * database directory and falls back to the internal `media/` subtree, and
 * [clearMedia] additionally never deletes an [INTERNAL_DIR_NAMES] entry sitting
 * directly in [root]. Media a previous build left loose in [root] is moved
 * under `media/` once, by [relocateStrayMedia].
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
 * That happens once per process, inside `databaseBuilder`, while Room opens the
 * file lazily and opens further connections on demand long afterwards — so
 * `AppDatabase` also re-creates the directory on every open, and
 * [ensureDirectories] creates the whole tree at `StardustAPI.init` for an
 * install that has none of it yet.
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

    /**
     * Everything directly under [root] that is *not* media: the database and
     * the directories handed out by [internalDir]. Media wipes skip these, and
     * so does [relocateStrayMedia].
     *
     * Kept as one list because both of those walk [root] by name — the
     * alternative is discovering each name the moment some caller happens to
     * ask [internalDir] for it, which is exactly when a wipe would already have
     * run.
     */
    private val INTERNAL_DIR_NAMES = setOf(DB_DIR, MEDIA_DIR, "models", "locks", "logs", "config")

    /** `files/stardust` — the root of everything this SDK owns. */
    val root: File get() = File(DataManager.appContext.filesDir, ROOT_DIR)

    /** `files/stardust/db` — Room databases only. */
    private val databaseDir: File get() = File(root, DB_DIR)

    /** `files/stardust/media` — where media goes when the host supplied nowhere else. */
    private val internalMediaRoot: File get() = File(root, MEDIA_DIR)

    /**
     * [root] with a trailing separator, in both spellings a stored path can
     * use: `/data/user/0/<pkg>/…` is a symlink to `/data/data/<pkg>/…`, and
     * which one a path carries depends on when it was written.
     *
     * Memoized — the process's `filesDir` does not move — because this backs
     * the fast reject in [relocatedMediaPath].
     */
    private val rootPrefixes: Set<String> by lazy {
        setOf(root.absolutePath, root.canonicalOrAbsolute().path)
            .mapTo(mutableSetOf()) { it + File.separator }
    }

    /**
     * `<host-supplied location>/stardust` — PTT recordings and received files.
     * Null until [initMediaRoot] runs; [mediaRoot] falls back to the internal
     * subtree so nothing can write into the host's directory by accident.
     */
    @Volatile
    private var resolvedMediaRoot: File? = null

    /**
     * Resolves the media root from the location the host passed to
     * `StardustAPI.init` and creates it, along with the rest of the tree.
     *
     * The host's path is used as a *parent*, never written to directly — see
     * the class comment. A location that would swallow the database directory,
     * and a blank or unusable one, fall back to the internal subtree, which is
     * always writable.
     *
     * @return the absolute path of the resolved root, for [DataManager.fileLocation].
     */
    fun initMediaRoot(hostLocation: String?): String {
        val hostRoot = hostLocation?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        val requested = if (hostRoot != null) File(hostRoot, ROOT_DIR) else internalMediaRoot

        // A host that passes its own filesDir lands exactly on `root`, and a
        // media root that contains the database is one `clearMedia` away from
        // deleting it out from under an open connection. See the class comment.
        val candidate = if (requested.containsOrIs(databaseDir)) {
            Timber.w(
                "Media location $requested holds the Stardust database — " +
                    "using $internalMediaRoot instead"
            )
            internalMediaRoot
        } else {
            requested
        }

        val usable = runCatching { candidate.exists() || candidate.mkdirs() }.getOrDefault(false)
        val resolved = if (usable) {
            candidate
        } else {
            Timber.w("Media root $candidate is not usable — falling back to internal storage")
            internalMediaRoot
        }

        resolvedMediaRoot = resolved
        ensureDirectories()
        if (resolved.isSameAs(internalMediaRoot)) relocateStrayMedia(resolved)
        Timber.d("StardustStorage: media root = $resolved")
        return resolved.absolutePath
    }

    /**
     * Creates the directories the SDK writes to before anything asks for them.
     *
     * A fresh install has none of this tree, and the places that create a
     * directory on demand ([chatDir], [internalDir], [appDatabasePath]) only
     * cover the caller that got there first. Repeating it is a handful of
     * `stat` calls, so it runs on every `StardustAPI.init`.
     */
    fun ensureDirectories() {
        listOf(root, databaseDir, mediaRoot).forEach { dir ->
            val ready = runCatching { dir.exists() || dir.mkdirs() }.getOrDefault(false)
            if (!ready) Timber.w("StardustStorage: could not create $dir")
        }
    }

    /**
     * Moves media a previous build left directly in [root] — back when the
     * media root resolved to [root] itself — into [target].
     *
     * [INTERNAL_DIR_NAMES] stay where they are: those are the database and the
     * internal directories, not media. A name already taken in [target] is left
     * alone rather than merged, because the two entries are different files
     * with the same chat id and only the caller could say which one wins.
     *
     * Both trees are inside [root], so each move is a rename within one
     * filesystem. The absolute paths already stored in message rows are
     * re-pointed separately, by `AppRepository.rewriteRelocatedMediaPaths` —
     * see [relocatedMediaPath]. That runs off the database and cannot happen
     * here, in `StardustAPI.init`, before the database exists.
     */
    private fun relocateStrayMedia(target: File) {
        val strays = root.listFiles()?.filter { it.name !in INTERNAL_DIR_NAMES }.orEmpty()
        if (strays.isEmpty()) return

        strays.forEach { stray ->
            val destination = File(target, stray.name)
            val moved = runCatching { !destination.exists() && stray.renameTo(destination) }
                .getOrDefault(false)
            if (moved) {
                Timber.d("StardustStorage: moved stray media $stray -> $destination")
            } else {
                Timber.w("StardustStorage: could not move stray media $stray -> $destination")
            }
        }
    }

    /**
     * Where [stored] ended up after [relocateStrayMedia] moved it, or null if it
     * is not a path that moved.
     *
     * Answers for one stored path so the caller — the message rows are the only
     * place these paths are kept — can rewrite a row at a time without knowing
     * anything about the layout. A path qualifies when it sits directly in
     * [root] outside [INTERNAL_DIR_NAMES], which is where and only where the
     * aliased build wrote media.
     *
     * The file must be at the new location for this to answer: a move that was
     * refused (a name already taken in [mediaRoot]) left the file in [root],
     * and the path stored for it is still the right one. That also makes this
     * idempotent — once a row is rewritten it no longer matches — and keeps it
     * from touching a path that merely resembles ours.
     */
    fun relocatedMediaPath(stored: String?): String? {
        val path = stored?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Cheap reject first. This is asked of every attachment and PTT row at
        // startup, and all but a handful of them cannot be under `root` at all;
        // only a path that gets past here is worth resolving, which costs a
        // trip to the filesystem.
        if (rootPrefixes.none { path.startsWith(it) }) return null

        // Nothing was moved unless media resolves inside our own tree — see
        // [relocateStrayMedia]. The existence check below would refuse anyway;
        // this says why.
        if (!mediaRoot.isSameAs(internalMediaRoot)) return null

        val rootPath = root.canonicalOrAbsolute().path
        val filePath = File(path).canonicalOrAbsolute().path
        if (!filePath.startsWith(rootPath + File.separator)) return null

        val relative = filePath.removePrefix(rootPath + File.separator)
        if (relative.substringBefore(File.separatorChar) in INTERNAL_DIR_NAMES) return null

        val relocated = File(mediaRoot, relative)
        return relocated.absolutePath.takeIf { relocated.exists() }
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
        check(ensureDatabaseDirectory()) {
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
     * Creates the directory the database file lives in, returning whether it is
     * there afterwards.
     *
     * Called on every database open — see `AppDatabase` — not only when the
     * path is first handed to Room, so a directory that disappears mid-process
     * costs one recreated directory instead of every subsequent open.
     */
    internal fun ensureDatabaseDirectory(): Boolean {
        val dir = databaseDir
        return runCatching { dir.exists() || dir.mkdirs() }.getOrDefault(false)
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
            if (isInternal(child)) {
                Timber.d("StardustStorage.clearMedia: keeping $child")
                return@forEach
            }
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
     * True when [child] is the database directory or one of the internal
     * directories, and so must survive a media wipe.
     *
     * [initMediaRoot] already keeps the two roots apart, which leaves nothing
     * internal inside the media root for this to match. It is the second lock
     * on the same door: the cost of a wrong answer here is a logout that wipes
     * the live database, and the roots are only as separate as whatever the
     * host passes in.
     *
     * Only an entry directly under [root] qualifies — a chat whose id happens
     * to be "logs" is still media.
     */
    private fun isInternal(child: File): Boolean {
        if (child.name !in INTERNAL_DIR_NAMES) return false
        return child.parentFile?.isSameAs(root) == true
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

    /**
     * The canonical form, falling back to the absolute one.
     *
     * Canonicalising resolves `..`, a trailing separator and any symlink on the
     * way, which is what makes the comparisons below answer about *files*
     * rather than about strings. It needs no path to exist, but it does touch
     * the filesystem and can throw, hence the fallback.
     */
    private fun File.canonicalOrAbsolute(): File =
        runCatching { canonicalFile }.getOrElse { absoluteFile }

    /** True when both paths name the same directory. */
    private fun File.isSameAs(other: File): Boolean =
        canonicalOrAbsolute().path == other.canonicalOrAbsolute().path

    /** True when [other] is this directory or lies underneath it. */
    private fun File.containsOrIs(other: File): Boolean {
        val self = canonicalOrAbsolute().path
        val target = other.canonicalOrAbsolute().path
        return target == self || target.startsWith(self + File.separator)
    }
}
