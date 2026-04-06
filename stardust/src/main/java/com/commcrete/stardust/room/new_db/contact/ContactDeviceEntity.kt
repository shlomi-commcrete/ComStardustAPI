package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.new_db.chat.ContactEntity

@Entity(
    tableName = "app_contact_devices",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contact_id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["device_id"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["contact_id", "slot"], unique = true),
        Index(value = ["contact_id"]),
    ]
)
data class ContactDeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "slot")
    val slot: Int = 0,
    @ColumnInfo(name = "assigned_at")
    val assignedAt: Long = System.currentTimeMillis(),
)

