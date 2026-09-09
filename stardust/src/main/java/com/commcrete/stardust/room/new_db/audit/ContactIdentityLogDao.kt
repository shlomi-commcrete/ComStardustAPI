package com.commcrete.stardust.room.new_db.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for [ContactIdentityLogEntity]. Writes are append-only —
 * there is deliberately no update or single-row delete; the only removal path is
 * [prune] (by age) and `clearAllTables()`.
 *
 * `kind` is stored as its enum name (see `Converters.EnumConverter`), which is
 * why the filtered queries below compare against string literals.
 */
@Dao
interface ContactIdentityLogDao {

    // ── Writes ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun log(row: ContactIdentityLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun log(rows: List<ContactIdentityLogEntity>): List<Long>

    // ── Reads ────────────────────────────────────────────────────────────

    /** Newest [limit] rows, newest first. */
    @Query("SELECT * FROM contact_identity_log ORDER BY at_ms DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<ContactIdentityLogEntity>

    /** Live variant of [recent], for a diagnostics screen. */
    @Query("SELECT * FROM contact_identity_log ORDER BY at_ms DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ContactIdentityLogEntity>>

    /**
     * Full ownership history of one identifier, oldest first. [idValue] must be
     * normalized (trimmed + lower-cased) — pass `messages.sender_id` straight in.
     */
    @Query(
        """
        SELECT * FROM contact_identity_log
        WHERE id_value = :idValue
        ORDER BY at_ms ASC, id ASC
        """
    )
    suspend fun historyForId(idValue: String): List<ContactIdentityLogEntity>

    /**
     * The last ownership event for [idValue] at or before [atMs] — the answer to
     * "which contact owned this id when that message arrived".
     *
     * Interpret the result by [ContactIdentityLogEntity.kind]:
     *  - `ASSIGNED` / `MOVED` → `to_contact_id` (and `to_name`) owned it.
     *  - `STRIPPED` → nobody owned it at that moment.
     *  - `null` → no recorded change; the id has been owned by the same contact
     *    since before logging began (or the row was pruned).
     */
    @Query(
        """
        SELECT * FROM contact_identity_log
        WHERE id_value = :idValue
          AND at_ms <= :atMs
          AND kind IN ('ASSIGNED', 'MOVED', 'STRIPPED')
        ORDER BY at_ms DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun ownershipAt(idValue: String, atMs: Long): ContactIdentityLogEntity?

    /** Everything that ever touched [contactId], on either side of a change. */
    @Query(
        """
        SELECT * FROM contact_identity_log
        WHERE from_contact_id = :contactId OR to_contact_id = :contactId
        ORDER BY at_ms ASC, id ASC
        """
    )
    suspend fun historyForContact(contactId: Int): List<ContactIdentityLogEntity>

    /**
     * Every row written by one `applyContactOperations` call, in write order —
     * used to reconstruct a strip/assign pair as a single move.
     */
    @Query(
        """
        SELECT * FROM contact_identity_log
        WHERE batch_id = :batchId
        ORDER BY id ASC
        """
    )
    suspend fun batch(batchId: Long): List<ContactIdentityLogEntity>

    /**
     * Chats that messages were moved out of, newest first. Explains a chat whose
     * history appears to stop, or one that suddenly contains older messages.
     */
    @Query(
        """
        SELECT * FROM contact_identity_log
        WHERE kind = 'MESSAGES_REPARENTED'
          AND (from_chat_id = :chatId OR to_chat_id = :chatId)
        ORDER BY at_ms DESC, id DESC
        """
    )
    suspend fun reparentsForChat(chatId: String): List<ContactIdentityLogEntity>

    // ── Maintenance ──────────────────────────────────────────────────────

    /** Drops rows older than [cutoffMs]. Returns the number deleted. */
    @Query("DELETE FROM contact_identity_log WHERE at_ms < :cutoffMs")
    suspend fun prune(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM contact_identity_log")
    suspend fun count(): Int
}