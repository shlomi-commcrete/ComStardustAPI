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
    SOS,

    /**
     * The "Location Request" row: [MessageExtraData.Text] carrying LOCATION_REQUEST_TEXT, typed
     * apart from TEXT so a location lane can claim it. Set explicitly by its two writers — Text
     * alone maps to TEXT. Stored by name: add, never rename.
     */
    LOCATION_REQUEST,
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

