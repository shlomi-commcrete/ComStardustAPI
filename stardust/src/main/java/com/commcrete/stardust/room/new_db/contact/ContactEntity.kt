package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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

