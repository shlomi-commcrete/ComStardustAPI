@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.message


import kotlinx.serialization.Serializable

/**
 * Type-specific metadata serialized into MessageEntity.extraData.
 * Each subtype only serializes its own relevant fields.
 */
@Serializable
sealed class MessageExtraData {

    @Serializable
    data class Text(
        val text: String,
    ) : MessageExtraData()

    @Serializable
    data class Attachment(
        val title: String,
        val path: String,
        val subtype: AttachmentType,
    ) : MessageExtraData()

    @Serializable
    data class PTT(
        val path: String,
        val encoderType: EncoderType = EncoderType.CODEC2,
    ) : MessageExtraData()

    @Serializable
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        val isAckResponse: Boolean = false,
    ) : MessageExtraData()

    @Serializable
    data class Sos(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        val subtype: SosType? = null,
    ) : MessageExtraData()
}
