package com.commcrete.bittell.room.sniffer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.commcrete.stardust.room.Converters

@Database(entities = [SnifferItem::class], version = 1, exportSchema = false)
@TypeConverters(Converters.EnumConverter::class)

abstract class SnifferDatabase : RoomDatabase() {
    abstract fun snifferDao() : SnifferDao

    companion object {
        @Volatile
        private var INSTANCE : SnifferDatabase? = null

        fun getDatabase(context: Context) : SnifferDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SnifferDatabase::class.java,
                    "sniffer_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
    }
}