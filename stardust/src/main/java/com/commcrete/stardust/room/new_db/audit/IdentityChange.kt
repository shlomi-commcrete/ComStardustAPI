package com.commcrete.stardust.room.new_db.audit

/**
 * What kind of identity change a [ContactIdentityLogEntity] row records.
 *
 * The identity tables (`app_contact_user_ids`, `app_contact_group_ids`,
 * `app_contact_devices`) are written with `OnConflictStrategy.REPLACE`, so a
 * transfer of ownership silently deletes the previous mapping row. Once that has
 * happened there is no way to tell, after the fact, which contact owned an id
 * when a given message arrived — messages are anchored to `chat_id` and carry
 * `sender_id` / `receiver_id` only as frozen text. These rows are that record.
 */
enum class IdentityChangeKind {
    /** An id that had no owner is now mapped to a contact. */
    ASSIGNED,

    /** An id changed owner: `from_contact_id` lost it, `to_contact_id` gained it. */
    MOVED,

    /** An id's mapping was removed and not handed to anyone in the same step. */
    STRIPPED,

    /** A contact (and usually its chat) was renamed; no id changed hands. */
    RENAMED,

    /** A contact row was deleted; FK cascades dropped its mappings and participants. */
    CONTACT_DELETED,

    /**
     * `messages.chat_id` was rewritten in bulk from `from_chat_id` to
     * `to_chat_id`; `affected_rows` carries the row count actually moved.
     */
    MESSAGES_REPARENTED,

    /** Written by a newer version of the app than the one reading the row. */
    UNKNOWN,
}

/** Which identity table [ContactIdentityLogEntity.idValue] belongs to. */
enum class IdentityKind {
    USER_ID,
    GROUP_ID,
    DEVICE_ID,
}

/**
 * Free-form provenance tag for a log row — which flow caused the change.
 *
 * Kept as a `String` rather than an enum so the host app can pass its own tags
 * without an AAR release; the constants here are the ones the library writes.
 */
object IdentityLogSource {
    /** User-resolved conflict applied via `AppRepository.applyContactOperations`. */
    const val CONFLICT_RESOLUTION = "conflict_resolution"

    /** Bulk roster insert at login (`insertContactsWithChats`). */
    const val LOGIN_IMPORT = "login_import"

    /** Contact created implicitly by the message-save pipeline for an unknown id. */
    const val AUTO_CREATE = "auto_create"

    /** Migration of the legacy database into `new_db`. */
    const val LEGACY_MIGRATION = "legacy_migration"

    /** Source not stated by the caller. */
    const val UNKNOWN = "unknown"
}
