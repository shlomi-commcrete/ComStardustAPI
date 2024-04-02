package com.commcrete.stardust.room.beetle_users

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bittel_user_table", indices = [androidx.room.Index(
    value = ["phone"],
    unique = true
)])
data class BittelUser (
    @PrimaryKey (autoGenerate = true)
    val id : Int = 0,
    @ColumnInfo(name = "bittel_id")
    val bittelId : String,
    @ColumnInfo(name = "phone")
    val phone : String
)