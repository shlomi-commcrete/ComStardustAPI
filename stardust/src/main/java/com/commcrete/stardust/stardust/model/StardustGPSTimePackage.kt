package com.commcrete.stardust.stardust.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StardustGPSTimePackage(
    val year: String, val month: String,
    val day: String, val hour: String, val minute: String,
    val second: String, val appTs : Long) {
    override fun toString(): String {
        val date = timestampToDateWithMillis(appTs)
        return "$day/$month/20$year $hour:$minute:$second\nappTS : $date"
    }
}

fun timestampToDateWithMillis(timestamp: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    return formatter.format(Date(timestamp))
}