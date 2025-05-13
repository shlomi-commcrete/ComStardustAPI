package com.commcrete.stardust.location

import com.commcrete.stardust.location.Coordinatess.ALTITUDE_ERR
import com.commcrete.stardust.location.Coordinatess.ALT_OFFSET
import com.commcrete.stardust.location.Coordinatess.COORDINATES_ERR
import com.commcrete.stardust.location.Coordinatess.MAX_ALT
import com.commcrete.stardust.location.Coordinatess.MIN_ALT
import kotlin.math.floor


object Coordinatess {

    const val ALTITUDE_ERR = 0x7FFF // Error value for altitude
    const val COORDINATES_ERR = 0x7FFF // Same as ALTITUDE_ERR
    const val MIN_ALT = -1000 // Minimum altitude
    const val ALT_OFFSET = 1000 // Offset for altitude encoding
    const val MAX_ALT = 32767 - ALT_OFFSET // Maximum altitude

    private const val LAT_RES = 24
    private const val LON_RES = 25
    private const val ALT_ABOVE_UPPER_SCALE_ERR = Short.MAX_VALUE.toInt()
    private const val ALT_BELOW_LOWER_SCALE_ERR = Short.MAX_VALUE - 1
    private const val LAT_FACTOR = 8388607.0f
    private const val LON_FACTOR = 8388607.0f
    private const val LATITUDE_ERR = 200.0f
    private const val LONGITUDE_ERR = 200.0f

    /**
     * Packs latitude, longitude, and altitude into a 64-bit integer
     */

}

fun packGPS_Coord(lat: Double, lon: Double, alt16: Int): Long {
    var alt = alt16

    // Validate latitude and longitude
    if (lat !in -90.0..90.0) {
        alt = ALTITUDE_ERR
    }
    if (lon !in -180.0..180.0) {
        alt = ALTITUDE_ERR
    }

    // Scale and convert to integers (explicitly floor like JS)
    val scaledLat = floor(((lat + 90) * ((1 shl 24) - 1) / 180)).toInt() // 24-bit
    val scaledLon = floor(((lon + 180) * ((1 shl 25) - 2) / 360)).toInt() // 25-bit

    // Handle altitude
    val altitude: UShort = when {
        alt != COORDINATES_ERR -> {
            when {
                alt < MIN_ALT -> COORDINATES_ERR.toUShort()
                alt > MAX_ALT -> COORDINATES_ERR.toUShort()
                else -> (alt + ALT_OFFSET).toUShort()
            }
        }
        else -> alt.toUShort() and 0x7FFFu
    }

    return (scaledLat.toLong() shl 40) or (scaledLon.toLong() shl 15) or altitude.toLong()
}

/**
 * Unpacks a 64-bit integer into latitude, longitude, and altitude
 */
fun unPackGPS_Coord(gpsData: Long): Triple<Double, Double, Long> {
    // Extract values
    val scaledLat = ((gpsData shr 40) and 0xFFFFFF).toInt() // 24 bits for latitude
    val scaledLon = ((gpsData shr 15) and 0x1FFFFFF).toInt() // 25 bits for longitude
    val rawAlt = (gpsData and 0x7FFF).toUShort() // 15 bits for altitude
    val rawAltInt = rawAlt.toInt()
    val offset = Coordinatess.ALT_OFFSET.toInt()
    val err = Coordinatess.COORDINATES_ERR.toInt()

    // Convert back to original ranges
    val lat = scaledLat * 180.0 / ((1 shl 24) - 1) - 90.0
    val lon = scaledLon * 360.0 / ((1 shl 25) - 2) - 180.0

    // Process altitude

    val altitude = if (rawAltInt != err) {
        (rawAltInt - offset).toLong()
    } else {
        rawAltInt.toLong()
    }

    return Triple(lat, lon, altitude)
}
