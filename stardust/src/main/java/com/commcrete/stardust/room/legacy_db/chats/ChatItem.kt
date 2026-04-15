@file:JvmName("ChatItemHelper")

package com.commcrete.stardust.room.legacy_db.chats

import android.os.Parcelable
import androidx.room.*
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.request_objects.model.user_list.User
import kotlinx.android.parcel.Parcelize
import org.json.JSONObject
import java.util.Locale

@Entity(tableName = "chats_table",  indices = [Index(
    value = ["chat_id"],
    unique = true
)])
@Parcelize
data class ChatItem (
    @PrimaryKey (autoGenerate = true)
    val id : Int = 0,
    @ColumnInfo(name = "chat_id")
    var chat_id : String,
    @ColumnInfo(name = "last_message_id")
    val lastMessageId : String = "",
    @ColumnInfo(name = "chat_name")
    val name : String = "",
    @ColumnInfo(name = "audio_received")
    var isAudioReceived : Boolean = false,
    @ColumnInfo(name = "enable_background_ptt")
    var enableBackgroundPtt : Boolean = true,
    @ColumnInfo(name = "isSniffer")
    var isSniffer : Boolean = false,
    val chatContacts : String = "",
    var bittelIDS : String = "",
    val smartphoneBittelIDS : String = "",
    val numOfUnseenMessages : Int = 0,
    @ColumnInfo(name = "is_group")
    val isGroup: Boolean = false,
    @ColumnInfo(name = "is_bittel")
    val isBittel: Boolean = false,
    @ColumnInfo(name = "image_name")
    val imageName: String? = "",
    @Embedded var message: Message? = null,
    @Embedded var user: User? = null,

    ) : Parcelable {

    init {
        chat_id = chat_id.lowercase(Locale.ROOT)
        bittelIDS = bittelIDS.lowercase(Locale.ROOT)
    }

}
