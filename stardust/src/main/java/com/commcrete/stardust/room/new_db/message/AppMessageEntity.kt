package com.commcrete.stardust.room.new_db.message

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import java.util.Locale

@Entity(
    tableName = "new_messages_table",
    indices = [
        Index(value = ["epochTimeMs"], unique = true),
        Index(value = ["time"], unique = true),
        Index("isArchived"),
    ]
)
data class AppMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "senderID")
    var senderID: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "epochTimeMs")
    val epochTimeMs: Long,
    @ColumnInfo(name = "seen")
    var seen: SeenStatus? = SeenStatus.SENT,
    @ColumnInfo(name = "senderName")
    var senderName: String? = null,
    @ColumnInfo(name = "chatId")
    var chatId: String? = null,
    @ColumnInfo(name = "file_location")
    var fileLocation: String? = null,
    @ColumnInfo(name = "is_audio")
    var isAudio: Boolean? = false,
    @ColumnInfo(name = "audio_type")
    var audioType: Int = 0,
    @ColumnInfo(name = "is_location")
    var isLocation: Boolean? = false,
    @ColumnInfo(name = "is_file")
    var isFile: Boolean? = false,
    @ColumnInfo(name = "is_image")
    var isImage: Boolean? = false,
    @ColumnInfo(name = "is_audio_complete")
    var isAudioComplete: Boolean? = false,
    @ColumnInfo(name = "is_sos")
    var isSOS: Boolean? = false,
    @ColumnInfo(name = "sosType")
    var sosType: Int = 0,
    @ColumnInfo(name = "time")
    var time: String? = null,
    @ColumnInfo(name = "isAck")
    var isAck: Boolean? = false,
    @ColumnInfo(name = "message_number")
    var messageNumber: Int = 1,
    @ColumnInfo(name = "id_number")
    var idNumber: Long = 1,
    @ColumnInfo(name = "isArchived")
    val isArchived: Boolean = false,
) {
    init {
        senderID = senderID.lowercase(Locale.ROOT)
        chatId = chatId?.lowercase(Locale.ROOT)
    }
}

fun MessageItem.toAppMessageEntity(): AppMessageEntity = AppMessageEntity(
    id = id,
    senderID = senderID,
    text = text,
    epochTimeMs = epochTimeMs,
    seen = seen,
    senderName = senderName,
    chatId = chatId,
    fileLocation = fileLocation,
    isAudio = isAudio,
    audioType = audioType,
    isLocation = isLocation,
    isFile = isFile,
    isImage = isImage,
    isAudioComplete = isAudioComplete,
    isSOS = isSOS,
    sosType = sosType,
    time = time,
    isAck = isAck,
    messageNumber = messageNumber,
    idNumber = idNumber,
    isArchived = isArchived,
)

fun AppMessageEntity.toLegacyMessageItem(): MessageItem = MessageItem(
    id = id,
    senderID = senderID,
    text = text,
    epochTimeMs = epochTimeMs,
    seen = seen,
    senderName = senderName,
    chatId = chatId,
    fileLocation = fileLocation,
    isAudio = isAudio,
    audioType = audioType,
    isLocation = isLocation,
    isFile = isFile,
    isImage = isImage,
    isAudioComplete = isAudioComplete,
    isSOS = isSOS,
    sosType = sosType,
    time = time,
    isAck = isAck,
    messageNumber = messageNumber,
    idNumber = idNumber,
    isArchived = isArchived,
)

