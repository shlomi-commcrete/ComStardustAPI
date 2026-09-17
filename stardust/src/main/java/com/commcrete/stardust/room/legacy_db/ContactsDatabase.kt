package com.commcrete.stardust.room.legacy_db


import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.legacy_db.contacts.ContactsDao
import com.commcrete.stardust.util.DataManager

@Database(entities = [ChatContact::class], version = 21, exportSchema = false)
/**
 * Read-only migration source, consumed and deleted by `LegacyMigrator`.
 * See [MessagesDatabase] for the two rules that govern the legacy databases:
 * never open one whose file does not already exist, and no destructive
 * migration fallback.
 */
abstract class ContactsDatabase : RoomDatabase() {
    abstract fun contactsDao() : ContactsDao

    companion object {
        @Volatile
        private var INSTANCE : ContactsDatabase? = null

        fun getDatabase() : ContactsDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    DataManager.appContext,
                    ContactsDatabase::class.java,
                    "contacts_database"
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