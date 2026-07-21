@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.message


import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Type-specific metadata serialized into MessageEntity.extraData.
 * Each subtype only serializes its own relevant fields.
 */
@Serializable
sealed class MessageExtraData {

    @Serializable
    @SerialName("Text")
    data class Text(
        val text: String,
    ) : MessageExtraData()

    @Serializable
    @SerialName("Attachment")
    data class Attachment(
        val title: String,
        val path: String,
        val subtype: AttachmentType,
        /**
         * Optional cached, display-oriented summary parsed once at persist time.
         * `null` for attachments that need no summary (a plain file/image renders
         * from [title]/[path] alone). Carries [SharedContactSummary] for CONTACT.
         */
        val fileSummary: FileSummary? = null,
    ) : MessageExtraData()

    @Serializable
    @SerialName("PTT")
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
    @SerialName("Location")
    data class Location(
        override val latitude: Double,
        override val longitude: Double,
        override val altitude: Double,
        val isAckResponse: Boolean = false,
    ) : GeoData()

    @Serializable
    @SerialName("Sos")
    data class Sos(
        override val latitude: Double,
        override val longitude: Double,
        override val altitude: Double,
        val subtype: SosType? = null,
    ) : GeoData()
}

fun MessageExtraData?.toMessageType(): MessageType = when (this) {
    is MessageExtraData.Text       -> MessageType.TEXT
    is MessageExtraData.Attachment -> MessageType.ATTACHMENT
    is MessageExtraData.PTT        -> MessageType.PTT
    is MessageExtraData.Location   -> MessageType.LOCATION
    is MessageExtraData.Sos        -> MessageType.SOS
    null                           -> MessageType.TEXT
}

