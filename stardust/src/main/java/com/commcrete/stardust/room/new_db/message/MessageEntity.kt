package com.commcrete.stardust.room.new_db.message

import android.location.Location
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.room.new_db.contact.ContactUserIdEntity
import com.commcrete.stardust.room.new_db.contact.ContactDeviceEntity
import com.commcrete.stardust.room.new_db.contact.ContactGroupIdEntity
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
        Index(value = ["chat_id", "epoch_time_ms"]),
        Index(value = ["chat_id", "state", "epoch_time_ms"]),
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
        return extraData is MessageExtraData.GeoData
    }

    fun getMessageLocation(): Location {
        val location = Location("")
        val geo = extraData as? MessageExtraData.GeoData ?: return location
        location.latitude = geo.latitude
        location.longitude = geo.longitude
        geo.altitude?.let { location.altitude = it }
        return location
    }

}
