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
import com.commcrete.stardust.room.new_db.chat.AppChatEntity
import com.commcrete.stardust.room.new_db.chat.AppChatsDao
import com.commcrete.stardust.room.new_db.contact.AppContactEntity
import com.commcrete.stardust.room.new_db.contact.AppContactsDao
import com.commcrete.stardust.room.new_db.message.AppMessageEntity
import com.commcrete.stardust.room.new_db.message.AppMessagesDao

/**
 * Unified Room database hosting both:
 * 1) legacy-compatible tables (temporary), and
 * 2) new v2 tables under new_db for future development.
 *
 * Legacy tables:
 *  - chats_table
 *  - contacts_table
 *  - messages_table
 *
 * New tables:
 *  - new_chats_table
 *  - new_contacts_table
 *  - new_messages_table
 */
@Database(
    entities = [
        // Legacy entities kept for temporary compatibility paths.
        ChatItem::class,
        ChatContact::class,
        MessageItem::class,
        // New entities used by AppRepository going forward.
        AppChatEntity::class,
        AppContactEntity::class,
        AppMessageEntity::class,
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(
    Converters.StringArrayConverter::class,
    Converters.DoubleArrayConverter::class,
    Converters.EnumConverter::class,
)
abstract class AppDatabase : RoomDatabase() {

    // Legacy accessors (temporary compatibility)
    abstract fun chatsDao(): ChatsDao
    abstract fun contactsDao(): ContactsDao
    abstract fun messagesDao(): MessagesDao

    // New accessors for future use
    abstract fun appChatsDao(): AppChatsDao
    abstract fun appContactsDao(): AppContactsDao
    abstract fun appMessagesDao(): AppMessagesDao

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
