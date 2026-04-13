package com.commcrete.stardust.room.new_db.chat

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.commcrete.stardust.room.legacy_db.chats.ChatItem
import com.commcrete.stardust.room.new_db.contact.ContactEntity

data class ChatWithParticipants(
    @Embedded
    val chat: ChatItem,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ChatParticipantEntity::class,
            parentColumn = "chat_id",
            entityColumn = "contact_id",
        ),
    )
    val participants: List<ContactEntity> = emptyList(),
)
