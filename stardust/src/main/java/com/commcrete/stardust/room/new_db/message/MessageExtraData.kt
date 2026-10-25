@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.message


import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.FileReceiver
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Type-specific metadata serialized into MessageEntity.extraData.
 * Each subtype only serializes its own relevant fields.
 */
@Serializable
sealed class MessageExtraData {

    /**
     * The RD this message travelled on — the one a send resolved, or the one a received package's
     * control byte named — or `null` when not recorded: rows written before this field existed,
     * and the paths that do not record it (PTT receive, the legacy Codec2 recorder, the
     * hardware-key SOS). Omitted entirely when `null`, like [Attachment.failure].
     */
    abstract val deliveryRd: DeliveryRd?

    @Serializable
    @SerialName("Text")
    data class Text(
        val text: String,
        /**
         * How far an outgoing multi-part text has got — see [SendProgress]. Set only while the
         * row is, or was, [MessageState.SENDING]; `null` for a single-package text and for every
         * incoming one. Omitted entirely when `null`, like [Attachment.failure].
         */
        val sendProgress: SendProgress? = null,
        /**
         * Why a multi-part text did not get through whole, or `null` when it did — the same
         * reasons, and the same rule, as [Attachment.failure]: the row's
         * [MessageState.FAILED] carries the fact, this carries the cause. Omitted when `null`.
         */
        val failure: FileReceiver.FileFailure? = null,
        /**
         * The text went out with DEMAND_ACK, which the receiver answers with its location. Sent:
         * what was asked for. Received: what the LAST part carried. Omitted when false, so every
         * row written before this field existed reads back as no request.
         */
        val locationRequested: Boolean = false,
        override val deliveryRd: DeliveryRd? = null,
    ) : MessageExtraData()

    @Serializable
    @SerialName("Attachment")
    data class Attachment(
        val title: String,
        /**
         * Absolute path of the local copy of the file. EMPTY while the row's state is
         * RECEIVING — an incoming transfer is collected package by package and only
         * assembled into a file once it is complete — and EMPTY when the transfer failed,
         * because a failed transfer writes no file at all (so there is no partial-file
         * artifact to clean up either).
         *
         * Go by the row's STATE, never by this being non-empty: RECEIVING means the file
         * is still on its way, FAILED means it never arrived, and [failure] says why.
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
        /**
         * How far an outgoing transfer has got — see [SendProgress]. Set only while the row is,
         * or was, [MessageState.SENDING]; `null` on every incoming row. Omitted when `null`.
         */
        val sendProgress: SendProgress? = null,
        /**
         * The file's size in bytes before it was gzipped for the air — for an image, which is
         * not compressed, its size as sent. The same figure the start package carries, so it is
         * known from the first package of an incoming transfer, before the file exists.
         *
         * `null` when unknown: a sender that predates the field, a file past the start package's
         * 16 MiB limit, or a row written before this field existed. Omitted when `null`, like
         * [failure].
         */
        val originalSize: Long? = null,
        override val deliveryRd: DeliveryRd? = null,
    ) : MessageExtraData()

    @Serializable
    @SerialName("PTT")
    data class PTT(
        val path: String,
        val encoderType: EncoderType = EncoderType.CODEC2,
        /**
         * The recording is cut short: the stream was still arriving when the process died, so the
         * WAV holds what had been decoded by then and nothing after it. Set by the startup sweep,
         * never during a live stream — a PTT that ends normally is complete by definition.
         *
         * The row is still RECEIVED and the file still plays. This exists so a UI can say the
         * recording is partial instead of presenting a truncated message as the whole of it.
         *
         * Omitted entirely when false, like [Attachment.failure], so every row written before this
         * field existed reads back exactly as it did.
         */
        val truncated: Boolean = false,
        override val deliveryRd: DeliveryRd? = null,
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
        /** As [Text.locationRequested]: the location went out with DEMAND_ACK. Omitted when false. */
        val locationRequested: Boolean = false,
        override val deliveryRd: DeliveryRd? = null,
    ) : GeoData()

    @Serializable
    @SerialName("Sos")
    data class Sos(
        override val latitude: Double,
        override val longitude: Double,
        override val altitude: Double,
        val subtype: SosType? = null,
        /**
         * Who has acknowledged this SOS, in arrival order, or EMPTY when nobody has yet.
         * Only ever filled on an SOS this user SENT: an ack comes back for your own SOS,
         * never for one you received.
         *
         * A list rather than a single acker because an SOS can be addressed to a group,
         * where every member can respond — see [SosAck].
         *
         * Serialized as an empty list by omission, like [Attachment.failure], so every
         * row written before this field existed reads back exactly as it did.
         */
        val acks: List<SosAck> = emptyList(),
        override val deliveryRd: DeliveryRd? = null,
    ) : GeoData()
}

/**
 * The RD a message travelled on and the F it occupied — see [MessageExtraData.deliveryRd].
 *
 * [rd] is numbered as the operator sees it (RD1 = 1). [fFirst]..[fLast] are F in the operator's
 * global 1–9 numbering, as THIS radio's configuration placed the RD; both `null` when that
 * configuration had not set them yet. [type] is the RD's rate (HR, LR, ST), stored by name; `null`
 * on a row recorded before it was.
 */
@Serializable
data class DeliveryRd(
    val rd: Int,
    val fFirst: Int? = null,
    val fLast: Int? = null,
    val type: CarrierType? = null,
) {
    /** The F as a range, a single F being first == last; `null` when not known. Not serialized. */
    val fRange: IntRange?
        get() = if (fFirst != null && fLast != null) fFirst..fLast else null
}

/** This RD as a [DeliveryRd]: its zero-based slot is the RD number, its F as configured. */
internal fun Carrier.toDeliveryRd(): DeliveryRd =
    DeliveryRd(rd = index + 1, fFirst = fFirst, fLast = fLast, type = type)

/** This metadata with [radio] recorded as the RD it travelled on. */
internal fun MessageExtraData.withDeliveryRd(radio: Carrier): MessageExtraData {
    val recorded = radio.toDeliveryRd()
    return when (this) {
        is MessageExtraData.Text -> copy(deliveryRd = recorded)
        is MessageExtraData.Attachment -> copy(deliveryRd = recorded)
        is MessageExtraData.PTT -> copy(deliveryRd = recorded)
        is MessageExtraData.Location -> copy(deliveryRd = recorded)
        is MessageExtraData.Sos -> copy(deliveryRd = recorded)
    }
}

fun MessageExtraData?.toMessageType(): MessageType = when (this) {
    is MessageExtraData.Text       -> MessageType.TEXT
    is MessageExtraData.Attachment -> MessageType.ATTACHMENT
    is MessageExtraData.PTT        -> MessageType.PTT
    is MessageExtraData.Location   -> MessageType.LOCATION
    is MessageExtraData.Sos        -> MessageType.SOS
    null                           -> MessageType.TEXT
}

