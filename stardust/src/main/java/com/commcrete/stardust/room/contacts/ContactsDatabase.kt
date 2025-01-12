package com.commcrete.stardust.room.contacts

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChatContact::class], version = 20, exportSchema = false)
abstract class ContactsDatabase : RoomDatabase() {
    abstract fun contactsDao() : ContactsDao

    companion object {
        @Volatile
        private var INSTANCE : ContactsDatabase? = null

        fun getDatabase(context: Context) : ContactsDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
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