package com.commcrete.bittell.room.sniffer

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.messages.MessageItem
import kotlinx.parcelize.Parcelize

@Entity(tableName = "sniffer_table", indices = [Index(value = ["epochTimeMs"], unique = true),
    Index(value = ["time"], unique = true)])
@Parcelize
data class SnifferItem (
    @PrimaryKey(autoGenerate = true)
    val id : Int = 0,
    @ColumnInfo(name = "senderID")
    val senderID : String,
    @ColumnInfo(name = "receiverID")
    val receiverID : String,
    @ColumnInfo(name = "text")
    val text : String,
    @ColumnInfo(name = "epochTimeMs")
    val epochTimeMs : Long,
    @ColumnInfo(name = "senderName")
    var senderName : String? = null,
    @ColumnInfo(name = "chatId")
    var chatId : String? = null,
    @ColumnInfo(name = "receiverName")
    var receiverName : String? = null,
    @ColumnInfo(name = "file_location")
    var fileLocation : String? = null,
    @ColumnInfo(name = "is_audio")
    var isAudio : Boolean? = false,
    @ColumnInfo(name = "is_location")
    var isLocation : Boolean? = false,
    @ColumnInfo(name = "is_audio_complete")
    var isAudioComplete : Boolean? = false,
    @ColumnInfo(name = "is_sos")
    var isSOS : Boolean? = false,
    @ColumnInfo(name = "time")
    var time : String? = null,
) : Parcelable {

    fun getFromTo () : String {
        return "From : ${senderName ?: senderID}, To : ${receiverName ?: receiverID}"
    }

    fun toMessageItem () : MessageItem {
        return MessageItem(id = this.id, senderID = this.senderID, text = this.text, epochTimeMs = this.epochTimeMs, senderName = this.senderName,
            chatId = this.chatId, fileLocation = this.fileLocation, isAudio = this.isAudio, isLocation = this.isLocation, isAudioComplete = this.isAudioComplete,
            isSOS = this.isSOS, time = this.time)
    }
}