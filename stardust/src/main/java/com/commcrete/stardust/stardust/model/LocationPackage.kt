package com.commcrete.stardust.stardust.model

import android.location.Location
import com.commcrete.stardust.room.new_db.message.SosType
import com.commcrete.stardust.util.SOSUtils
import java.util.Date

data class LocationPackage(val location: Location, val date: Date)
data class SOSPackage(val location: Location, val date: Date, val sosType : SOSUtils.SOS_REPORT_TYPES? = null)

fun Location.asString(): String {
    return "latitude: $latitude\n" +
            "longitude: $longitude\n" +
            "altitude: $altitude"
}