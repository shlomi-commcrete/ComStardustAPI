package com.commcrete.stardust.room.new_db.contact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "devices",
)
data class DeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "model")
    val model: String? = null,
    @ColumnInfo(name = "serial")
    val serial: String? = null,
) {
    init {
        require(id.isNotBlank()) { "deviceId cannot be blank" }
    }
}

