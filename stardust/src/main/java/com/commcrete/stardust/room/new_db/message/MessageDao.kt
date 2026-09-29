package com.commcrete.stardust.room.new_db.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

/**
 *
 * State ints: SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5,
 * CANCELLED=6.
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

    /** One row by id. Backs the read-modify-write of [markFileTransferFailed]. */
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    /**
     * Marks an outgoing attachment row FAILED, merging the failure reason into its
     * existing extra_data. Returns the number of rows written (0 = refused).
     *
     * Guarded on what must NOT be overwritten rather than on the state the row is
     * expected to be in: a transfer that settled — SEEN/RECEIVED by the peer, archived,
     * or stopped by the user — beats a failure declared for it afterwards. Naming the
     * expected state instead would make the write match nothing whenever anything else
     * touched the row first, and the failure would silently vanish.
     *
     * `COALESCE(:extraData, extra_data)` leaves the blob alone when the caller could
     * not parse an attachment out of it: losing the reason is better than losing the
     * fact of the failure.
     *
     * epoch_time_ms moves with the state, inside the same guarded statement so it can
     * only move when the failure itself takes. The row was stamped when the send
     * started; giving up on it is news that arrives now, and left at the start time a
     * failure declared minutes later would sort above everything sent while it tried.
     */
    @Query("""
        UPDATE messages
        SET extra_data = COALESCE(:extraData, extra_data),
            state = 3,
            epoch_time_ms = :nowMs
        WHERE id = :messageId
          AND state NOT IN (1, 2, 5, 6)
    """)
    suspend fun markFileTransferFailed(
        messageId: Long,
        extraData: MessageExtraData?,
        nowMs: Long,
    ): Int

    /**
     * Settles an INCOMING attachment row that arrived: state RECEIVED, with the path and
     * summary of the file that landed merged into its extra_data. Returns the number of
     * rows written (0 = refused).
     *
     * Same guard as [markFileTransferFailed], for the same reason. `epoch_time_ms` is
     * deliberately NOT moved: the row was stamped when the first package arrived and that
     * is where the message belongs in the conversation — unlike a failure, the arrival is
     * the event the row already stands for, so moving it would drag a slow transfer below
     * everything that came in while it ran.
     */
    @Query("""
        UPDATE messages
        SET extra_data = COALESCE(:extraData, extra_data),
            state = 2
        WHERE id = :messageId
          AND state NOT IN (1, 2, 5, 6)
    """)
    suspend fun markIncomingTransferReceived(
        messageId: Long,
        extraData: MessageExtraData?,
    ): Int

    /**
     * Marks an INCOMING attachment row FAILED, merging the failure reason into its
     * existing extra_data. Returns the number of rows written (0 = refused).
     *
     * Separate from [markFileTransferFailed] only over the timestamp, which this one
     * leaves alone: the receive side's row is already positioned at the moment the
     * transfer started arriving, and a transfer that dies is not new news arriving now —
     * it is the same message, settled. Restamping it would reorder the conversation
     * around a message the reader has been watching in place.
     */
    @Query("""
        UPDATE messages
        SET extra_data = COALESCE(:extraData, extra_data),
            state = 3
        WHERE id = :messageId
          AND state NOT IN (1, 2, 5, 6)
    """)
    suspend fun markIncomingTransferFailed(
        messageId: Long,
        extraData: MessageExtraData?,
    ): Int

    // ── Interrupted transfers (startup sweep) ────────────────────────────

    /**
     * Rows still marked RECEIVING that no live transfer can account for, because they were
     * stamped before [cutoffMs] — which the sweep sets to before this process built the
     * repository. Only the process that created an in-flight row can finalize it (the
     * finalizer is a receiver held in memory), so one that predates this process is
     * orphaned by definition, not slow.
     *
     * Returns whole rows because the sweep has to look at each one's extra_data to decide
     * what it became; there are normally none, and one or two after a crash.
     */
    @Query("SELECT * FROM messages WHERE state = 4 AND epoch_time_ms < :cutoffMs")
    suspend fun getStaleInFlight(cutoffMs: Long): List<MessageEntity>

    /**
     * Settles one swept row. Guarded on RECEIVING — narrower than the guard the transfer
     * paths use — because the sweep acts on a row it read a moment earlier and must lose to
     * anything that has touched it since. `epoch_time_ms` is never moved: the message keeps
     * the place in the conversation it has had all along.
     */
    @Query("""
        UPDATE messages
        SET extra_data = COALESCE(:extraData, extra_data),
            state = :state
        WHERE id = :messageId
          AND state = 4
    """)
    suspend fun settleStaleInFlight(
        messageId: Int,
        state: MessageState,
        extraData: MessageExtraData?,
    ): Int

    /**
     * Drops an in-flight row outright. Guarded on RECEIVING so it can only ever remove a
     * transfer that never settled: used when a sender restarts a transfer and the retry
     * takes over, where the abandoned attempt is not history and should leave nothing
     * behind. Returns the number of rows deleted (0 = the row had already settled).
     */
    @Query("DELETE FROM messages WHERE id = :messageId AND state = 4")
    suspend fun deleteInFlightMessage(messageId: Long): Int

    /**
     * Marks an outgoing attachment row CANCELLED, merging how far the send had got into
     * its existing extra_data. Returns the number of rows written (0 = refused).
     *
     * Same guard and same timestamp reasoning as [markFileTransferFailed]: a row the
     * peer already saw, or that was archived, is not rewritten, and re-cancelling a
     * cancelled row is a no-op. A row still in flight (SENT/RECEIVING) or already failed
     * is rewritten — the user's own stop is the more accurate account of what happened.
     */
    @Query("""
        UPDATE messages
        SET extra_data = COALESCE(:extraData, extra_data),
            state = 6,
            epoch_time_ms = :nowMs
        WHERE id = :messageId
          AND state NOT IN (1, 2, 5, 6)
    """)
    suspend fun markFileSendCancelled(
        messageId: Long,
        extraData: MessageExtraData?,
        nowMs: Long,
    ): Int

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

    // ── SOS acknowledgement ──────────────────────────────────────────────

    /**
     * The newest SOS [senderId] sent into [chatId], or null when there is none.
     *
     * Scoped to rows this user SENT because that is the only kind an ack can belong to:
     * an SOS you received is someone else's, and its acks go to them. Archived rows (5)
     * are excluded — an ack is not a reason to resurrect one.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chat_id = :chatId
          AND sender_id = :senderId
          AND type = :type
          AND state != 5
        ORDER BY epoch_time_ms DESC, id DESC
        LIMIT 1
    """)
    suspend fun findLatestSosSentInChat(
        chatId: String,
        senderId: String,
        type: MessageType,
    ): MessageEntity?

    /**
     * The newest SOS [senderId] sent into ANY chat that [participantContactId] belongs
     * to, or null when there is none.
     *
     * The fallback for a group SOS whose ack comes back addressed to this user directly
     * instead of through the group: the ack then resolves to a private chat, which is not
     * where the SOS row sits. Matching through chat membership finds the group SOS the
     * acker could actually have seen.
     */
    @Query("""
        SELECT m.* FROM messages AS m
        INNER JOIN chat_participants AS p ON p.chat_id = m.chat_id
        WHERE m.sender_id = :senderId
          AND m.type = :type
          AND p.contact_id = :participantContactId
          AND m.state != 5
        ORDER BY m.epoch_time_ms DESC, m.id DESC
        LIMIT 1
    """)
    suspend fun findLatestSosSentToParticipant(
        senderId: String,
        participantContactId: Int,
        type: MessageType,
    ): MessageEntity?

    /**
     * Appends an acknowledgement to an SOS row's extra_data. Returns the number of rows
     * written (0 = refused).
     *
     * `COALESCE(:extraData, extra_data)` for the same reason as
     * [markFileTransferFailed]: a blob the caller could not parse is left alone rather
     * than blanked. `state` and `epoch_time_ms` are deliberately NOT touched — the row
     * still stands for the moment the SOS went out, and an ack is something that
     * happened TO it, not a new position in the conversation.
     */
    @Query("""
        UPDATE messages
        SET extra_data = COALESCE(:extraData, extra_data)
        WHERE id = :messageId
          AND state != 5
    """)
    suspend fun recordSosAck(messageId: Long, extraData: MessageExtraData?): Int

    // ── Media relocation ─────────────────────────────────────────────────

    /**
     * Every row of the given types, archived ones included: a path that no
     * longer resolves is just as broken in an archived message as in a live
     * one. Used only by the media-relocation rewrite, which needs the whole
     * table once — hence no chat/lane filtering and no paging.
     */
    @Query("SELECT * FROM messages WHERE type IN (:types)")
    suspend fun getMessagesByTypes(types: List<MessageType>): List<MessageEntity>

    /**
     * Replaces one row's extra_data wholesale. Unguarded, unlike
     * [markFileTransferFailed] — the caller has just read the row, rewrites
     * nothing but the path inside it, and runs at startup before a transfer
     * could be in flight against it.
     */
    @Query("UPDATE messages SET extra_data = :extraData WHERE id = :messageId")
    suspend fun updateExtraData(messageId: Int, extraData: MessageExtraData?): Int

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