package com.commcrete.stardust.room.friends

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.commcrete.stardust.request_objects.model.user_list.User
import com.commcrete.stardust.room.Converters

@Database(entities = [User::class], version = 4, exportSchema = false)
@TypeConverters(Converters.StringArrayConverter::class, Converters.DoubleArrayConverter::class)
abstract class FriendsDatabase : RoomDatabase() {
    abstract fun friendsDao() : FriendsDao

    companion object {
        @Volatile
        private var INSTANCE : FriendsDatabase? = null

        fun getDatabase(context: Context) : FriendsDatabase {
            val tempInstance = INSTANCE
            if(tempInstance != null){
                return tempInstance
            }
            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FriendsDatabase::class.java,
                    "friends_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
    }
}