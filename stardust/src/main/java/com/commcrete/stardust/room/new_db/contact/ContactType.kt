package com.commcrete.stardust.room.new_db.contact

import kotlinx.serialization.Serializable

@Serializable
enum class ContactType {
    USER,
    DEVICE,
    GROUP,
}

