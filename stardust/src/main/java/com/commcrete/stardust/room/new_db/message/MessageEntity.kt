package com.commcrete.stardust.room.new_db.message

import android.location.Location
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.legacy_db.messages.MessageItem
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.contact.ContactUserIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactDeviceEntity
import com.commcrete.stardust.room.new_db.contact.ContactGroupIdEntity
import com.commcrete.stardust.util.SOSUtils
import com.commcrete.stardust.util.RegisteredUserUtils
import java.util.Locale

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactUserIdEntity::class,
            parentColumns = ["user_id"],
            childColumns = ["sender_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactDeviceEntity::class,
            parentColumns = ["device_id"],
            childColumns = ["sender_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactGroupIdEntity::class,
            parentColumns = ["group_id"],
            childColumns = ["sender_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["epoch_time_ms"], unique = true),
        Index(value = ["chat_id"]),
        Index(value = ["sender_id"]),
        Index(value = ["receiver_id"]),
    ]
)
data class MessageEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "chat_id")
    var chatId: String? = null,

    @ColumnInfo(name = "sender_id")
    var senderID: String,

    @ColumnInfo(name = "receiver_id")
    var receiverID: String,

    @ColumnInfo(name = "extra_data")
    var extraData: MessageExtraData? = null,

    @ColumnInfo(name = "state")
    var state: MessageState? = MessageState.SENT,

    @ColumnInfo(name = "type")
    var type: MessageType = MessageType.TEXT,

    @ColumnInfo(name = "epoch_time_ms")
    val epochTimeMs: Long = System.currentTimeMillis(),
) {

    init {
        senderID = senderID.lowercase(Locale.ROOT)
        chatId = chatId?.lowercase(Locale.ROOT)
    }


    fun hasLocationData(): Boolean {
        return extraData is MessageExtraData.Location || extraData is MessageExtraData.Sos
    }

    fun getMessageLocation(): Location {
        val location = Location("")
        when (val extra = extraData) {
            is MessageExtraData.Location -> {
                location.latitude = extra.latitude
                location.longitude = extra.longitude
                extra.altitude?.let { location.altitude = it }
            }
            is MessageExtraData.Sos -> {
                location.latitude = extra.latitude
                location.longitude = extra.longitude
                extra.altitude?.let { location.altitude = it }
            }
            else -> {}
        }

        return location
    }

}

fun MessageItem.toAppMessageEntity(): MessageEntity {
    val type = when {
        isSOS == true -> MessageType.SOS
        isLocation == true -> MessageType.LOCATION
        isAudio == true -> MessageType.PTT
        isImage == true -> MessageType.ATTACHMENT
        isFile == true -> MessageType.ATTACHMENT
        else -> MessageType.TEXT
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
        extraData = buildExtraData(type = type, attachmentPath = fileLocation, text = text, legacySosType = sosType),
        receiverID = RegisteredUserUtils.mRegisterUser?.appId ?: "unknown_receiver"
    )
}

private fun buildExtraData(
    type: MessageType,
    attachmentPath: String?,
    text: String,
    legacySosType: Int = 0,
): MessageExtraData? {
    return when (type) {
        MessageType.ATTACHMENT ->
            attachmentPath?.takeIf { it.isNotBlank() }?.let { MessageExtraData.Attachment(it) }

        MessageType.PTT ->
            attachmentPath?.takeIf { it.isNotBlank() }?.let { MessageExtraData.PTT(it) }

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
    val alt = values.getOrNull(2)?.toDoubleOrNull()
    return MessageExtraData.Location(latitude = lat, longitude = lon, altitude = alt)
}
