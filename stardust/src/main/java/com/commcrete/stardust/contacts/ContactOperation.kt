package com.commcrete.stardust.contacts

/**
 * A single DB-level change produced by [ContactConflictEngine.resolve] and
 * applied by `AppRepository.applyContactOperations`.
 *
 * Draft-based so the resolution layer stays free of AAR types; the repository
 * turns each op into the matching `FullContactData` factory call at apply time.
 */
sealed class ContactOperation {

    /** Insert [contact] as a new row (REPLACE upsert handles the edit case). */
    data class Insert(val contact: ContactDraft) : ContactOperation()

    /** Replace the existing record [original] with [updated] (same primary key). */
    data class UpdateExisting(val original: ContactDraft, val updated: ContactDraft) : ContactOperation()

    /** Remove the existing record entirely (e.g. it surrendered its only identifier). */
    data class DeleteExisting(val target: ContactDraft) : ContactOperation()

    /** No DB change. */
    object Noop : ContactOperation()
}
