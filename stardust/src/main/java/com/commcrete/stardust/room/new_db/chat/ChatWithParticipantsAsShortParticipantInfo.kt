package com.commcrete.stardust.room.new_db.chat

/**
 * Represents a chat with its participants' essential info (id + type).
 *
 * This is a lightweight read-model created by converting [ChatWithParticipants] participants
 * after retrieval from the database. Instead of full [FullContactData], this stores only
 * the essential identifiers: the communication ID (userId/groupId/deviceId) and type.
 */
data class ChatWithParticipantsAsShortParticipantInfo(
    val chat: ChatEntity,
    val participants: List<ShortParticipantInfo> = emptyList(),
)


