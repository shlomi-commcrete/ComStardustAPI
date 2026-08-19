package com.commcrete.stardust.room.new_db.message

import android.location.Location
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.new_db.chat.ChatEntity
import com.commcrete.stardust.stardust.model.config.CarrierType
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
        )
    ],
    indices = [
        Index(value = ["chat_id", "epoch_time_ms", "id"]),
        Index(value = ["chat_id", "state", "epoch_time_ms"])
    ],
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
    val type: MessageType = extraData.toMessageType(),

    @ColumnInfo(name = "epoch_time_ms")
    val epochTimeMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "carrier_type")
    val carrierType: Int? = null,

    @ColumnInfo(name = "rd")
    val rd: Int? = null,

    @ColumnInfo(name = "freq_Mhz")
    val freqMhz: Double? = null,

    @ColumnInfo(name = "carrier_range")
    val carrierRange: String = "",
) {


    init {
        senderID = senderID.lowercase(Locale.ROOT)
        chatId = chatId?.lowercase(Locale.ROOT)
        receiverID = receiverID.lowercase(Locale.ROOT)
    }


    fun hasLocationData(): Boolean {
        return extraData is MessageExtraData.GeoData
    }

    fun getMessageLocation(): Location {
        val location = Location("")
        val geo = extraData as? MessageExtraData.GeoData ?: return location
        location.latitude = geo.latitude
        location.longitude = geo.longitude
        geo.altitude.let { location.altitude = it }
        return location
    }

}
