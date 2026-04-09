package com.commcrete.stardust.room.new_db.message

import android.location.Location
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessageState
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.contact.ContactUserIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactDeviceEntity
import com.commcrete.stardust.room.new_db.contact.ContactGroupIdEntity
import com.commcrete.stardust.util.SOSUtils
import com.commcrete.stardust.util.UsersUtils
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
    @ColumnInfo(name = "id_number")
    var idNumber: Long = 1,

    @ColumnInfo(name = "chat_id")
    var chatId: String? = null,

    @ColumnInfo(name = "sender_id")
    var senderID: String,

    @ColumnInfo(name = "receiver_id")
    var receiverID: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "attachment_path")
    var attachmentPath: String? = null,

    @ColumnInfo(name = "state")
    var state: MessageState? = MessageState.SENT,

    @ColumnInfo(name = "type")
    var type: MessageType = MessageType.TEXT,

    @ColumnInfo(name = "epoch_time_ms")
    val epochTimeMs: Long,
) {

    init {
        senderID = senderID.lowercase(Locale.ROOT)
        chatId = chatId?.lowercase(Locale.ROOT)
    }

    fun isSosMessage(): Boolean = type in listOf(
        MessageType.SOS,
        MessageType.SOS_HOSTILE,
        MessageType.SOS_MIA,
        MessageType.SOS_MAN_DOWN)

    fun hasLocationData(): Boolean {
        return type == MessageType.LOCATION || isSosMessage()
    }

    fun getMessageLocation(): Location {
        val location = Location("")
        if(hasLocationData()) return location

        val data: Array<String> = text.replace("latitude : ", "")
            .replace("longitude : ", "")
            .replace("altitude : ", "")
            .split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        try {
            location.latitude = data[0].toDouble()
            location.longitude = data[1].toDouble()
            location.altitude = data[2].toDouble()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return location
    }

}

fun MessageItem.toAppMessageEntity(): MessageEntity {
    // Determine consolidated type from legacy boolean flags
    val type = when {
        isSOS == true -> {
            when(sosType) {
                SOSUtils.SOS_TYPES_ARMY.HOSTILE.type -> MessageType.SOS_HOSTILE
                SOSUtils.SOS_TYPES_ARMY.LOST.type -> MessageType.SOS_MIA
                SOSUtils.SOS_TYPES_ARMY.MAN_DOWN.type -> MessageType.SOS_MAN_DOWN
                else -> MessageType.SOS
            }
        }
        isLocation == true -> MessageType.LOCATION
        isAudio == true -> MessageType.AUDIO
        isImage == true -> MessageType.IMAGE
        isFile == true -> MessageType.FILE
        else -> MessageType.TEXT
    }

    return MessageEntity(
        id = id,
        senderID = senderID,
        text = text,
        epochTimeMs = epochTimeMs,
        state = if (isArchived) MessageState.ARCHIVED else seen,
        chatId = chatId,
        type = type,
        attachmentPath = fileLocation,
        idNumber = idNumber,
        receiverID = UsersUtils.mRegisterUser?.appId ?: "unknown_receiver"
    )
}
