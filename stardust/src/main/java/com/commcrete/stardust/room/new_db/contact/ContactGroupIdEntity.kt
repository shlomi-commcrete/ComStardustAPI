package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_contact_group_ids",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["contact_id"], unique = true),
    ]
)
data class ContactGroupIdEntity(
    @PrimaryKey
    @ColumnInfo(name = "group_id")
    val groupId: String,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
) {
    init {
        require(groupId.isNotBlank()) { "groupId cannot be blank" }
    }
}

