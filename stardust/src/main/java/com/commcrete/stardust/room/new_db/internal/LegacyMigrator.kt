package com.commcrete.stardust.room.new_db.internal

import android.content.Context
import androidx.core.content.edit
import com.commcrete.stardust.room.legacy_db.ChatsDatabase
import com.commcrete.stardust.room.legacy_db.ContactsDatabase
import com.commcrete.stardust.room.legacy_db.MessagesDatabase
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.legacy_db.messages.MessageItem
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.MessageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One-shot bridge from the three legacy Room databases (`chats_database`,
 * `contacts_database`, `messages_database`) to the unified [AppDatabase].
 *
 * Two responsibilities:
 *
 *  1. **[migrate]** — copies every row from the legacy schemas into the new
 *     unified schema, deletes the legacy DB files, and flips a SharedPreferences
 *     flag so the work runs at most once per installation. Idempotent and safe
 *     to call on every cold start.
 *
 *  2. **[clearLegacy]** — wipes the legacy databases and sets the migration-done
 *     flag. Used by `AppRepository.clearData` so that a "wipe everything" action
 *     also clears any pre-migration leftovers and prevents the migrator from
 *     resurrecting them on the next launch.
 *
 * The migrator does **not** know about caches or chats logic; it delegates
 * contact insertion through [insertContacts] (typically `AppRepository::
 * insertContactsWithChats`) so that the cache-write-through path is exercised
 * exactly as it is for live inserts.
 *
 * @param messagesDao the unified [MessageDao] — legacy messages are mapped via
 *        [MessageItem.toAppMessageEntity] and bulk-inserted directly.
 * @param insertContacts callback that inserts a batch of [FullContactData] into
 *        the new schema (typically wired to `AppRepository.insertContactsWithChats`).
 */
internal class LegacyMigrator(
    private val messagesDao: MessageDao,
    private val insertContacts: suspend (List<FullContactData>) -> Unit,
) {

    /**
     * Copies every row from the three legacy databases into the unified
     * [AppDatabase], then deletes the old database files.
     *
     * Guarded by [KEY_MIGRATION_DONE] in SharedPreferences so it runs exactly
     * once per installation. On failure the flag is **not** set — the next
     * cold start will retry. Safe to call repeatedly.
     */
    suspend fun migrate(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) return@withContext

        try {
            // ── Contacts ──────────────────────────────────────────────────
            val oldContactsDb = ContactsDatabase.getDatabase(context)
            val contacts: List<ChatContact> = oldContactsDb.contactsDao().getAllContact()
            if (contacts.isNotEmpty()) {
                val parsed: List<FullContactData> =
                    contacts.mapNotNull { it.toFullContactData() }
                insertContacts(parsed)
                Timber.d("Migration: copied ${contacts.size} contact(s)")
            }
            oldContactsDb.close()

            // ── Messages ──────────────────────────────────────────────────
            val oldMessagesDb = MessagesDatabase.getDatabase(context)
            val messages: List<MessageItem> = oldMessagesDb.messagesDao().getAllMessages()
            if (messages.isNotEmpty()) {
                messagesDao.addMessages(messages.map { it.toAppMessageEntity() })
                Timber.d("Migration: copied ${messages.size} message(s)")
            }
            oldMessagesDb.close()

            // ── Delete legacy database files ──────────────────────────────
            LEGACY_DATABASE_NAMES.forEach { context.deleteDatabase(it) }
            Timber.d("Migration: legacy databases deleted")

            // ── Mark done ────────────────────────────────────────────────
            prefs.edit { putBoolean(KEY_MIGRATION_DONE, true) }
            Timber.d("Migration: completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "Migration: failed — legacy data retained, will retry on next launch")
            // Do NOT set the flag — retry on next launch.
        }
    }

    /**
     * Clears every legacy database (tables + files) and sets the
     * migration-done flag so the migrator will not run again. Returns true
     * iff *every* step succeeded; partial failures still attempt the rest.
     */
    suspend fun clearLegacy(context: Context): Boolean = withContext(Dispatchers.IO) {
        var success = true

        runCatching {
            ChatsDatabase.getDatabase(context).also { db ->
                db.chatsDao().clearData()
                db.close()
            }
        }.onFailure { success = false }

        runCatching {
            ContactsDatabase.getDatabase(context).also { db ->
                db.contactsDao().clearData()
                db.close()
            }
        }.onFailure { success = false }

        runCatching {
            MessagesDatabase.getDatabase(context).also { db ->
                db.messagesDao().clearData()
                db.close()
            }
        }.onFailure { success = false }

        LEGACY_DATABASE_NAMES.forEach { dbName ->
            val deletedOrMissing = runCatching {
                val path = context.getDatabasePath(dbName)
                if (!path.exists()) true else context.deleteDatabase(dbName)
            }.getOrDefault(false)
            if (!deletedOrMissing) success = false
        }

        val flagUpdated = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_MIGRATION_DONE, true) }
            true
        }.getOrDefault(false)

        success && flagUpdated
    }

    companion object {
        private const val PREFS_NAME = "app_db_prefs"
        private const val KEY_MIGRATION_DONE = "migration_done"
        private val LEGACY_DATABASE_NAMES = listOf(
            "chats_database",
            "contacts_database",
            "messages_database",
        )
    }
}

