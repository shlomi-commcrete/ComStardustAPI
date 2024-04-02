package com.commcrete.stardust.room.messages

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Entity(tableName = "messages_table", indices = [Index(value = ["epochTimeMs"], unique = true),
    Index(value = ["time"], unique = true)])
@Parcelize
data class MessageItem (
    @PrimaryKey(autoGenerate = true)
    val id : Int = 0,
    @ColumnInfo(name = "senderID")
    val senderID : String,
    @ColumnInfo(name = "text")
    val text : String,
    @ColumnInfo(name = "epochTimeMs")
    val epochTimeMs : Long,
    @ColumnInfo(name = "seen")
    var seen : SeenStatus? = SeenStatus.SENT,
    @ColumnInfo(name = "senderName")
    var senderName : String? = null,
    @ColumnInfo(name = "chatId")
    var chatId : String? = null,
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
    @ColumnInfo(name = "isAck")
    var isAck : Boolean? = false,
    @ColumnInfo(name = "message_number")
    var messageNumber : Int = 1,
    @ColumnInfo(name = "id_number")
    var idNumber : Long = 1
) : Parcelable

enum class SeenStatus(val id: Int){
    SENT(0),
    SEEN(1),
    RECEIVED(2),
    FAILED(3)
}