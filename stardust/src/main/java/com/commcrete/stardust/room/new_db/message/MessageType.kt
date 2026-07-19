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
    ATTACHMENT,
    PTT,
    LOCATION,
    SOS
}

enum class AttachmentType {
    IMAGE,
    FILE,
    CONTACT
}

enum class SosType {
    MAN_DOWN,
    MIA,
    HOSTILE,
    REINFORCEMENT
}


enum class EncoderType {
    CODEC2,
    AI
}

