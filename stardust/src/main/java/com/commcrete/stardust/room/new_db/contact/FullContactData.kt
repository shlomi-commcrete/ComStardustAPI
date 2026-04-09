package com.commcrete.stardust.room.new_db.contact

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Full contact data including all related identifiers and devices.
 *
 * Supported combinations:
 * 1. groupId only (GROUP contacts)
 * 2. userId only (USER contacts)
 * 3. devices only (DEVICE contacts)
 * 4. userId + devices (USER contacts with associated devices)
 */
data class FullContactData(
    @Embedded
    val contact: ContactEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "contact_id"
    )
    val userId: ContactUserIdEntity? = null,

    @Relation(
        parentColumn = "id",
        entityColumn = "contact_id"
    )
    val groupId: ContactGroupIdEntity? = null,

    @Relation(
        parentColumn = "id",
        entityColumn = "contact_id"
    )
    val devices: List<ContactDeviceWithDeviceData> = emptyList(),
)

