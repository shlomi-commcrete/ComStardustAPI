package com.commcrete.stardust.room.logs

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.android.parcel.Parcelize


@Entity(tableName = "logs_table")
@Parcelize
data class LogObject (
    @PrimaryKey(autoGenerate = true)
    val logId : Int = 0,
    @ColumnInfo(name = "logWriter")
    val logWriter : String = "Android App",
    @ColumnInfo(name = "user")
    val user : String,
    @ColumnInfo(name = "logLevel")
    val logLevel : String,
    @ColumnInfo(name = "event")
    val event : String,
    @ColumnInfo(name = "message")
    val message : String = "",
    @ColumnInfo(name = "details")
    val details : String,
    @ColumnInfo(name = "createdAt")
    val createdAt : Long,
    @ColumnInfo(name = "updatedAt")
    val updatedAt : Long,
) : Parcelable

enum class LOG_LEVEL (val type : String) {
    INFO  ("INFO"),
    WARNING  ("WARNING"),
    ERROR  ("ERROR"),
}

enum class LOG_EVENT (val type : String) {
    LOCATION_RECEIVED  ("location_received"),
    LOCATION_SENT  ("location_sent"),
    LOCATION_POLLING  ("locationPolling"),
}