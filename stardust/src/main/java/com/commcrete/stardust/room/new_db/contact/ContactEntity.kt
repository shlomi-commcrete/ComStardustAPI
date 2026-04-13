package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import java.util.Locale

@Entity(
    tableName = "contacts_table",
    indices = [Index(value = ["id"], unique = true)]
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,
    @ColumnInfo(name = "name")
    val name: String = "",
    @ColumnInfo(name = "image")
    val image: String? = null,
    @ColumnInfo(name = "type")
    val type: ContactType = ContactType.USER,
    @ColumnInfo(name = "created_at_ms")
    val createdAt: Long? = 0,
    @ColumnInfo(name = "last_updated_ms")
    val lastUpdatedAt: Long? = 0,
)

fun ChatContact.toProfileEntity(resolvedContactId: Int = contactId): ContactEntity =
    ContactEntity(
        id = resolvedContactId,
        name = displayName,
        image = photoURI,
        lastUpdatedAt = lastUpdateAt,
        type = when {
            isGroup -> ContactType.GROUP
            isBittel || (smartphoneBittelId.isNullOrBlank() && hasDevice()) -> ContactType.DEVICE
            else -> ContactType.USER
        },
    )

fun ChatContact.deviceEntries(): List<Pair<Int, String>> = listOfNotNull(
    normalizeId(bittelId)?.let { 0 to it },
).distinctBy { it.second }

fun ChatContact.normalizedUserId(): String? = normalizeId(smartphoneBittelId)

fun ChatContact.hasDevice(): Boolean =
    !normalizeId(bittelId).isNullOrBlank()

private fun normalizeId(value: String?): String? =
    value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }

