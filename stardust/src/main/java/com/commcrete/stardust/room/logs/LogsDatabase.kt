package com.commcrete.stardust.room.logs

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LogObject::class], version = 1, exportSchema = false)
abstract class LogsDatabase : RoomDatabase() {
    abstract fun logsDao() : LogsDao

    companion object {
        @Volatile
        private var INSTANCE : LogsDatabase? = null

        fun getDatabase(context: Context) : LogsDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LogsDatabase::class.java,
                    "logs_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
    }
}