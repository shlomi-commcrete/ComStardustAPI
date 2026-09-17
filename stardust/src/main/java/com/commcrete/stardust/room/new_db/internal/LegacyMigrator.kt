package com.commcrete.stardust.room.new_db.internal


import android.content.Context.MODE_PRIVATE
import androidx.core.content.edit
import com.commcrete.stardust.room.legacy_db.ChatsDatabase
import com.commcrete.stardust.room.legacy_db.ContactsDatabase
import com.commcrete.stardust.room.legacy_db.MessagesDatabase
import com.commcrete.stardust.room.legacy_db.chats.ChatItem
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.legacy_db.messages.MessageItem
import com.commcrete.stardust.room.new_db.chat.ChatDao
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.MessageDao
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.util.DataManager
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
 *     A legacy database is only ever opened when its file already exists, and
 *     only the ones actually read are deleted. Both rules exist because these
 *     names are generic and the host's `databases/` directory is shared with
 *     every other ATAK plugin: opening an absent one would *create* a colliding
 *     file, and deleting an unreadable one could destroy another plugin's data.
 *     An empty legacy database — the usual state when the upgrade happened with
 *     no logged-in user — needs no copying and is simply removed.
 *
 *  2. **[clearLegacy]** — wipes the legacy databases and sets the migration-done
 *     flag. Used by `AppRepository.clearData` so that a "wipe everything" action
 *     also clears any pre-migration leftovers and prevents the migrator from
 *     resurrecting them on the next launch.
 *
 * # Chat-id translation
 *
 * The legacy schema addressed a chat by the peer's *communication id* — a bittel
 * / smartphone / group id stored directly in `messages_table.chatId`. The new
 * schema gives every chat a random UUID primary key ([ChatEntity.id]) and puts a
 * foreign key on `messages.chat_id`, so legacy ids can never be copied across
 * verbatim: every one of them would fail the constraint and abort the whole
 * migration. Messages are therefore re-pointed through
 * [ChatDao.findNewChatIdRowsByPreviousChatIds], which maps a legacy
 * communication id back to the chat that now owns it.
 *
 * A legacy id with no owner — the peer contact was deleted, or it never survived
 * [ChatContact.toFullContactData] — gets a placeholder contact (and therefore a
 * chat) synthesised from its `chats_table` row, so the history stays reachable
 * instead of being dropped on the floor.
 *
 * The migrator does **not** know about caches or chats logic; it delegates
 * contact insertion through [insertContacts] (typically `AppRepository::
 * insertContactsWithChats`) so that the cache-write-through path is exercised
 * exactly as it is for live inserts.
 *
 * @param messagesDao the unified [MessageDao] — legacy messages are mapped via
 *        [MessageItem.toAppMessageEntity] and bulk-inserted directly.
 * @param chatsDao the unified [ChatDao] — used only to resolve legacy chat ids
 *        to the new UUID keys.
 * @param insertContacts callback that inserts a batch of [FullContactData] into
 *        the new schema (typically wired to `AppRepository.insertContactsWithChats`).
 */
internal class LegacyMigrator(
    private val messagesDao: MessageDao,
    private val chatsDao: ChatDao,
    private val insertContacts: suspend (List<FullContactData>) -> Unit,
) {

    /**
     * Copies every row from the three legacy databases into the unified
     * [AppDatabase], then deletes the old database files.
     *
     * Guarded by [KEY_MIGRATION_DONE] in SharedPreferences so it runs exactly
     * once per installation. On failure the flag is **not** set — the next
     * cold start will retry. Safe to call repeatedly.
     *
     * Deliberately not wrapped in a single `withTransaction`: [insertContacts]
     * hops to [Dispatchers.IO] internally, and switching dispatchers inside a
     * Room transaction deadlocks against the transaction thread. Every write
     * below is an upsert keyed on stable ids, so a partial run is simply
     * repeated — not duplicated — on the next attempt.
     */
    suspend fun migrate() = withContext(Dispatchers.IO) {
        val context = DataManager.appContext
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) return@withContext

        // Nothing to migrate from. Covers a fresh install, and the common
        // upgrade case where there was no logged-in user so the legacy
        // databases were never created. Checked BEFORE opening anything:
        // Room creates the file on open, so an unguarded open would invent
        // three generic-named databases in the host's shared directory.
        if (LEGACY_DATABASE_NAMES.none { context.getDatabasePath(it).exists() }) {
            prefs.edit { putBoolean(KEY_MIGRATION_DONE, true) }
            Timber.d("Migration: no legacy database present — nothing to do")
            return@withContext
        }

        // Legacy databases we actually managed to read. Only these get deleted:
        // a file we could not open may belong to another plugin that picked the
        // same generic name, and deleting it would destroy their data.
        val consumed = mutableSetOf<String>()

        try {
            // ── Contacts ──────────────────────────────────────────────────
            val contacts: List<ChatContact> = readLegacy(CONTACTS_DB, consumed) {
                try {
                    ContactsDatabase.getDatabase().contactsDao().getAllContact()
                } finally {
                    ContactsDatabase.closeAndClear()
                }
            }.orEmpty()
            if (contacts.isNotEmpty()) {
                val parsed: List<FullContactData> =
                    contacts.mapNotNull { it.toFullContactData() }
                insertContacts(parsed)
                Timber.d("Migration: copied ${contacts.size} contact(s)")
            }

            // ── Chats ─────────────────────────────────────────────────────
            // Not copied as rows — chats are re-derived from contacts. Read
            // only for the name / image / kind of any chat that needs a
            // placeholder contact built for it below.
            val legacyChatsById: Map<String, ChatItem> = readLegacy(CHATS_DB, consumed) {
                try {
                    ChatsDatabase.getDatabase().chatsDao().getAllChats()
                } finally {
                    ChatsDatabase.closeAndClear()
                }
            }.orEmpty()
                .mapNotNull { item -> normalizeIdOrNull(item.chat_id)?.let { it to item } }
                .toMap()

            // ── Messages ──────────────────────────────────────────────────
            val messages: List<MessageItem> = readLegacy(MESSAGES_DB, consumed) {
                try {
                    MessagesDatabase.getDatabase().messagesDao().getAllMessages()
                } finally {
                    MessagesDatabase.closeAndClear()
                }
            }.orEmpty()

            if (messages.isNotEmpty()) {
                migrateMessages(messages, legacyChatsById)
            }

            // ── Delete the legacy database files we consumed ──────────────
            // An empty legacy database is the normal case for an upgrade with
            // no logged-in user: there is nothing to copy, so it is simply
            // removed here rather than left to be retried.
            deleteLegacyDatabases(consumed)

            // ── Mark done ────────────────────────────────────────────────
            prefs.edit { putBoolean(KEY_MIGRATION_DONE, true) }
            Timber.d("Migration: completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "Migration: failed — legacy data retained, will retry on next launch")
            // Do NOT set the flag — retry on next launch.
        }
    }

    /**
     * Reads one legacy database, or returns null without touching the
     * filesystem when its file is absent.
     *
     * A database that is absent counts as [consumed] — there is nothing to
     * delete. One that throws does **not**: an unreadable file under a generic
     * name is most likely another plugin's, so it is left exactly where it is
     * and the rest of the migration carries on without it.
     */
    private suspend fun <T> readLegacy(
        name: String,
        consumed: MutableSet<String>,
        read: suspend () -> T,
    ): T? {
        if (!DataManager.appContext.getDatabasePath(name).exists()) {
            consumed += name
            return null
        }
        return runCatching { read() }
            .onSuccess { consumed += name }
            .onFailure { Timber.e(it, "Migration: cannot read $name — leaving the file untouched") }
            .getOrNull()
    }

    /** Deletes the named legacy database files, logging anything that survives. */
    private fun deleteLegacyDatabases(names: Set<String>): Boolean {
        val context = DataManager.appContext
        var allGone = true
        names.forEach { dbName ->
            val gone = runCatching {
                val path = context.getDatabasePath(dbName)
                !path.exists() || context.deleteDatabase(dbName)
            }.getOrDefault(false)
            if (!gone) {
                allGone = false
                Timber.w("Migration: could not delete legacy database $dbName")
            }
        }
        if (allGone) Timber.d("Migration: legacy databases deleted")
        return allGone
    }

    /**
     * Maps legacy messages onto the new schema, re-pointing each one at the
     * chat that now owns its legacy communication id.
     *
     * Runs the resolution twice: once against the chats created from the
     * migrated contacts, then again after placeholder contacts have been minted
     * for whatever was still unaccounted for. The second pass names each
     * placeholder after its legacy chat id rather than its legacy chat *name*,
     * because `chats.name` carries a UNIQUE index — a name that collides with an
     * existing chat leaves the chat uncreated (see
     * [ChatDao.insertChatWithParticipants]) and the id unresolved.
     */
    private suspend fun migrateMessages(
        messages: List<MessageItem>,
        legacyChatsById: Map<String, ChatItem>,
    ) {
        val entities: List<MessageEntity> = messages.map { it.toAppMessageEntity() }
        val legacyChatIds: List<String> =
            entities.mapNotNull { normalizeIdOrNull(it.chatId) }.distinct()

        var resolved: Map<String, String> = resolveChatIds(legacyChatIds)

        // Pass 1 — placeholders named after the legacy chat, de-duplicated so
        // two chats sharing a name cannot knock each other out.
        val missingAfterContacts = legacyChatIds.filterNot { resolved.containsKey(it) }
        if (missingAfterContacts.isNotEmpty()) {
            val usedNames = mutableSetOf<String>()
            val placeholders = missingAfterContacts.mapNotNull { legacyChatId ->
                val legacyChat = legacyChatsById[legacyChatId]
                val name = legacyChat?.name?.trim()
                    ?.takeIf { it.isNotEmpty() && usedNames.add(it) }
                    ?: legacyChatId
                placeholderContact(legacyChatId, legacyChat, name)
            }
            if (placeholders.isNotEmpty()) {
                insertContacts(placeholders)
                Timber.d("Migration: created ${placeholders.size} placeholder contact(s)")
                resolved = resolveChatIds(legacyChatIds)
            }
        }

        // Pass 2 — anything still missing lost a `chats.name` race; retry under
        // the legacy chat id, which is unique by construction.
        val missingAfterPlaceholders = legacyChatIds.filterNot { resolved.containsKey(it) }
        if (missingAfterPlaceholders.isNotEmpty()) {
            val retries = missingAfterPlaceholders.mapNotNull { legacyChatId ->
                placeholderContact(legacyChatId, legacyChatsById[legacyChatId], legacyChatId)
            }
            if (retries.isNotEmpty()) {
                insertContacts(retries)
                resolved = resolveChatIds(legacyChatIds)
            }
        }

        val mapped = entities.mapNotNull { entity ->
            val legacyChatId = normalizeIdOrNull(entity.chatId)
            when {
                // A message that never belonged to a chat stays chat-less:
                // the foreign key is satisfied by NULL.
                legacyChatId == null -> entity.copy(chatId = null)
                else -> resolved[legacyChatId]?.let { entity.copy(chatId = it) }
            }
        }

        val dropped = entities.size - mapped.size
        if (dropped > 0) {
            Timber.w("Migration: dropped $dropped message(s) — chat id could not be resolved")
        }

        mapped.chunked(MESSAGE_INSERT_CHUNK).forEach { messagesDao.addMessages(it) }
        Timber.d("Migration: copied ${mapped.size} message(s)")
    }

    /**
     * Legacy communication id -> new chat id, for the ids that have an owner.
     * Chunked to stay under SQLite's bound-variable limit; the underlying query
     * returns newest-first, so the first row wins for an id owned by more than
     * one chat.
     */
    private suspend fun resolveChatIds(legacyChatIds: List<String>): Map<String, String> {
        if (legacyChatIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        legacyChatIds.chunked(SQLITE_VARIABLE_LIMIT).forEach { chunk ->
            chatsDao.findNewChatIdRowsByPreviousChatIds(chunk).forEach { row ->
                if (!out.containsKey(row.previousId)) out[row.previousId] = row.chatId
            }
        }
        return out
    }

    /**
     * Builds the contact that will own [legacyChatId], typed from the legacy
     * chat row when one survives and defaulting to a user contact otherwise.
     */
    private fun placeholderContact(
        legacyChatId: String,
        legacyChat: ChatItem?,
        name: String,
    ): FullContactData? {
        val image = legacyChat?.imageName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            legacyChat?.isGroup == true ->
                FullContactData.createGroupContact(name, image, legacyChatId)

            legacyChat?.isBittel == true ->
                FullContactData.createDeviceContact(name, image, legacyChatId)

            else -> FullContactData.createUserContact(name, image, legacyChatId)
        }
    }

    /**
     * Clears every legacy database (tables + files) and sets the
     * migration-done flag so the migrator will not run again. Returns true
     * iff *every* step succeeded; partial failures still attempt the rest.
     */
    suspend fun clearLegacy(): Boolean = withContext(Dispatchers.IO) {
        val appContext = DataManager.appContext
        var success = true

        // Deleting the file is what actually clears these, and it works whether
        // or not the schema is readable. Emptying the tables first is only so a
        // handle another caller is already holding sees no rows. Both steps are
        // skipped for a database that does not exist — opening one would create
        // the very file this function is meant to remove.
        if (appContext.getDatabasePath(CHATS_DB).exists()) {
            runCatching {
                ChatsDatabase.getDatabase().chatsDao().clearData()
            }.onFailure { Timber.w(it, "clearLegacy: could not empty $CHATS_DB") }
            ChatsDatabase.closeAndClear()
        }

        if (appContext.getDatabasePath(CONTACTS_DB).exists()) {
            runCatching {
                ContactsDatabase.getDatabase().contactsDao().clearData()
            }.onFailure { Timber.w(it, "clearLegacy: could not empty $CONTACTS_DB") }
            ContactsDatabase.closeAndClear()
        }

        if (appContext.getDatabasePath(MESSAGES_DB).exists()) {
            runCatching {
                MessagesDatabase.getDatabase().messagesDao().clearData()
            }.onFailure { Timber.w(it, "clearLegacy: could not empty $MESSAGES_DB") }
            MessagesDatabase.closeAndClear()
        }

        if (!deleteLegacyDatabases(LEGACY_DATABASE_NAMES.toSet())) success = false

        val flagUpdated = runCatching {
            appContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit { putBoolean(KEY_MIGRATION_DONE, true) }
            true
        }.getOrDefault(false)

        success && flagUpdated
    }

    companion object {
        private const val PREFS_NAME = "app_db_prefs"
        private const val KEY_MIGRATION_DONE = "migration_done"

        /** SQLite's default SQLITE_MAX_VARIABLE_NUMBER is 999; stay clear of it. */
        private const val SQLITE_VARIABLE_LIMIT = 900

        /** Batch size for the bulk message insert — bounds peak statement size. */
        private const val MESSAGE_INSERT_CHUNK = 500

        private const val CHATS_DB = "chats_database"
        private const val CONTACTS_DB = "contacts_database"
        private const val MESSAGES_DB = "messages_database"

        private val LEGACY_DATABASE_NAMES = listOf(CHATS_DB, CONTACTS_DB, MESSAGES_DB)
    }
}