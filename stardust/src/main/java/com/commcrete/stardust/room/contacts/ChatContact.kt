package com.commcrete.stardust.room.contacts

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.android.parcel.Parcelize

@Entity(tableName = "contacts_table", indices = [androidx.room.Index(
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
    val bittelId : String? = null,
    @ColumnInfo(name = "smartphone_bittel_id")
    val smartphoneBittelId : String? = null,
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
    var lastUpdateTS : Long? = 0,
    @ColumnInfo(name = "isSOS")
    var isSOS : Boolean = false,
) : Parcelable