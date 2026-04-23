package com.commcrete.stardust.room.new_db.chat

import com.commcrete.stardust.room.new_db.contact.ContactType

/**
 * Lightweight participant info containing only the essential identifiers.
 *
 * @param id the communication ID (userId, groupId, or deviceId)
 * @param type the participant type (USER, GROUP, or DEVICE)
 */
data class ShortParticipantInfo(
    val id: String,
    val type: ContactType,
)

