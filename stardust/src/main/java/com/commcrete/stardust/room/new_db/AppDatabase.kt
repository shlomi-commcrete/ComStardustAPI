package com.commcrete.stardust.room.new_db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.commcrete.stardust.room.Converters
import com.commcrete.stardust.room.legacy_db.chats.ChatItem
import com.commcrete.stardust.room.legacy_db.chats.ChatsDao
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.legacy_db.contacts.ContactsDao as LegacyContactsDao
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDao
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.chat.ChatSummary
import com.commcrete.stardust.room.new_db.chat.ChatParticipantEntity
import com.commcrete.stardust.room.new_db.contact.ContactDeviceEntity
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactUserIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactGroupIdEntity
import com.commcrete.stardust.room.new_db.contact.DeviceEntity
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.room.new_db.contact.ContactsDao as NewContactsDao

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
 *  - app_contacts, app_contact_user_ids, app_devices, app_contact_devices
 *  - new_messages_table
 *
 * New views:
 *  - new_contacts_table (transitional compatibility projection)
 *  - chat_summary
 */
@Database(
    entities = [
        // Legacy entities kept for temporary compatibility paths.
        ChatItem::class,
        ChatContact::class,
        MessageItem::class,
        // New entities used by AppRepository.
        ChatEntity::class,
        ContactEntity::class,
        ContactUserIdEntity::class,
        ContactGroupIdEntity::class,
        ContactDeviceEntity::class,
        DeviceEntity::class,
        MessageEntity::class,
        ChatParticipantEntity::class,
    ],
    views = [ChatSummary::class],
    version = 8,
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
    abstract fun contactsDao(): LegacyContactsDao
    abstract fun messagesDao(): MessagesDao

    // New accessors for future use
    abstract fun appChatsDao(): ChatDao
    abstract fun appContactsDao(): NewContactsDao
    abstract fun appMessagesDao(): MessageDao
    abstract fun chatSummaryDao(): ChatDao

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
