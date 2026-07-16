package com.commcrete.stardust.contacts

/**
 * The single source of truth for contact-conflict rules: detection of which
 * existing contacts an incoming [ContactDraft] collides with, and resolution of
 * a chosen [ContactResolution] into a deterministic list of [ContactOperation]s.
 *
 * Pure — no Android, no DB, no coroutines — so it is trivially unit-testable
 * and shared by every contacts flow (single add/edit and bulk file import).
 */
object ContactConflictEngine {

    /**
     * Finds the existing contact (if any) that owns each identity field of
     * [incoming]. Only non-blank fields are considered; blanks never match.
     * [candidates] is the set to compare against — callers decide what goes in
     * it (all contacts, minus self on edit, plus the other incoming rows on a
     * bulk import, etc.).
     */
    fun detect(incoming: ContactDraft, candidates: List<ContactDraft>): ContactConflicts =
        ContactConflicts(
            callsignOwner = incoming.name.takeIf { it.isNotBlank() }
                ?.let { n -> candidates.firstOrNull { it.name.equals(n, ignoreCase = true) } },
            appIdOwner = incoming.appId.takeIf { it.isNotBlank() }
                ?.let { id -> candidates.firstOrNull { it.appId.equals(id, ignoreCase = true) } },
            deviceIdOwner = incoming.deviceId.takeIf { it.isNotBlank() }
                ?.let { id -> candidates.firstOrNull { it.deviceId.equals(id, ignoreCase = true) } },
        )

    /**
     * Turns a resolved conflict into DB operations: any owner mutations implied
     * by the [resolution] (Swap strips/deletes/renames the owner) followed by
     * the upsert of the incoming contact with its applied values.
     */
    fun resolve(
        incoming: ContactDraft,
        conflicts: ContactConflicts,
        resolution: ContactResolution,
    ): List<ContactOperation> {
        val applied = incoming.copy(
            name = resolution.incomingName,
            appId = if (ConflictField.APP_ID in conflicts.fields && resolution.appId == FieldChoice.KEEP) "" else incoming.appId,
            deviceId = if (ConflictField.DEVICE_ID in conflicts.fields && resolution.deviceId == FieldChoice.KEEP) "" else incoming.deviceId,
        )

        val ops = mutableListOf<ContactOperation>()
        // Owner mutations, merged per owner so several moved fields collapse
        // into one update (or a delete, if it strips the owner's last identity).
        val updates = LinkedHashMap<String, Pair<ContactDraft, ContactDraft>>()
        fun key(d: ContactDraft) = "${d.type}|${d.appId}|${d.deviceId}|${d.name}"
        fun mutate(owner: ContactDraft, transform: (ContactDraft) -> ContactDraft) {
            val k = key(owner)
            updates[k] = owner to transform(updates[k]?.second ?: owner)
        }

        // A swapped App ID / Device ID is stripped from the existing owner so no
        // two contacts share it; the owner is deleted below only if that leaves
        // it with no identity at all.
        if (ConflictField.APP_ID in conflicts.fields && resolution.appId == FieldChoice.SWAP) {
            conflicts.appIdOwner?.let { mutate(it) { d -> d.copy(appId = "") } }
        }
        if (ConflictField.DEVICE_ID in conflicts.fields && resolution.deviceId == FieldChoice.SWAP) {
            conflicts.deviceIdOwner?.let { mutate(it) { d -> d.copy(deviceId = "") } }
        }
        // Callsign owner renamed (when the resolution assigns it a new name).
        val ownerName = resolution.ownerName
        val callsignOwner = conflicts.callsignOwner
        if (ConflictField.CALLSIGN in conflicts.fields && callsignOwner != null &&
            ownerName != null && ownerName.isNotBlank() && ownerName != callsignOwner.name
        ) {
            mutate(callsignOwner) { d -> d.copy(name = ownerName) }
        }

        updates.values.forEach { (original, updated) ->
            when {
                updated == original -> Unit
                updated.appId.isBlank() && updated.deviceId.isBlank() -> ops += ContactOperation.DeleteExisting(original)
                else -> ops += ContactOperation.UpdateExisting(original, updated)
            }
        }
        ops += ContactOperation.Insert(applied)
        return ops
    }

    /**
     * Appends a `" (n)"` suffix to [name], or increments an existing one:
     * `"Charlie"` → `"Charlie (2)"`, `"Charlie (2)"` → `"Charlie (3)"`.
     */
    fun appendSuffix(name: String): String {
        val match = Regex("""^(.*?)\s*\((\d+)\)\s*$""").find(name)
        return if (match != null) {
            val base = match.groupValues[1].trimEnd()
            val n = match.groupValues[2].toInt()
            "$base (${n + 1})"
        } else {
            "$name (2)"
        }
    }
}
