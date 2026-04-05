package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.contacts.ChatContact
import java.util.Locale

@Entity(
    tableName = "new_contacts_table",
    indices = [Index(value = ["number"], unique = true)]
)
data class AppContactEntity(
    @PrimaryKey(autoGenerate = true)
    val contactId: Int = 0,
    @ColumnInfo(name = "display_name")
    val displayName: String = "",
    @ColumnInfo(name = "number")
    val number: String,
    @ColumnInfo(name = "photo_uri")
    var photoURI: String? = null,
    @ColumnInfo(name = "bittel_id")
    var bittelId: String? = null,
    @ColumnInfo(name = "smartphone_bittel_id")
    var smartphoneBittelId: String? = null,
    @ColumnInfo(name = "chat_user_id")
    var chatUserId: String? = null,
    @ColumnInfo(name = "lat")
    var lat: Double = 0.0,
    @ColumnInfo(name = "lon")
    var lon: Double = 0.0,
    @ColumnInfo(name = "online")
    var online: Boolean = false,
    @ColumnInfo(name = "pttEnabled")
    var pttEnabled: Boolean = true,
    @ColumnInfo(name = "updateTS")
    var lastUpdateTS: Long? = 0,
    @ColumnInfo(name = "isSOS")
    var isSOS: Boolean = false,
    @ColumnInfo(name = "isSniffer")
    var isSniffer: Boolean = false,
    @ColumnInfo(name = "is_bittel")
    var isBittel: Boolean = false,
    @ColumnInfo(name = "is_group")
    var isGroup: Boolean = false,
) {
    init {
        bittelId = bittelId?.lowercase(Locale.ROOT)
        smartphoneBittelId = smartphoneBittelId?.lowercase(Locale.ROOT)
    }
}

fun ChatContact.toAppContactEntity(): AppContactEntity = AppContactEntity(
    contactId = contactId,
    displayName = displayName,
    number = number,
    photoURI = photoURI,
    bittelId = bittelId,
    smartphoneBittelId = smartphoneBittelId,
    chatUserId = chatUserId,
    lat = lat,
    lon = lon,
    online = online,
    pttEnabled = pttEnabled,
    lastUpdateTS = lastUpdateTS,
    isSOS = isSOS,
    isSniffer = isSniffer,
    isBittel = isBittel,
    isGroup = isGroup,
)

fun AppContactEntity.toLegacyChatContact(): ChatContact = ChatContact(
    contactId = contactId,
    displayName = displayName,
    number = number,
    photoURI = photoURI,
    bittelId = bittelId,
    smartphoneBittelId = smartphoneBittelId,
    chatUserId = chatUserId,
    lat = lat,
    lon = lon,
    online = online,
    pttEnabled = pttEnabled,
    lastUpdateTS = lastUpdateTS,
    isSOS = isSOS,
    isSniffer = isSniffer,
    isBittel = isBittel,
    isGroup = isGroup,
)

