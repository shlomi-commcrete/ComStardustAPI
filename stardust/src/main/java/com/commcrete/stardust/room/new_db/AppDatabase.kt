package com.commcrete.stardust.room.new_db


import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.commcrete.stardust.room.Converters
import com.commcrete.stardust.room.StardustStorage
import com.commcrete.stardust.room.new_db.audit.ContactIdentityLogDao
import com.commcrete.stardust.room.new_db.audit.ContactIdentityLogEntity
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatParticipantEntity
import com.commcrete.stardust.room.new_db.contact.ContactDeviceEntity
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactUserIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactGroupIdEntity
import com.commcrete.stardust.room.new_db.contact.DeviceEntity
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.util.DataManager
import timber.log.Timber
import com.commcrete.stardust.room.new_db.contact.ContactsDao as NewContactsDao

/**
 * The unified Room database — every chat, contact, message and audit row the
 * SDK owns.
 *
 * Stored at `files/stardust/db/stardust.db` rather than under a name in the host's
 * shared `databases/` directory; see [StardustStorage] for why that matters in
 * a process where every ATAK plugin shares one data directory.
 *
 * Schema changes need a real [androidx.room.migration.Migration] — there is no
 * destructive fallback. See `getDatabase`.
 *
 * Tables:
 *  - new_chats_table
 *  - app_contacts, app_contact_user_ids, app_devices, app_contact_devices
 *  - new_messages_table
 *  - contact_identity_log (append-only audit of identity ownership changes)
 *
 * New views:
 *  - new_contacts_ (transitional compatibility projection)
 *  - chat_summary
 */
@Database(
    entities = [
        // New entities used by AppRepository.
        ChatEntity::class,
        ContactEntity::class,
        ContactUserIdEntity::class,
        ContactGroupIdEntity::class,
        ContactDeviceEntity::class,
        DeviceEntity::class,
        MessageEntity::class,
        ChatParticipantEntity::class,
        ContactIdentityLogEntity::class,
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    Converters.StringArrayConverter::class,
    Converters.DoubleArrayConverter::class,
    Converters.EnumConverter::class,
)
abstract class AppDatabase : RoomDatabase() {


    // New accessors for future use
    abstract fun appChatsDao(): ChatDao
    abstract fun appContactsDao(): NewContactsDao
    abstract fun appMessagesDao(): MessageDao
    abstract fun appContactIdentityLogDao(): ContactIdentityLogDao

    companion object {

        /**
         * The name this database used while it lived in the host's shared
         * `databases/` directory. Generic enough for another ATAK plugin to
         * pick, which is why the database moved under [StardustStorage].
         * Deleted on first open so upgraded installs do not leave a colliding
         * file behind.
         */
        private const val ORPHANED_DATABASE_NAME = "app_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    deleteOrphanedDatabase()
                    Room.databaseBuilder(
                        DataManager.appContext,
                        AppDatabase::class.java,
                        StardustStorage.appDatabasePath()
                    )
                        // Re-creates the parent directory on every open, not
                        // just on the one that built this instance. See
                        // DirectoryCreatingOpenHelper.
                        .openHelperFactory(
                            DirectoryCreatingOpenHelper.Factory(
                                FrameworkSQLiteOpenHelperFactory()
                            )
                        )
                        // No fallbackToDestructiveMigration: a version bump
                        // without a matching Migration must fail loudly rather
                        // than silently drop every chat and message on the
                        // device. Schemas are exported (see the room.schemaLocation
                        // kapt argument) so migrations can be written against
                        // them.
                        .build()
                        .also { INSTANCE = it }
                }
            }
        }

        /**
         * Closes the database and drops the cached handle. Plain [close] leaves
         * [INSTANCE] pointing at a closed object, so the next [getDatabase]
         * hands back a handle that throws on first use. Mirrors the legacy
         * databases, and is what makes [StardustStorage.deleteAll] safe.
         */
        fun closeAndClear() = synchronized(this) {
            runCatching { INSTANCE?.close() }
            INSTANCE = null
        }

        /**
         * Removes the pre-subtree database from the host's shared `databases/`
         * directory. Idempotent and cheap — `deleteDatabase` is a no-op when
         * the file is absent, so this needs no "already done" flag.
         *
         * Note this deletes *our* old file, not a same-named file belonging to
         * another plugin: we cannot tell the difference, and leaving ours
         * behind keeps the collision alive. The window is one upgrade.
         */
        private fun deleteOrphanedDatabase() {
            runCatching {
                val path = DataManager.appContext.getDatabasePath(ORPHANED_DATABASE_NAME)
                if (path.exists() &&
                    DataManager.appContext.deleteDatabase(ORPHANED_DATABASE_NAME)
                ) {
                    Timber.d("Removed orphaned database $ORPHANED_DATABASE_NAME")
                }
            }.onFailure {
                Timber.w(it, "Could not remove orphaned database $ORPHANED_DATABASE_NAME")
            }
        }
    }
}

/**
 * An open helper that makes sure `files/stardust/db` exists before it hands the
 * database over.
 *
 * [StardustStorage.appDatabasePath] creates that directory, but it runs once
 * per process, inside `databaseBuilder`. Room opens the file lazily on the
 * first query and opens the helper again after every [AppDatabase.closeAndClear],
 * so anything that removes the directory in between — our own media wipe used
 * to, a host or user file cleanup still can — leaves a path Room cannot open:
 * SQLite creates a missing *file*, never a missing *directory*, and the open
 * fails with `SQLiteCantOpenDatabaseException: Directory … doesn't exist`.
 * Creating a directory that is already there is one `stat`, so no attempt is
 * made to remember whether this has run.
 *
 * It cannot cover every open: a connection the WAL pool adds on its own, for a
 * concurrent read, is opened inside the framework and never passes through
 * here. Keeping the directory in place is still [StardustStorage]'s job — this
 * only stops one deletion from being fatal for the life of the process.
 *
 * A failed `mkdirs` is logged and ignored: the open that follows produces the
 * real, specific error, and swallowing it here would say less.
 */
private class DirectoryCreatingOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper by delegate {

    override val writableDatabase: SupportSQLiteDatabase
        get() {
            ensureDirectory()
            return delegate.writableDatabase
        }

    override val readableDatabase: SupportSQLiteDatabase
        get() {
            ensureDirectory()
            return delegate.readableDatabase
        }

    private fun ensureDirectory() {
        if (!StardustStorage.ensureDatabaseDirectory()) {
            Timber.w("Could not create the Stardust database directory before opening $databaseName")
        }
    }

    class Factory(
        private val delegate: SupportSQLiteOpenHelper.Factory,
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(
            configuration: SupportSQLiteOpenHelper.Configuration,
        ): SupportSQLiteOpenHelper = DirectoryCreatingOpenHelper(delegate.create(configuration))
    }
}
