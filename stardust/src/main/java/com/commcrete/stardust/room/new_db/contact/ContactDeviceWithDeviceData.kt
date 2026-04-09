package com.commcrete.stardust.room.new_db.contact

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Contact device mapping with full device data.
 * Combines ContactDeviceEntity (mapping data) with the full DeviceEntity.
 */
data class ContactDeviceWithDeviceData(
    @Embedded
    val contactDevice: ContactDeviceEntity,

    @Relation(
        parentColumn = "device_id",
        entityColumn = "device_id"
    )
    val device: DeviceEntity? = null,
)

