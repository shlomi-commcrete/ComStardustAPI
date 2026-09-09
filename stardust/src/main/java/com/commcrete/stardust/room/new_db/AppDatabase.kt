package com.commcrete.stardust.room.new_db


import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.commcrete.stardust.room.Converters
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
import com.commcrete.stardust.room.new_db.contact.ContactsDao as NewContactsDao

/**
 * Unified Room database hosting both:
 * 1) legacy-compatible tables (temporary), and
 * 2) new v2 tables under new_db for future development.
 *
 * Legacy tables:
 *  - chats_table
 *  - contacts_table
 *  - messages_table
 *
 * New tables:
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
    version = 2,
    exportSchema = false
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
        private const val DATABASE_NAME = "app_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: adds `contact_identity_log` (see [ContactIdentityLogEntity]).
         *
         * A real migration rather than the destructive fallback: v1 databases hold
         * every chat and message on the device, and dropping them to gain an audit
         * table would be an absurd trade. The DDL must match what Room generates
         * for the entity — column order, affinities and index names included —
         * or the identity check on open fails.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_identity_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `at_ms` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `id_kind` TEXT,
                        `id_value` TEXT,
                        `from_contact_id` INTEGER,
                        `to_contact_id` INTEGER,
                        `from_name` TEXT,
                        `to_name` TEXT,
                        `from_chat_id` TEXT,
                        `to_chat_id` TEXT,
                        `affected_rows` INTEGER,
                        `source` TEXT NOT NULL,
                        `batch_id` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_identity_log_id_value` ON `contact_identity_log` (`id_value`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_identity_log_at_ms` ON `contact_identity_log` (`at_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_identity_log_from_contact_id` ON `contact_identity_log` (`from_contact_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_identity_log_to_contact_id` ON `contact_identity_log` (`to_contact_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_identity_log_batch_id` ON `contact_identity_log` (`batch_id`)")
            }
        }

        fun getDatabase(): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    DataManager.appContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
