package com.commcrete.stardust.room.legacy_db


import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.commcrete.stardust.room.Converters
import com.commcrete.stardust.room.legacy_db.messages.MessageItem
import com.commcrete.stardust.room.legacy_db.messages.MessagesDao
import com.commcrete.stardust.util.DataManager

@Database(entities = [MessageItem::class], version = 25, exportSchema = false)
@TypeConverters(Converters.EnumConverter::class)

/**
 * Read-only migration source, consumed and deleted by `LegacyMigrator` on the
 * first run against an install that predates the unified `AppDatabase`.
 *
 * Two rules apply to all three legacy databases:
 *
 *  - **Never open one unless its file already exists.** Room *creates* the file
 *    on open, and `messages_database` is a generic name in the host's shared
 *    `databases/` directory — manufacturing it invents a collision with any
 *    other ATAK plugin that picks the same name. `LegacyMigrator` gates every
 *    open on existence.
 *  - **No `fallbackToDestructiveMigration()`.** If a file under this name turns
 *    out to have a foreign schema it belongs to somebody else; the open must
 *    fail so the migrator can leave it alone, not drop their tables.
 */
abstract class MessagesDatabase : RoomDatabase() {
    abstract fun messagesDao() : MessagesDao

    companion object {
        @Volatile
        private var INSTANCE : MessagesDatabase? = null

        fun getDatabase() : MessagesDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    DataManager.appContext,
                    MessagesDatabase::class.java,
                    "messages_database"
                ).build()
                INSTANCE = instance
                return instance
            }
        }

        /**
         * Closes the database and drops the cached handle. Plain [close] leaves
         * [INSTANCE] pointing at a closed object, so the next [getDatabase] hands
         * back a handle that throws on first use.
         */
        fun closeAndClear() = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}