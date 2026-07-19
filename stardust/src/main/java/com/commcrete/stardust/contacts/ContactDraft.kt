package com.commcrete.stardust.contacts

import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.FullContactData

/**
 * Flat, always-constructible editing/domain model for a contact.
 *
 * This is the single in-memory representation used across the contacts feature
 * — editing buffers, conflict detection and resolution all speak [ContactDraft]
 * instead of each carrying their own mirror of [FullContactData]. Conversion to
 * the AAR write-model happens only at the persistence boundary via
 * [toFullContactData]; conversion in happens via [fromFullContactData].
 *
 * Unlike [FullContactData] (whose factories reject partial input) a draft can
 * hold half-typed / invalid state, so the UI can bind to it directly.
 *
 * @property type the *declared* kind. The effective persisted kind may differ:
 *   a USER draft with no App ID but a Device ID persists as a DEVICE contact
 *   (see [effectiveType]).
 */
data class ContactDraft(
    val name: String = "",
    /** userId for USER, groupId for GROUP, blank for pure DEVICE. */
    val appId: String = "",
    val deviceId: String = "",
    val model: String = "",
    val serial: String = "",
    val iconKey: String = "",
    val type: ContactType = ContactType.USER,
) {
    val hasAppId: Boolean get() = appId.isNotBlank()
    val hasDeviceId: Boolean get() = deviceId.isNotBlank()

    /** The kind this draft would actually be persisted as, or `null` if it has no identity. */
    fun effectiveType(): ContactType? = when (type) {
        ContactType.GROUP -> if (hasAppId) ContactType.GROUP else null
        else -> when {
            hasAppId -> ContactType.USER
            hasDeviceId -> ContactType.DEVICE
            else -> null
        }
    }

    /**
     * Builds the AAR write-model, applying USER→DEVICE promotion. Returns
     * `null` when the draft has no usable identity or the AAR factory rejects it.
     */
    fun toFullContactData(): FullContactData? = when (effectiveType()) {
        ContactType.USER -> FullContactData.createUserContact(
            name = name, image = iconKey, userId = appId,
            deviceId = deviceId.ifBlank { null }, model = model.ifBlank { null }, serial = serial.ifBlank { null },
        )
        ContactType.GROUP -> FullContactData.createGroupContact(name = name, image = iconKey, groupId = appId)
        ContactType.DEVICE -> FullContactData.createDeviceContact(
            name = name, image = iconKey, deviceId = deviceId,
            model = model.ifBlank { null }, serial = serial.ifBlank { null },
        )
        null -> null
    }

    /** `true` when both drafts denote the same stored record (all identity fields equal). */
    fun sameIdentityAs(other: ContactDraft): Boolean =
        type == other.type &&
            name.equals(other.name, ignoreCase = true) &&
            appId.equals(other.appId, ignoreCase = true) &&
            deviceId.equals(other.deviceId, ignoreCase = true)

    /**
     * `true` when both drafts share every identity field (type + appId + deviceId)
     * but the name may differ — the "same contact, callsign changed" case that
     * should be handled as an in-place rename rather than a duplicate insert.
     */
    fun sameIdentityIgnoringName(other: ContactDraft): Boolean =
        type == other.type &&
            appId.equals(other.appId, ignoreCase = true) &&
            deviceId.equals(other.deviceId, ignoreCase = true)

    companion object {
        fun fromFullContactData(contact: FullContactData): ContactDraft {
            val entity = contact.contact
            return when (contact) {
                is FullContactData.User -> {
                    val device = contact.devices.firstOrNull()
                    ContactDraft(
                        name = entity.name,
                        appId = contact.userId,
                        deviceId = device?.id.orEmpty(),
                        model = device?.model.orEmpty(),
                        serial = device?.serial.orEmpty(),
                        iconKey = entity.image.orEmpty(),
                        type = ContactType.USER,
                    )
                }
                is FullContactData.Group -> ContactDraft(
                    name = entity.name, appId = contact.groupId,
                    iconKey = entity.image.orEmpty(), type = ContactType.GROUP,
                )
                is FullContactData.Device -> ContactDraft(
                    name = entity.name, deviceId = contact.deviceId,
                    model = contact.deviceData.model.orEmpty(), serial = contact.deviceData.serial.orEmpty(),
                    iconKey = entity.image.orEmpty(), type = ContactType.DEVICE,
                )
            }
        }
    }
}
