package com.commcrete.stardust.room.sos_messages

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SOSMessageItem::class], version = 1, exportSchema = false)

abstract class SOSMessagesDatabase : RoomDatabase() {
    abstract fun messagesDao() : SOSMessagesDao

    companion object {
        @Volatile
        private var INSTANCE : SOSMessagesDatabase? = null

        fun getDatabase(context: Context) : SOSMessagesDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SOSMessagesDatabase::class.java,
                    "sos_messages_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
    }
}