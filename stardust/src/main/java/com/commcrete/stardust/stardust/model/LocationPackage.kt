package com.commcrete.stardust.stardust.model

import android.location.Location
import java.util.Date

data class LocationPackage(val location: Location, val date: Date)
data class SOSPackage(val location: Location, val date: Date, val sosType : Int)

fun Location.asString(): String {
    return "latitude: $latitude\n" +
            "longitude: $longitude\n" +
            "altitude: $altitude"
}