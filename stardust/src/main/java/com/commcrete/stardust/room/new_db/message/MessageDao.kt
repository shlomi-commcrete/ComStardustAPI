package com.commcrete.stardust.room.new_db.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

/**
 *
 * State ints: SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5.
 *
 * Optional params use Room's null-or-IN idiom:
 *  - types / excludeTypes: null = no filter. Never pass an empty list.
 *  - participantId: null = chat-scoped (group lanes); non-null = target-scoped.
 *
 * UNSEEN = state 2 only. RECEIVING (4) is an in-flight transfer, not an unread
 * message, and marking it seen would abort the transfer. Counting and marking
 * therefore use the same predicate — the asymmetry was a stuck-badge bug.
 *
 */
@Dao
interface MessageDao {

    data class UnseenCountRow(
        @ColumnInfo(name = "chat_id") val chatId: String,
        @ColumnInfo(name = "target_id") val targetId: String,
        @ColumnInfo(name = "unseen_count") val unseenCount: Int,
    )

    data class ChatUnseenCountRow(
        @ColumnInfo(name = "chat_id") val chatId: String,
        @ColumnInfo(name = "unseen_count") val unseenCount: Int,
    )

    // ── Insert ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessages(messages: List<MessageEntity>): List<Long>

    // ── Live feed ────────────────────────────────────────────────────────

    /** Newest [limit] rows, returned oldest-first. */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (:participantId IS NULL
                   OR sender_id = :participantId OR receiver_id = :participantId)
              AND (:types IS NULL OR type IN (:types))
              AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
            ORDER BY epoch_time_ms DESC, id DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC, id ASC
    """)
    fun observeLatest(
        chatId: String,
        participantId: String?,
        limit: Int,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<List<MessageEntity>>

    // ── Keyset pagination ────────────────────────────────────────────────

    /** Strictly older than (beforeEpochMs, beforeId). Null = newest page. */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (:participantId IS NULL
                   OR sender_id = :participantId OR receiver_id = :participantId)
              AND (:types IS NULL OR type IN (:types))
              AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
              AND (:beforeEpochMs IS NULL
                   OR epoch_time_ms < :beforeEpochMs
                   OR (epoch_time_ms = :beforeEpochMs AND id < :beforeId))
            ORDER BY epoch_time_ms DESC, id DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC, id ASC
    """)
    suspend fun loadOlder(
        chatId: String,
        participantId: String?,
        beforeEpochMs: Long?,
        beforeId: Int?,
        limit: Int,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity>

    /** Strictly newer than (afterEpochMs, afterId). */
    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND state != 5
          AND (:participantId IS NULL
               OR sender_id = :participantId OR receiver_id = :participantId)
          AND (:types IS NULL OR type IN (:types))
          AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
          AND (epoch_time_ms > :afterEpochMs
               OR (epoch_time_ms = :afterEpochMs AND id > :afterId))
        ORDER BY epoch_time_ms ASC, id ASC
        LIMIT :limit
    """)
    suspend fun loadNewer(
        chatId: String,
        participantId: String?,
        afterEpochMs: Long,
        afterId: Int,
        limit: Int,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity>

    /** Inclusive of (fromEpochMs, fromId) — the anchor half of loadAround. */
    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND state != 5
          AND (:participantId IS NULL
               OR sender_id = :participantId OR receiver_id = :participantId)
          AND (:types IS NULL OR type IN (:types))
          AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
          AND (epoch_time_ms > :fromEpochMs
               OR (epoch_time_ms = :fromEpochMs AND id >= :fromId))
        ORDER BY epoch_time_ms ASC, id ASC
        LIMIT :limit
    """)
    suspend fun loadAtOrNewer(
        chatId: String,
        participantId: String?,
        fromEpochMs: Long,
        fromId: Int,
        limit: Int,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): List<MessageEntity>

    // ── Anchoring ────────────────────────────────────────────────────────

    /**
     * Oldest still-unseen row. Returns the whole entity so callers can scroll to
     * it and render the "N new" divider without a second lookup — that is why no
     * separate cursor type exists.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND state = 2
          AND (:participantId IS NULL
               OR sender_id = :participantId OR receiver_id = :participantId)
          AND (:types IS NULL OR type IN (:types))
          AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
        ORDER BY epoch_time_ms ASC, id ASC
        LIMIT 1
    """)
    suspend fun findFirstUnseen(
        chatId: String,
        participantId: String?,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): MessageEntity?

    // ── Unseen counters (state = 2 only) ─────────────────────────────────

    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE state = 2
          AND (:types IS NULL OR type IN (:types))
          AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
    """)
    fun observeReceivedMessagesCount(
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<Int>

    /** Per-chat. Use for GROUP chats: the group target is synthetic. */
    @Query("""
        SELECT chat_id AS chat_id, COUNT(*) AS unseen_count
        FROM messages
        WHERE state = 2
          AND chat_id IN (:chatIds)
          AND (:types IS NULL OR type IN (:types))
          AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
        GROUP BY chat_id
    """)
    fun observeUnseenCountsForChats(
        chatIds: List<String>,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<List<ChatUnseenCountRow>>

    /**
     * Per (chat, sender). PRIVATE chats only — targets are matched against
     * sender_id because on any inbound message receiver_id is the registered
     * user, in private and group chats alike.
     */
    @Query("""
        SELECT chat_id AS chat_id, sender_id AS target_id, COUNT(*) AS unseen_count
        FROM messages
        WHERE state = 2
          AND chat_id IN (:chatIds)
          AND sender_id IN (:targetIds)
          AND (:types IS NULL OR type IN (:types))
          AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
        GROUP BY chat_id, sender_id
    """)
    fun observeUnseenCountsForTargets(
        chatIds: List<String>,
        targetIds: List<String>,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Flow<List<UnseenCountRow>>

    // ── State transitions ────────────────────────────────────────────────

    @Query("UPDATE messages SET state = :state WHERE id = :messageId")
    suspend fun updateMessageState(messageId: Long, state: MessageState)

    /**
     * The only seen-marking write. Bidirectional range, so it serves both
     * "entered at the bottom and scrolled up" and "anchored and scrolled down".
     * from == to marks one row. participantId must be supplied for target-scoped
     * lanes or the range would also mark another target's messages.
     */
    @Query("""
        UPDATE messages SET state = 1
        WHERE chat_id = :chatId AND state = 2
          AND (:participantId IS NULL
               OR sender_id = :participantId OR receiver_id = :participantId)
          AND (epoch_time_ms > :fromEpochMs
               OR (epoch_time_ms = :fromEpochMs AND id >= :fromId))
          AND (epoch_time_ms < :toEpochMs
               OR (epoch_time_ms = :toEpochMs AND id <= :toId))
          AND (:types IS NULL OR type IN (:types))
          AND (:excludeTypes IS NULL OR type NOT IN (:excludeTypes))
    """)
    suspend fun markSeenInRange(
        chatId: String,
        participantId: String?,
        fromEpochMs: Long,
        fromId: Int,
        toEpochMs: Long,
        toId: Int,
        types: List<MessageType>? = null,
        excludeTypes: List<MessageType>? = null,
    ): Int

    // ── Archival ─────────────────────────────────────────────────────────

    @Query("UPDATE messages SET state = 5 WHERE id = :messageId AND state != 5")
    suspend fun archiveMessage(messageId: Int): Int

    @Query("UPDATE messages SET state = 5 WHERE id IN (:messageIds) AND state != 5")
    suspend fun archiveMessages(messageIds: List<Int>): Int

    @Query("UPDATE messages SET state = 5 WHERE epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp")
    suspend fun archiveAllMessages(startTimestamp: Long, endTimestamp: Long): Int

    // ── Deletion & re-parenting ──────────────────────────────────────────

    @Query("UPDATE messages SET chat_id = :newChatId WHERE chat_id = :oldChatId")
    suspend fun reassignChat(oldChatId: String, newChatId: String): Int

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun clearChatMessages(chatId: String)

    @Query("""
        DELETE FROM messages
        WHERE chat_id = :chatId
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
    """)
    suspend fun clearChatInRange(chatId: String, startTimestamp: Long, endTimestamp: Long)
}