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

    /**
     * Common base for any [MessageExtraData] that carries geographic coordinates.
     * Both [Location] and [Sos] share latitude/longitude/altitude; use this type
     * whenever only the coordinates matter.
     */
    sealed class GeoData : MessageExtraData() {
        abstract val latitude: Double
        abstract val longitude: Double
        abstract val altitude: Double
    }

    @Serializable
    data class Location(
        override val latitude: Double,
        override val longitude: Double,
        override val altitude: Double,
        val isAckResponse: Boolean = false,
    ) : GeoData()

    @Serializable
    data class Sos(
        override val latitude: Double,
        override val longitude: Double,
        override val altitude: Double,
        val subtype: SosType? = null,
    ) : GeoData()
}
