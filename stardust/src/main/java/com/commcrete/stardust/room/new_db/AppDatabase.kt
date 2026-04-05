package com.commcrete.stardust.room.new_db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.commcrete.stardust.room.Converters
import com.commcrete.stardust.room.chats.ChatItem
import com.commcrete.stardust.room.chats.ChatsDao
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.contacts.ContactsDao
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDao

/**
 * Single unified Room database that combines chats, contacts and messages as tables.
 *
 * Tables:
 *  - chats_table     → [ChatItem]
 *  - contacts_table  → [ChatContact]
 *  - messages_table  → [MessageItem]
 */
@Database(
    entities = [
        ChatItem::class,
        ChatContact::class,
        MessageItem::class,
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(
    Converters.StringArrayConverter::class,
    Converters.DoubleArrayConverter::class,
    Converters.EnumConverter::class,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatsDao(): ChatsDao
    abstract fun contactsDao(): ContactsDao
    abstract fun messagesDao(): MessagesDao

    companion object {
        private const val DATABASE_NAME = "app_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

