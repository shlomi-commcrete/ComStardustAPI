package com.commcrete.stardust.room.new_db


import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
