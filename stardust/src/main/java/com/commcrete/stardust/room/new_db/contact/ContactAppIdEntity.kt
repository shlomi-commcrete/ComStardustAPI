package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.new_db.chat.ContactEntity

@Entity(
    tableName = "app_contact_user_ids",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contact_id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["contact_id"], unique = true),
    ]
)
data class ContactAppIdEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
) {
    init {
        require(userId.isNotBlank()) { "userId cannot be blank" }
    }
}

