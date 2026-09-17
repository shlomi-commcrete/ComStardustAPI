package com.commcrete.stardust.room.legacy_db


import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.commcrete.stardust.room.Converters
import com.commcrete.stardust.room.legacy_db.chats.ChatItem
import com.commcrete.stardust.room.legacy_db.chats.ChatsDao
import com.commcrete.stardust.util.DataManager

@Database(entities = [ChatItem::class], version = 32, exportSchema = false)
@TypeConverters(Converters.StringArrayConverter::class, Converters.DoubleArrayConverter::class)
/**
 * Read-only migration source, consumed and deleted by `LegacyMigrator`.
 * See [MessagesDatabase] for the two rules that govern the legacy databases:
 * never open one whose file does not already exist, and no destructive
 * migration fallback.
 */
abstract class ChatsDatabase : RoomDatabase() {
    abstract fun chatsDao() : ChatsDao

    companion object {
        @Volatile
        private var INSTANCE : ChatsDatabase? = null

        fun getDatabase() : ChatsDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    DataManager.appContext,
                    ChatsDatabase::class.java,
                    "chats_database"
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