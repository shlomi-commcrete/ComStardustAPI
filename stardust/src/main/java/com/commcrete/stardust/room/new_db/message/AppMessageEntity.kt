package com.commcrete.stardust.room.new_db.message

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.room.new_db.contact.ContactAppIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactDeviceEntity
import com.commcrete.stardust.room.new_db.chat.ContactEntity
import java.util.Locale

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactAppIdEntity::class,
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
        )
    ],
    indices = [
        Index(value = ["epoch_time_ms"], unique = true),
        Index(value = ["chat_id"]),
        Index(value = ["sender_id"]),
    ]
)
data class AppMessageEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "id_number")
    var idNumber: Long = 1,

    @ColumnInfo(name = "chat_id")
    var chatId: String? = null,

    @ColumnInfo(name = "sender_id")
    var senderID: String,
    @ColumnInfo(name = "sender_name")
    var senderName: String? = null,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "file_location")
    var fileLocation: String? = null,
    @ColumnInfo(name = "audio_type")
    var audioType: Int = 0,
    @ColumnInfo(name = "sos_type")
    var sosType: Int = 0,

    @ColumnInfo(name = "state")
    var state: SeenStatus? = SeenStatus.SENT,
    @ColumnInfo(name = "type")
    var type: MessageType = MessageType.TEXT,

    @ColumnInfo(name = "message_number")
    var messageNumber: Int = 1,
    @ColumnInfo(name = "epoch_time_ms")
    val epochTimeMs: Long,
) {
    init {
        senderID = senderID.lowercase(Locale.ROOT)
        chatId = chatId?.lowercase(Locale.ROOT)
    }
}

fun MessageItem.toAppMessageEntity(): AppMessageEntity {
    // Determine consolidated type from legacy boolean flags
    val type = when {
        isSOS == true -> MessageType.SOS
        isLocation == true -> MessageType.LOCATION
        isAudio == true -> MessageType.AUDIO
        isImage == true -> MessageType.IMAGE
        isFile == true -> MessageType.FILE
        else -> MessageType.TEXT
    }

    return AppMessageEntity(
        id = id,
        senderID = senderID,
        text = text,
        epochTimeMs = epochTimeMs,
        state = if (isArchived) SeenStatus.ARCHIVED else seen,
        senderName = senderName,
        chatId = chatId,
        type = type,
        fileLocation = fileLocation,
        audioType = audioType,
        sosType = sosType,
        messageNumber = messageNumber,
        idNumber = idNumber
    )
}

fun AppMessageEntity.toLegacyMessageItem(): MessageItem {
    // Restore legacy boolean flags from consolidated type
    val (isAudio, isImage, isFile, isLocation, isSOS) = when (type) {
        MessageType.AUDIO -> Tuple5(true, false, true, false, false)
        MessageType.IMAGE -> Tuple5(false, true, true, false, false)
        MessageType.FILE -> Tuple5(false, false, true, false, false)
        MessageType.LOCATION -> Tuple5(false, false, false, true, false)
        MessageType.SOS -> Tuple5(false, false, false, false, true)
        MessageType.TEXT -> Tuple5(false, false, false, false, false)
    }

    return MessageItem(
        id = id,
        senderID = senderID,
        text = text,
        epochTimeMs = epochTimeMs,
        seen = state,
        senderName = senderName,
        chatId = chatId,
        fileLocation = fileLocation,
        isAudio = if (isAudio) true else null,
        audioType = audioType,
        isLocation = if (isLocation) true else null,
        isFile = if (isFile) true else null,
        isImage = if (isImage) true else null,
        isAudioComplete = null,
        isSOS = if (isSOS) true else null,
        sosType = sosType,
        time = null,
        isAck = null,
        messageNumber = messageNumber,
        idNumber = idNumber,
        isArchived = state == SeenStatus.ARCHIVED,
    )
}

private data class Tuple5(val a: Boolean, val b: Boolean, val c: Boolean, val d: Boolean, val e: Boolean)

