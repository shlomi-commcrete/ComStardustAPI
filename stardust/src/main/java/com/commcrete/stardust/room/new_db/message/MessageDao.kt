package com.commcrete.stardust.room.new_db.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

// SeenStatus int values: SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5

@Dao
interface MessageDao {

    /** (chatId, targetId, count) row used by bulk unseen-count observers. */
    data class UnseenCountRow(
        @ColumnInfo(name = "chat_id") val chatId: String,
        @ColumnInfo(name = "target_id") val targetId: String,
        @ColumnInfo(name = "unseen_count") val unseenCount: Int,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessages(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND state != 5
        ORDER BY epoch_time_ms ASC
    """)
    fun getAllMessagesByChatId(chatId: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND sender_id = :senderId AND state != 5
        ORDER BY epoch_time_ms ASC
    """)
    fun getChatMessagesBySenderId(chatId: String, senderId: String): Flow<List<MessageEntity>>


    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId AND state != 5 
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epoch_time_ms DESC
        LIMIT :limit
    """)
    fun getMessagesByChatInRange(
        chatId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE sender_id = :senderId AND state != 5
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY epoch_time_ms DESC
        LIMIT :limit
    """)
    fun getMessagesBySenderInRange(
        senderId: String,
        startTimestamp: Long,
        endTimestamp: Long,
        limit: Int = 50
    ): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun clearChatMessages(chatId: String)

    /**
     * Re-parents every message from [oldChatId] to [newChatId]. Used when a
     * contact is being deleted after surrendering its last identifier to a new
     * incoming contact — we move the message history to the new contact's chat
     * before the old chat is dropped, so history survives the swap.
     * Returns the number of messages moved.
     */
    @Query("UPDATE messages SET chat_id = :newChatId WHERE chat_id = :oldChatId")
    suspend fun reassignChat(oldChatId: String, newChatId: String): Int

    @Query("DELETE FROM messages WHERE sender_id = :senderId")
    suspend fun clearSenderMessages(senderId: String)

    @Query("""
        DELETE FROM messages
        WHERE chat_id = :chatId
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
    """)
    suspend fun clearChatInRange(chatId: String, startTimestamp: Long, endTimestamp: Long)

    @Query("UPDATE messages SET state = :state WHERE id = :messageId")
    suspend fun updateMessageState(messageId: Long, state: MessageState)

    @Query("UPDATE messages SET state = 5 WHERE id = :messageId AND state != 5")
    suspend fun archiveMessage(messageId: Int): Int

    @Query("UPDATE messages SET state = 5 WHERE id IN (:messageIds) AND state != 5")
    suspend fun archiveMessages(messageIds: List<Int>): Int

    @Query("UPDATE messages SET state = 5 WHERE epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp")
    suspend fun archiveAllMessages(startTimestamp: Long, endTimestamp: Long): Int

    /**
     * Marks every RECEIVED (2) and RECEIVING (4) message in [chatId] as SEEN (1).
     * Call when the user opens a chat.
     * Every [ChatSummaryDao.getAllChats] observer re-emits with unseenCount = 0
     * for that chat automatically.
     */
    @Query("""
        UPDATE messages
        SET state = 1
        WHERE chat_id = :chatId AND state IN (2, 4)
    """)
    suspend fun markAllMessagesAsSeen(chatId: String)

    /**
     * Marks as SEEN every RECEIVED message in [chatId] whose timestamp
     * is ≤ [untilEpochMs].  Useful for "read up to here" semantics.
     */
    @Query("""
        UPDATE messages
        SET state = 1
        WHERE chat_id = :chatId
          AND state  IN (2, 4)
          AND epoch_time_ms <= :untilEpochMs
    """)
    suspend fun markMessagesAsSeenUntil(chatId: String, untilEpochMs: Long)

    /** Archive all messages in a time range by setting state = ARCHIVED (5). */
    @Query("""
        UPDATE messages
        SET state = 5
        WHERE chat_id = :chatId
        AND epoch_time_ms BETWEEN :startTimestamp AND :endTimestamp
    """)
    suspend fun archiveMessagesInRange(chatId: String?, startTimestamp: Long, endTimestamp: Long)

    @Query("DELETE FROM messages")
    fun clearData()

    // ── Live feed ─────────────────────────────────────────────────────────

    /**
     * Emits the [limit] most-recent non-archived messages in [chatId],
     * ordered chronologically (oldest first). Re-emits on every insert/update/delete
     * in the messages table — use for both group and private chats.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    fun getLatestMessages(chatId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * Same as [getLatestMessages] but only includes messages where [participantId]
     * is the sender OR receiver. Use for the filtered view in private chats.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (sender_id = :participantId OR receiver_id = :participantId)
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    fun getLatestMessagesByParticipant(
        chatId: String,
        participantId: String,
        limit: Int,
    ): Flow<List<MessageEntity>>

    // ── Pagination ────────────────────────────────────────────────────────

    /**
     * Returns the [limit] messages that come just before [beforeEpochMs],
     * ordered chronologically. Call when the user scrolls to the top of the
     * chat to prepend the next page to the existing list.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND epoch_time_ms < :beforeEpochMs
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    suspend fun loadOlderMessages(
        chatId: String,
        beforeEpochMs: Long,
        limit: Int,
    ): List<MessageEntity>

    /**
     * Same as [loadOlderMessages] but scoped to [participantId] as sender or receiver.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (sender_id = :participantId OR receiver_id = :participantId)
              AND epoch_time_ms < :beforeEpochMs
            ORDER BY epoch_time_ms DESC
            LIMIT :limit
        ) ORDER BY epoch_time_ms ASC
    """)
    suspend fun loadOlderMessagesByParticipant(
        chatId: String,
        participantId: String,
        beforeEpochMs: Long,
        limit: Int,
    ): List<MessageEntity>

    // ── Direct OFFSET-based pagination ───────────────────────────────────

    /**
     * Returns the [limit] non-archived messages on the requested page (0-based)
     * for [chatId], ordered oldest-first within the page.
     * Equivalent to walking [loadOlderMessages] N times, but in a single query.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
            ORDER BY epoch_time_ms DESC, id DESC
            LIMIT :limit OFFSET :offset
        ) ORDER BY epoch_time_ms ASC, id ASC
    """)
    suspend fun loadPageForChat(
        chatId: String,
        limit: Int,
        offset: Int,
    ): List<MessageEntity>

    /**
     * Same as [loadPageForChat] but only counts messages where [participantId]
     * is sender or receiver.
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (sender_id = :participantId OR receiver_id = :participantId)
            ORDER BY epoch_time_ms DESC, id DESC
            LIMIT :limit OFFSET :offset
        ) ORDER BY epoch_time_ms ASC, id ASC
    """)
    suspend fun loadPageForTarget(
        chatId: String,
        participantId: String,
        limit: Int,
        offset: Int,
    ): List<MessageEntity>

    /**
     * Page of messages in [chatId] where one of sender/receiver appears in [userIds].
     * Use to scope a chat to the registered user (and any of their identities).
     */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE chat_id = :chatId AND state != 5
              AND (sender_id IN (:userIds) OR receiver_id IN (:userIds))
            ORDER BY epoch_time_ms DESC, id DESC
            LIMIT :limit OFFSET :offset
        ) ORDER BY epoch_time_ms ASC, id ASC
    """)
    suspend fun loadPageForChatScopedToUsers(
        chatId: String,
        userIds: List<String>,
        limit: Int,
        offset: Int,
    ): List<MessageEntity>

    // ── Unseen counters ──────────────────────────────────────────────────

    /**
     * Reactive unseen count for [chatId]. Re-emits whenever any message in that
     * chat is inserted, marked seen, archived, or deleted.
     *
     * "Unseen" = state IN (RECEIVED=2, RECEIVING=4).
     * Uses the (chat_id, state, epoch_time_ms) index — runs as an index-only
     * COUNT on hot calls.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE chat_id = :chatId AND state IN (2, 4)
    """)
    fun observeUnseenCountForChat(chatId: String): Flow<Int>

    /**
     * Reactive count of every message across all chats currently in state
     * RECEIVED (2) — i.e. delivered but not yet marked SEEN. Re-emits
     * whenever any message is inserted, updated, or deleted.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE state = 2
    """)
    fun observeReceivedMessageCount(): Flow<Int>

    /**
     * Reactive unseen count for [chatId] scoped to messages where [targetId]
     * is the sender OR receiver. Useful when a chat is "shared" between
     * multiple participants and you want a per-target badge.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE chat_id = :chatId
          AND state IN (2, 4)
          AND (sender_id = :targetId OR receiver_id = :targetId)
    """)
    fun observeUnseenCountForTarget(chatId: String, targetId: String): Flow<Int>

    /**
     * Bulk reactive unseen counts. Single Flow that emits rows
     * (chatId, targetId, count) for every (chatId, targetId) pair where
     * `chatId IN :chatIds` and `targetId IN :targetIds`.
     *
     * One COUNT query per emission for the whole list — much cheaper than
     * N independent [observeUnseenCountForTarget] subscriptions.
     */
    @Query("""
        SELECT chat_id AS chat_id, t AS target_id, COUNT(*) AS unseen_count
        FROM (
            SELECT chat_id, sender_id   AS t FROM messages
            WHERE state IN (2, 4) AND chat_id IN (:chatIds) AND sender_id   IN (:targetIds)
            UNION ALL
            SELECT chat_id, receiver_id AS t FROM messages
            WHERE state IN (2, 4) AND chat_id IN (:chatIds) AND receiver_id IN (:targetIds)
        )
        GROUP BY chat_id, t
    """)
    fun observeUnseenCountsForTargets(
        chatIds: List<String>,
        targetIds: List<String>,
    ): Flow<List<UnseenCountRow>>

    /**
     * Reactive unseen count across every chat in [chatIds].
     * Single COUNT — emits a Map-friendly row list.
     */
    @Query("""
        SELECT chat_id AS chat_id, '' AS target_id, COUNT(*) AS unseen_count
        FROM messages
        WHERE state IN (2, 4) AND chat_id IN (:chatIds)
        GROUP BY chat_id
    """)
    fun observeUnseenCountsForChats(chatIds: List<String>): Flow<List<UnseenCountRow>>

    /**
     * Resolve message sender to contact by checking both user_id and device_id.
     * Returns contact_id if sender is found in either identity table.
     */
    @Query(
        """
        SELECT COALESCE(u.contact_id, d.contact_id) AS contactId
        FROM messages m
        LEFT JOIN app_contact_user_ids u
               ON u.user_id = m.sender_id
        LEFT JOIN app_contact_devices d
               ON d.device_id = m.sender_id
        WHERE m.id = :messageId
          AND (u.contact_id IS NOT NULL OR d.contact_id IS NOT NULL)
        LIMIT 1
        """
    )
    suspend fun getMessageSenderContactId(messageId: Int): Int?


    /**
     * Find all messages from a specific contact (by either user_id or device_id).
     */
    @Query(
        """
        SELECT m.*
        FROM messages m
        WHERE sender_id IN (
            SELECT u.user_id
            FROM app_contact_user_ids u
            WHERE u.contact_id = :contactId
            UNION
            SELECT d.device_id
            FROM app_contact_devices d
            WHERE d.contact_id = :contactId
        )
        ORDER BY m.epoch_time_ms DESC
        """
    )
    suspend fun getMessagesBySender(contactId: Int): List<MessageEntity>
}
