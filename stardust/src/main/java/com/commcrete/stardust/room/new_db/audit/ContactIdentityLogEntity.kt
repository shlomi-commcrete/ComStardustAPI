package com.commcrete.stardust.room.new_db.audit

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only audit trail of contact-identity ownership changes.
 *
 * Deliberately **not** foreign-keyed to `contacts` or `chats`: the whole point of
 * the row is to outlive the contact and chat it describes, so a swap that empties
 * a contact out and deletes it still leaves an explanation behind. `from_*` /
 * `to_*` columns therefore hold plain snapshots — an id that no longer resolves
 * is expected, not a bug.
 *
 * Rows are written by [com.commcrete.stardust.room.new_db.internal.IdentityLogRecorder]
 * and never updated. `clearAllTables()` (logout / `AppRepository.clearData`) drops
 * them along with everything else; otherwise prune by age via
 * [ContactIdentityLogDao.prune].
 *
 * @property atMs wall-clock time of the change. Comparable against
 *   `messages.epoch_time_ms`, which is what makes "who owned this id when this
 *   message arrived" answerable.
 * @property idValue the affected identifier, **normalized** (trimmed + lower-cased)
 *   exactly like the identity tables and `messages.sender_id`, so the two join
 *   directly. Null for [IdentityChangeKind.RENAMED] and
 *   [IdentityChangeKind.MESSAGES_REPARENTED], which are not about one id.
 * @property batchId groups every row written by one
 *   `AppRepository.applyContactOperations` call. A `STRIPPED` row and an
 *   `ASSIGNED` row sharing a `batch_id` and an `id_value` are the two halves of
 *   one move through conflict resolution.
 * @property affectedRows row count reported by the underlying statement, where it
 *   has one (currently `MESSAGES_REPARENTED`). Null when not applicable.
 */
@Entity(
    tableName = "contact_identity_log",
    indices = [
        Index(value = ["id_value"]),
        Index(value = ["at_ms"]),
        Index(value = ["from_contact_id"]),
        Index(value = ["to_contact_id"]),
        Index(value = ["batch_id"]),
    ],
)
data class ContactIdentityLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "at_ms")
    val atMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "kind")
    val kind: IdentityChangeKind,

    @ColumnInfo(name = "id_kind")
    val idKind: IdentityKind? = null,

    @ColumnInfo(name = "id_value")
    val idValue: String? = null,

    @ColumnInfo(name = "from_contact_id")
    val fromContactId: Int? = null,

    @ColumnInfo(name = "to_contact_id")
    val toContactId: Int? = null,

    @ColumnInfo(name = "from_name")
    val fromName: String? = null,

    @ColumnInfo(name = "to_name")
    val toName: String? = null,

    @ColumnInfo(name = "from_chat_id")
    val fromChatId: String? = null,

    @ColumnInfo(name = "to_chat_id")
    val toChatId: String? = null,

    @ColumnInfo(name = "affected_rows")
    val affectedRows: Int? = null,

    @ColumnInfo(name = "source")
    val source: String = IdentityLogSource.UNKNOWN,

    @ColumnInfo(name = "batch_id")
    val batchId: Long? = null,
)
