package com.commcrete.stardust.room.legacy_db


import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.legacy_db.contacts.ContactsDao
import com.commcrete.stardust.util.DataManager

@Database(entities = [ChatContact::class], version = 21, exportSchema = false)
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
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
        val MIGRATION_28_29 = object : Migration(17, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add the new columns with a default value
                database.execSQL("ALTER TABLE contacts_database ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE contacts_database ADD COLUMN is_bittel INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

}