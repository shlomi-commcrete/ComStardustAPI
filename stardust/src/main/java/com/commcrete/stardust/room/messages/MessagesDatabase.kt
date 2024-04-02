package com.commcrete.stardust.room.messages

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.commcrete.stardust.room.Converters

@Database(entities = [MessageItem::class], version = 21, exportSchema = false)
@TypeConverters(Converters.EnumConverter::class)

abstract class MessagesDatabase : RoomDatabase() {
    abstract fun messagesDao() : MessagesDao

    companion object {
        @Volatile
        private var INSTANCE : MessagesDatabase? = null

        fun getDatabase(context: Context) : MessagesDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MessagesDatabase::class.java,
                    "messages_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages_table ADD COLUMN time TEXT NOT NULL DEFAULT '' ")
            }
        }
    }
}