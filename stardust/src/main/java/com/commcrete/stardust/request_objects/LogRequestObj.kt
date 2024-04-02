package com.commcrete.stardust.request_objects

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class User(
    @SerializedName("bittelId") val bittelId: String,
    @SerializedName("appId") val appId: String
)

data class DetailsData(
    @SerializedName("dstChannel") val dstChannel: String,
    @SerializedName("bytes") val bytes: String
)

data class Details(
    @SerializedName("location") val location: List<Double>,
    @SerializedName("data") val data: DetailsData
)

data class LogEntry(
    @SerializedName("from") val from: String,
    @SerializedName("user") val user: User,
    @SerializedName("logLevel") val logLevel: String,
    @SerializedName("event") val event: String,
    @SerializedName("message") val message: String,
    @SerializedName("details") val details: Details,
    @SerializedName("ts") val ts: Long
)

data class Logs(
    @SerializedName("logs") val logs: List<LogEntry>
)

fun LogEntry.toJson(): JsonObject {
    return Gson().fromJson(Gson().toJson(this), JsonObject::class.java)
}

fun Logs.toJson(): JsonObject {
    return Gson().fromJson(Gson().toJson(this), JsonObject::class.java)
}