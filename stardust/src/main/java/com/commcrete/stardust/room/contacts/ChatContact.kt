package com.commcrete.stardust.room.contacts

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.android.parcel.Parcelize
import java.util.Locale

@Entity(tableName = "contacts", indices = [androidx.room.Index(
    value = ["number"],
    unique = true
)])
@Parcelize
data class ChatContact (
    @PrimaryKey (autoGenerate = true)
    val contactId : Int = 0,
    @ColumnInfo(name = "display_name")
    val displayName : String = "",
    @ColumnInfo(name = "number")
    val number : String,
    @ColumnInfo(name = "photo_uri")
    var photoURI : String? = null,
    @ColumnInfo(name = "bittel_id")
    var bittelId : String? = null,
    @ColumnInfo(name = "smartphone_bittel_id")
    var smartphoneBittelId : String? = null,
    @ColumnInfo(name = "chat_user_id")
    var chatUserId : String? = null,
    @ColumnInfo(name = "lat")
    var lat : Double = 0.0,
    @ColumnInfo(name = "lon")
    var lon : Double = 0.0,
    @ColumnInfo(name = "online")
    var online : Boolean = false,
    @ColumnInfo(name = "pttEnabled")
    var pttEnabled : Boolean = true,
    @ColumnInfo(name = "updateTS")
    var lastUpdateAt : Long? = 0,
    @ColumnInfo(name = "isSOS")
    var isSOS : Boolean = false,
    @ColumnInfo(name = "isSniffer")
    var isSniffer : Boolean = false,
    @ColumnInfo(name = "is_bittel")
    var isBittel : Boolean = false,
    @ColumnInfo(name = "is_group")
    var isGroup : Boolean = false,
) : Parcelable {
    init {
        bittelId = bittelId?.lowercase(Locale.ROOT)
        smartphoneBittelId = smartphoneBittelId?.lowercase(Locale.ROOT)
    }
}