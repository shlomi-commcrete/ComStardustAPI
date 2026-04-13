package com.commcrete.stardust.room.new_db.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.commcrete.stardust.room.legacy_db.chats.ChatItem
import com.commcrete.stardust.room.new_db.contact.ContactEntity

@Entity(
    tableName = "chat_participants",
    primaryKeys = ["chat_id", "contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatItem::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["contact_id"]),
    ]
)
data class ChatParticipantEntity(
    @ColumnInfo(name = "chat_id")
    val chatId: Int,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
)
