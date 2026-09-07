@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.message


import com.commcrete.stardust.util.FileReceiver
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
        /**
         * Absolute path of the local copy of the file. EMPTY when the transfer failed:
         * a failed transfer writes no file at all, so there is nothing to point at (and
         * no partial-file artifact to clean up). Check [failure] / the row's state
         * before opening it.
         */
        val path: String,
        val subtype: AttachmentType,
        /**
         * Optional cached, display-oriented summary parsed once at persist time.
         * `null` for attachments that need no summary (a plain file/image renders
         * from [title]/[path] alone), and for a failed transfer — there is no file to
         * summarize. Carries [SharedContactSummary] for CONTACT.
         */
        val fileSummary: FileSummary? = null,
        /**
         * Why the transfer did not deliver its file, or `null` when it did. The reason
         * lives here and nowhere else; the row's [MessageState.FAILED] carries the fact
         * of the failure.
         *
         * Serialized by enum name and OMITTED entirely when `null`, so every row written
         * before this field existed reads back exactly as it did before.
         */
        val failure: FileReceiver.FileFailure? = null,
        /**
         * How far an outgoing transfer had got when the user stopped it, or `null` when
         * it was not cancelled. Set together with [MessageState.CANCELLED], and mutually
         * exclusive with [failure] — a cancel is not a failure.
         *
         * Omitted entirely when `null`, like [failure].
         */
        val cancellation: FileTransferCancellation? = null,
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

