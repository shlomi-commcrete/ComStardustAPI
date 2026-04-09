package com.commcrete.stardust.room.new_db.chat

import com.commcrete.stardust.room.new_db.contact.ContactEntity

// Compatibility models retained in contact package.
data class ContactLastKnownLocation(
    val latitude: Double,
    val longitude: Double,
    val epochTimeMs: Long,
)

data class ContactLocationMessageRow(
    val text: String,
    val epochTimeMs: Long,
)
