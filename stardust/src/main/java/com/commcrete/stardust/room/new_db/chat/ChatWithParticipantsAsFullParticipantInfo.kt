package com.commcrete.stardust.room.new_db.chat

import com.commcrete.stardust.room.new_db.contact.FullContactData

/**
 * Represents a chat with its participants as full contact models.
 *
 * Unlike [ChatWithParticipantsAsShortParticipantInfo], this includes device
 * metadata for USER/DEVICE contacts through [FullContactData].
 */
data class ChatWithParticipantsAsFullParticipantInfo(
    val chat: ChatEntity,
    val participants: List<FullContactData> = emptyList(),
)

