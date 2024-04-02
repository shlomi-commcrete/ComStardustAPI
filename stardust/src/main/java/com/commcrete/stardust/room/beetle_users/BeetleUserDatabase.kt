package com.commcrete.stardust.room.beetle_users

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BittelUser::class], version = 2, exportSchema = false)
abstract class BittelUserDatabase : RoomDatabase() {
    abstract fun bittelUserDao() : BittelUserDoa

    companion object {
        @Volatile
        private var INSTANCE : BittelUserDatabase? = null

        fun getDatabase(context: Context) : BittelUserDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BittelUserDatabase::class.java,
                    "bittel_user_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
    }
}