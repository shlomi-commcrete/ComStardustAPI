package com.commcrete.stardust.contacts

import com.commcrete.stardust.room.new_db.contact.ContactType

/** Result of running a [ContactDraft] through [ContactDraftValidator]. */
sealed class ContactValidation {
    object Valid : ContactValidation()
    data class Invalid(val reason: Reason, val field: Field? = null) : ContactValidation()

    enum class Reason { NAME_REQUIRED, NAME_TOO_SHORT, ID_REQUIRED, ID_NOT_HEX, ILLEGAL_CHARACTERS }
    enum class Field { NAME, APP_ID, DEVICE_ID, MODEL, SERIAL }
}

/**
 * Pure validation for a [ContactDraft] — no Android, no view touches. Rules
 * mirror the legacy form validator, fail-fast in order:
 *  1. name present + min length
 *  2. per-type id presence
 *  3. id format (hex-8)
 *  4. SQL-injection heuristic over every populated free-text field
 */
object ContactDraftValidator {

    const val NAME_MIN_LENGTH = 2
    const val ID_HEX_LENGTH = 8

    private val HEX_ID_REGEX = Regex("^[0-9a-fA-F]{$ID_HEX_LENGTH}$")
    private val SQL_INJECTION_REGEX = Regex(
        pattern = """(--|;|/\*|\*/|\bxp_|\b(select|insert|update|delete|drop|alter|truncate|union|exec|execute)\b)""",
        option = RegexOption.IGNORE_CASE,
    )

    fun isValidHexId(value: String): Boolean = HEX_ID_REGEX.matches(value)
    fun looksLikeSqlInjection(value: String): Boolean = SQL_INJECTION_REGEX.containsMatchIn(value)

    fun validate(draft: ContactDraft): ContactValidation {
        if (draft.name.isEmpty()) return ContactValidation.Invalid(ContactValidation.Reason.NAME_REQUIRED, ContactValidation.Field.NAME)
        if (draft.name.length < NAME_MIN_LENGTH) return ContactValidation.Invalid(ContactValidation.Reason.NAME_TOO_SHORT, ContactValidation.Field.NAME)

        when (draft.type) {
            ContactType.USER -> if (!draft.hasAppId && !draft.hasDeviceId)
                return ContactValidation.Invalid(ContactValidation.Reason.ID_REQUIRED, ContactValidation.Field.APP_ID)
            ContactType.GROUP -> if (!draft.hasAppId)
                return ContactValidation.Invalid(ContactValidation.Reason.ID_REQUIRED, ContactValidation.Field.APP_ID)
            ContactType.DEVICE -> if (!draft.hasDeviceId)
                return ContactValidation.Invalid(ContactValidation.Reason.ID_REQUIRED, ContactValidation.Field.DEVICE_ID)
        }

        if (draft.hasAppId && !isValidHexId(draft.appId))
            return ContactValidation.Invalid(ContactValidation.Reason.ID_NOT_HEX, ContactValidation.Field.APP_ID)
        if (draft.hasDeviceId && !isValidHexId(draft.deviceId))
            return ContactValidation.Invalid(ContactValidation.Reason.ID_NOT_HEX, ContactValidation.Field.DEVICE_ID)

        val fields = listOf(
            ContactValidation.Field.NAME to draft.name,
            ContactValidation.Field.APP_ID to draft.appId,
            ContactValidation.Field.DEVICE_ID to draft.deviceId,
            ContactValidation.Field.MODEL to draft.model,
            ContactValidation.Field.SERIAL to draft.serial,
        )
        for ((field, value) in fields) {
            if (value.isNotEmpty() && looksLikeSqlInjection(value))
                return ContactValidation.Invalid(ContactValidation.Reason.ILLEGAL_CHARACTERS, field)
        }
        return ContactValidation.Valid
    }
}
