package com.commcrete.stardust.room.legacy_db.contacts

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.new_db.contact.ContactEntity
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.DeviceEntity
import com.commcrete.stardust.room.new_db.contact.FullContactData
import kotlinx.android.parcel.Parcelize
import java.util.Locale

@Entity(tableName = "contacts", indices = [Index(
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

    fun toFullContactData(): FullContactData? {
        val normalizedBittelId = bittelId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSmartphoneId = smartphoneBittelId?.trim()?.takeIf { it.isNotEmpty() }

        return when {
            isGroup && normalizedBittelId != null -> {
                FullContactData.Group(
                    contact = ContactEntity(
                        name = displayName,
                        image = photoURI,
                        type = ContactType.GROUP,
                        createdAt = System.currentTimeMillis(),
                        lastUpdatedAt = lastUpdateAt,
                    ),
                    groupId = normalizedBittelId,
                )
            }
            isBittel || (normalizedBittelId != null && normalizedSmartphoneId == null) -> {
                normalizedBittelId?.let { deviceId ->
                    FullContactData.Device(
                        contact = ContactEntity(
                            name = displayName,
                            image = photoURI,
                            type = ContactType.DEVICE,
                            createdAt = System.currentTimeMillis(),
                            lastUpdatedAt = lastUpdateAt,
                        ),
                        deviceId = deviceId,
                        deviceData = DeviceEntity(id = deviceId),
                    )
                }
            }
            normalizedSmartphoneId != null -> {
                FullContactData.User(
                    contact = ContactEntity(
                        name = displayName,
                        image = photoURI,
                        type = ContactType.USER,
                        createdAt = System.currentTimeMillis(),
                        lastUpdatedAt = lastUpdateAt,
                    ),
                    userId = normalizedSmartphoneId,
                    devices = normalizedBittelId?.let { deviceId ->
                        listOf(DeviceEntity(id = deviceId))
                    } ?: emptyList(),
                )
            }
            else -> null
        }
    }

}


