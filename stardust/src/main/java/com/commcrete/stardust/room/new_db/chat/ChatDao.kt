package com.commcrete.stardust.room.new_db.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.ColumnInfo
import com.commcrete.stardust.room.new_db.message.MessageType
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    /** (previousId -> resolved chatId) row produced by bulk previous-id lookup. */
    data class PreviousChatIdRow(
        @ColumnInfo(name = "previous_id") val previousId: String,
        @ColumnInfo(name = "chat_id") val chatId: String,
    )

    /** (chatId, messageType, count) row for unseen messages of the split-out types. */
    data class ChatTypeUnseenRow(
        @ColumnInfo(name = "chatId") val chatId: String,
        @ColumnInfo(name = "messageType") val messageType: MessageType,
        @ColumnInfo(name = "count") val count: Int,
    )

    // ── Single chat ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChats(chats: List<ChatEntity>): List<Long>

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Upsert
    suspend fun upsertChat(chat: ChatEntity): Long

    @Upsert
    suspend fun upsertChats(chats: List<ChatEntity>)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String): Int

    /** Renames a chat in place (used when a contact's callsign changes). */
    @Query("UPDATE chats SET name = :name WHERE id = :chatId")
    suspend fun renameChatById(chatId: String, name: String): Int

    // ── Chat + participants (transactional) ──────────────────────────────

    @Transaction
    suspend fun insertChatWithParticipants(chat: ChatEntity, participantIds: List<Int>) {
        upsertChat(chat)
        // `chats.name` is UNIQUE: when a chat with this name already exists under a different id,
        // the upsert-by-primary-key above matches nothing and chat.id is never stored. Linking
        // participants to that missing id would violate the chat_id foreign key (SQLITE_CONSTRAINT
        // 787) and crash. Only wire participants when the chat we intended to create is actually
        // present — otherwise a chat by this name already exists and there is nothing to create,
        // so leave its participants untouched rather than clobbering them.
        if (getChatByChatId(chat.id) != null) {
            replaceParticipants(chat.id, participantIds)
        }
    }

    @Transaction
    suspend fun insertChatsWithParticipants(chats: List<Pair<ChatEntity, List<Int>>>) {
        chats.forEach { (chat, participantIds) ->
            insertChatWithParticipants(chat, participantIds)
        }
    }

    // ── Participants ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addParticipant(ref: ChatParticipantEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addParticipants(refs: List<ChatParticipantEntity>)

    @Query("DELETE FROM chat_participants WHERE chat_id = :chatId")
    suspend fun clearParticipants(chatId: String)

    @Transaction
    suspend fun replaceParticipants(chatId: String, participantIds: List<Int>) {
        clearParticipants(chatId)
        if (participantIds.isNotEmpty()) {
            addParticipants(
                participantIds
                    .distinct()
                    .map { participantId ->
                        ChatParticipantEntity(chatId = chatId, contactId = participantId)
                    }
            )
        }
    }

    // ── Chat summaries ───────────────────────────────────────────────────
    //
    // ChatSummary is a plain query-result POJO (no longer a @DatabaseView). The projection below is
    // the single source of that SQL, parameterized by the message types to exclude from BOTH the
    // last-message pick and the unread count. Passing an empty list excludes nothing (`NOT IN ()`
    // is always true in SQLite), so callers that don't split types just pass emptyList().
    //
    // State ints — SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5.

    /** Whole chats list, newest last-message first. [excludedTypes] = message types to hide. */
    @Query("""
        SELECT
            c.id                                            AS chatId,
            c.type                                          AS chatType,
            c.name                                          AS name,
            c.image                                         AS image,
            lm.type                                         AS lastMessageType,
            lm.state                                        AS lastMessageState,
            lm.extra_data                                   AS lastMessageExtraData,
            lm.sender_id                                    AS lastSenderId,
            COALESCE(ct.name, lm.sender_id)                 AS lastSenderName,
            lm.epoch_time_ms                                AS lastMessageEpochMs,
            COALESCE(unseen.unseen_count, 0)                AS unseenCount
        FROM chats c
        LEFT JOIN (
            SELECT m.chat_id, m.type, m.state, m.extra_data, m.sender_id, m.epoch_time_ms
            FROM messages m
            JOIN (
                SELECT chat_id, MAX(epoch_time_ms) AS max_epoch_time_ms
                FROM messages
                WHERE state != 5 AND type NOT IN (:excludedTypes)
                GROUP BY chat_id
            ) latest
                ON latest.chat_id = m.chat_id
               AND latest.max_epoch_time_ms = m.epoch_time_ms
            WHERE m.type NOT IN (:excludedTypes)
        ) lm
            ON lm.chat_id = c.id
        LEFT JOIN app_contact_user_ids u
            ON u.user_id = lm.sender_id
        LEFT JOIN app_contact_devices d
            ON d.device_id = lm.sender_id
        LEFT JOIN contacts ct
            ON ct.id = COALESCE(u.contact_id, d.contact_id)
        LEFT JOIN (
            SELECT chat_id, COUNT(*) AS unseen_count
            FROM messages
            WHERE state IN (2) AND type NOT IN (:excludedTypes)
            GROUP BY chat_id
        ) unseen
            ON unseen.chat_id = c.id
        ORDER BY lastMessageEpochMs DESC
    """)
    fun getChatSummaries(excludedTypes: List<MessageType>): Flow<List<ChatSummary>>

    /** Single-chat summary (e.g. an in-chat header). Same projection, filtered to one chat. */
    @Query("""
        SELECT
            c.id                                            AS chatId,
            c.type                                          AS chatType,
            c.name                                          AS name,
            c.image                                         AS image,
            lm.type                                         AS lastMessageType,
            lm.state                                        AS lastMessageState,
            lm.extra_data                                   AS lastMessageExtraData,
            lm.sender_id                                    AS lastSenderId,
            COALESCE(ct.name, lm.sender_id)                 AS lastSenderName,
            lm.epoch_time_ms                                AS lastMessageEpochMs,
            COALESCE(unseen.unseen_count, 0)                AS unseenCount
        FROM chats c
        LEFT JOIN (
            SELECT m.chat_id, m.type, m.state, m.extra_data, m.sender_id, m.epoch_time_ms
            FROM messages m
            JOIN (
                SELECT chat_id, MAX(epoch_time_ms) AS max_epoch_time_ms
                FROM messages
                WHERE state != 5 AND type NOT IN (:excludedTypes)
                GROUP BY chat_id
            ) latest
                ON latest.chat_id = m.chat_id
               AND latest.max_epoch_time_ms = m.epoch_time_ms
            WHERE m.type NOT IN (:excludedTypes)
        ) lm
            ON lm.chat_id = c.id
        LEFT JOIN app_contact_user_ids u
            ON u.user_id = lm.sender_id
        LEFT JOIN app_contact_devices d
            ON d.device_id = lm.sender_id
        LEFT JOIN contacts ct
            ON ct.id = COALESCE(u.contact_id, d.contact_id)
        LEFT JOIN (
            SELECT chat_id, COUNT(*) AS unseen_count
            FROM messages
            WHERE state IN (2) AND type NOT IN (:excludedTypes)
            GROUP BY chat_id
        ) unseen
            ON unseen.chat_id = c.id
        WHERE c.id = :chatId
        LIMIT 1
    """)
    fun getChatSummary(chatId: String, excludedTypes: List<MessageType>): Flow<ChatSummary?>

    /**
     * Live unseen (state RECEIVED = 2) counts for the split-out [splitTypes], grouped by chat and
     * type. Drives the per-type "new X received" indicators in the chats list. Empty [splitTypes]
     * yields no rows.
     */
    @Query("""
        SELECT chat_id AS chatId, type AS messageType, COUNT(*) AS count
        FROM messages
        WHERE state = 2 AND type IN (:splitTypes)
        GROUP BY chat_id, type
    """)
    fun observeUnseenCountsBySplitType(splitTypes: List<MessageType>): Flow<List<ChatTypeUnseenRow>>

    // ── Other chat queries ───────────────────────────────────────────────

    @Query("SELECT id FROM chats WHERE type = 'GROUP'")
    suspend fun getAllGroupChatIds(): List<String>

    @Query("""
        SELECT c.id FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE c.type = 'PRIVATE' AND cp.contact_id = :contactId
        LIMIT 1
    """)
    suspend fun findPrivateChatIdByContactId(contactId: Int): String?

    @Query("""
        SELECT c.id FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE c.type = 'GROUP' AND cp.contact_id = :contactId
        LIMIT 1
    """)
    suspend fun findGroupChatIdByContactId(contactId: Int): String?


    @Query("""
        SELECT c.id
        FROM chats c
        JOIN chat_participants cp ON cp.chat_id = c.id
        WHERE (
            c.type = 'GROUP'
            AND cp.contact_id IN (
                SELECT cg.contact_id
                FROM app_contact_group_ids cg
                WHERE cg.group_id = :previousChatId
            )
        )
        OR (
            c.type = 'PRIVATE'
            AND cp.contact_id IN (
                SELECT cu.contact_id
                FROM app_contact_user_ids cu
                WHERE cu.user_id = :previousChatId
            )
        )
        OR (
            c.type = 'PRIVATE'
            AND cp.contact_id IN (
                SELECT cd.contact_id
                FROM app_contact_devices cd
                WHERE cd.device_id = :previousChatId
            )
        )
        ORDER BY c.last_updated_ms DESC
        LIMIT 1
    """)
    suspend fun findNewChatIdByPreviousChatId(previousChatId: String): String?

    /**
     * Bulk version of [findNewChatIdByPreviousChatId]. Resolves multiple legacy
     * IDs in one query. Caller maps each input position back via the returned rows.
     * Lower-cased / trimmed IDs are expected (both inputs and stored data).
     */
    @Query("""
        SELECT prev.previous_id AS previous_id, prev.chat_id AS chat_id
        FROM (
            SELECT cg.group_id AS previous_id, cp.chat_id AS chat_id, c.last_updated_ms AS lu
            FROM app_contact_group_ids cg
            JOIN chat_participants cp ON cp.contact_id = cg.contact_id
            JOIN chats c ON c.id = cp.chat_id AND c.type = 'GROUP'
            UNION ALL
            SELECT cu.user_id, cp.chat_id, c.last_updated_ms
            FROM app_contact_user_ids cu
            JOIN chat_participants cp ON cp.contact_id = cu.contact_id
            JOIN chats c ON c.id = cp.chat_id AND c.type = 'PRIVATE'
            UNION ALL
            SELECT cd.device_id, cp.chat_id, c.last_updated_ms
            FROM app_contact_devices cd
            JOIN chat_participants cp ON cp.contact_id = cd.contact_id
            JOIN chats c ON c.id = cp.chat_id AND c.type = 'PRIVATE'
        ) prev
        WHERE prev.previous_id IN (:previousChatIds)
        ORDER BY prev.lu DESC
    """)
    suspend fun findNewChatIdRowsByPreviousChatIds(previousChatIds: List<String>): List<PreviousChatIdRow>

    @Query("SELECT id FROM chats")
    suspend fun getAllChatIds(): List<String>

    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatByChatId(chatId: String): ChatEntity?

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatWithParticipants(chatId: String): ChatWithParticipants?

    @Transaction
    @Query("SELECT * FROM chats ORDER BY last_updated_ms DESC")
    fun getAllChatsWithParticipants(): Flow<List<ChatWithParticipants>>
}