package com.commcrete.stardust.room.chats

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.commcrete.stardust.room.Converters

@Database(entities = [ChatItem::class], version = 30, exportSchema = false)
@TypeConverters(Converters.StringArrayConverter::class, Converters.DoubleArrayConverter::class)
abstract class ChatsDatabase : RoomDatabase() {
    abstract fun chatsDao() : ChatsDao

    companion object {
        @Volatile
        private var INSTANCE : ChatsDatabase? = null

        fun getDatabase(context: Context) : ChatsDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatsDatabase::class.java,
                    "chats_database"
                ).addMigrations(MIGRATION_28_30).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }

        val MIGRATION_28_30 = object : Migration(27, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add the new columns with a default value
                database.execSQL("ALTER TABLE chats_table ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats_table ADD COLUMN is_bittel INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats_table ADD COLUMN image_name TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}