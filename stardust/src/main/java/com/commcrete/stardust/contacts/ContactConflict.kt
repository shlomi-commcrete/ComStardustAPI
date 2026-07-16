package com.commcrete.stardust.contacts

/** The three identity fields a contact can collide on. */
enum class ConflictField { CALLSIGN, APP_ID, DEVICE_ID }

/**
 * Outcome for a single conflicting field:
 *  - [KEEP] the existing owner keeps the field; the incoming contact is saved
 *    without it (callsign → a renamed/numbered value).
 *  - [SWAP] the incoming contact takes the field; the existing owner loses it
 *    (renamed, stripped, or deleted).
 */
enum class FieldChoice { KEEP, SWAP }

/**
 * Which existing contact (if any) owns each conflicting field of an incoming
 * [ContactDraft]. Produced by [ContactConflictEngine.detect].
 */
data class ContactConflicts(
    val callsignOwner: ContactDraft? = null,
    val appIdOwner: ContactDraft? = null,
    val deviceIdOwner: ContactDraft? = null,
) {
    val fields: Set<ConflictField> = buildSet {
        if (callsignOwner != null) add(ConflictField.CALLSIGN)
        if (appIdOwner != null) add(ConflictField.APP_ID)
        if (deviceIdOwner != null) add(ConflictField.DEVICE_ID)
    }

    val isEmpty: Boolean get() = fields.isEmpty()

    fun ownerOf(field: ConflictField): ContactDraft? = when (field) {
        ConflictField.CALLSIGN -> callsignOwner
        ConflictField.APP_ID -> appIdOwner
        ConflictField.DEVICE_ID -> deviceIdOwner
    }
}

/**
 * The user's chosen resolution for a conflicting incoming contact — expressed
 * as *outcomes*, not UI gestures, so both flows (per-field Keep/Swap in the
 * contact editor, and free-form callsign rename in the bulk loader) map onto it.
 *
 * @property appId / [deviceId] Keep-with-owner vs Swap-to-incoming.
 * @property incomingName the final callsign for the incoming contact.
 * @property ownerName    the final callsign for the callsign owner, or `null`
 *   to leave it unchanged.
 */
data class ContactResolution(
    val appId: FieldChoice = FieldChoice.KEEP,
    val deviceId: FieldChoice = FieldChoice.KEEP,
    val incomingName: String,
    val ownerName: String? = null,
)
