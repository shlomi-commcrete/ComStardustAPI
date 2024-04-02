package com.commcrete.stardust.room.chats

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.commcrete.stardust.room.Converters

@Database(entities = [ChatItem::class], version = 27, exportSchema = false)
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
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
    }
}