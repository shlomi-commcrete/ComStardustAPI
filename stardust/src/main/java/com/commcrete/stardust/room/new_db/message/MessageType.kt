package com.commcrete.stardust.room.new_db.message

/**
 * Represents the type of message.
 *
 * - TEXT: Regular text message
 * - FILE: Generic file attachment (may also have subtype IMAGE or AUDIO)
 * - IMAGE: Image file (subtype of FILE)
 * - AUDIO: Audio/PTT message (subtype of FILE)
 * - LOCATION: Location sharing message
 * - SOS: Emergency SOS message (with sosType subtype)
 */
enum class MessageType {
    TEXT,
    FILE,
    IMAGE,
    AUDIO,
    LOCATION,
    SOS
}

