package com.commcrete.stardust.room.legacy_db.messages

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.new_db.message.AttachmentType
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.MessageType
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.SosType
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.SOSUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.parcelize.Parcelize
import java.util.Locale

@Entity(tableName = "messages_table", indices = [Index(value = ["epochTimeMs"], unique = true),
    Index(value = ["time"], unique = true), Index("isArchived")])
@Parcelize
data class MessageItem (
    @PrimaryKey(autoGenerate = true)
    val id : Int = 0,
    @ColumnInfo(name = "senderID")
    var senderID : String,
    @ColumnInfo(name = "text")
    val text : String,
    @ColumnInfo(name = "epochTimeMs")
    val epochTimeMs : Long,
    @ColumnInfo(name = "seen")
    var seen : MessageState? = MessageState.SENT,
    @ColumnInfo(name = "senderName")
    var senderName : String? = null,
    @ColumnInfo(name = "chatId")
    var chatId : String? = null,
    @ColumnInfo(name = "file_location")
    var fileLocation : String? = null,
    @ColumnInfo(name = "is_audio")
    var isAudio : Boolean? = false,
    @ColumnInfo(name = "audio_type")
    var audioType : Int = 0,
    @ColumnInfo(name = "is_location")
    var isLocation : Boolean? = false,
    @ColumnInfo(name = "is_file")
    var isFile : Boolean? = false,
    @ColumnInfo(name = "is_image")
    var isImage : Boolean? = false,
    @ColumnInfo(name = "is_audio_complete")
    var isAudioComplete : Boolean? = false,
    @ColumnInfo(name = "is_sos")
    var isSOS : Boolean? = false,
    @ColumnInfo(name = "sosType")
    var sosType : Int = 0,
    @ColumnInfo(name = "time")
    var time : String? = null,
    @ColumnInfo(name = "isAck")
    var isAck : Boolean? = false,
    @ColumnInfo(name = "message_number")
    var messageNumber : Int = 1,
    @ColumnInfo(name = "id_number")
    var idNumber : Long = 1,
    @ColumnInfo(name = "isArchived")
    val isArchived: Boolean = false
) : Parcelable {

        init {
            senderID = senderID.lowercase(Locale.ROOT)
            chatId = chatId?.lowercase(Locale.ROOT)
        }

    /**
     * Converts legacy MessageItem to new MessageEntity format.
     *
     * Mapping logic:
     * - Determines message type (TEXT, PTT, ATTACHMENT, LOCATION, SOS) from legacy flags
     * - Builds corresponding MessageExtraData based on type
     * - Preserves sender ID, chat ID, timestamps, and state
     */
    fun toAppMessageEntity(): MessageEntity {
        val type = when {
            isSOS == true -> MessageType.SOS
            isLocation == true -> MessageType.LOCATION
            isAudio == true -> MessageType.PTT
            isImage == true -> MessageType.ATTACHMENT
            isFile == true -> MessageType.ATTACHMENT
            else -> MessageType.TEXT
        }

        val pttSubType = if (type == MessageType.PTT) RecorderUtils.AudioEncoderType.fromId(audioType)?.toEncoderType() else null

        val attachmentSubType = when {
            isImage == true -> AttachmentType.IMAGE
            isFile == true -> AttachmentType.FILE
            else -> null
        }

        val mappedState = when {
            isArchived -> MessageState.ARCHIVED
            else -> seen?.let { s -> MessageState.entries.firstOrNull { it.id == s.id } }
        }

        return MessageEntity(
            id = id,
            senderID = senderID,
            epochTimeMs = epochTimeMs,
            state = mappedState,
            chatId = chatId,
            type = type,
            extraData = buildExtraData(
                type = type,
                pttSubtype = pttSubType,
                attachmentPath = fileLocation,
                attachmentSubType = attachmentSubType,
                text = text,
                legacySosType = sosType),
            receiverID = RegisteredUserUtils.mRegisterUser?.appId ?: "unknown_receiver"
        )
    }

    private fun buildExtraData(
        type: MessageType,
        attachmentPath: String?,
        text: String,
        legacySosType: Int = 0,
        pttSubtype: EncoderType?,
        attachmentSubType: AttachmentType?,
    ): MessageExtraData? {
        return when (type) {
            MessageType.ATTACHMENT ->
                attachmentPath?.takeIf { it.isNotBlank() }?.let { MessageExtraData.Attachment(
                    title = text,
                    path = it,
                    subtype = attachmentSubType ?: AttachmentType.FILE
                ) }

            MessageType.PTT ->
                attachmentPath?.takeIf { it.isNotBlank() }?.let {
                    MessageExtraData.PTT(
                        path = it,
                        encoderType = pttSubtype ?: EncoderType.CODEC2)
                }

            MessageType.LOCATION -> parseLocationExtra(text)

            MessageType.SOS -> parseLocationExtra(text)?.let {
                val sosSubtype = when (legacySosType) {
                    SOSUtils.SOS_REPORT_TYPES.HOSTILE.type -> SosType.HOSTILE
                    SOSUtils.SOS_REPORT_TYPES.LOST.type -> SosType.MIA
                    SOSUtils.SOS_REPORT_TYPES.MAN_DOWN.type -> SosType.MAN_DOWN
                    else -> null
                }
                MessageExtraData.Sos(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    altitude = it.altitude,
                    subtype = sosSubtype,
                )
            }

            MessageType.TEXT -> null
        }
    }

    private fun parseLocationExtra(text: String): MessageExtraData.Location? {
        val values = text
            .replace("latitude : ", "")
            .replace("longitude : ", "")
            .replace("altitude : ", "")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val lat = values.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = values.getOrNull(1)?.toDoubleOrNull() ?: return null
        val alt = values.getOrNull(2)?.toDoubleOrNull() ?: return null
        return MessageExtraData.Location(latitude = lat, longitude = lon, altitude = alt)
    }
}

