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

    /**
     * Rename the existing contact matching [target]'s identity, and its
     * associated chat, to [newName]. Used for the "same ids, different callsign"
     * case — updates the contact in place instead of creating a duplicate.
     */
    data class RenameExisting(val target: ContactDraft, val newName: String) : ContactOperation()

    /**
     * Remove the existing record entirely.
     *
     * If [reparentTo] is non-null (a contact that is being inserted in the same
     * op batch), the source contact's chat messages are re-parented to that
     * contact's chat **before** the source is deleted — so message history
     * survives the swap. FK cascades then drop the source's id/device mappings
     * and chat participants when the contact row is removed.
     */
    data class DeleteExisting(
        val target: ContactDraft,
        val reparentTo: ContactDraft? = null,
    ) : ContactOperation()

    /** No DB change. */
    object Noop : ContactOperation()
}
