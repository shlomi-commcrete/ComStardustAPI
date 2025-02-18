package com.commcrete.stardust.location


object Coordinatess {

    const val ALTITUDE_ERR = 0x7FFF // Error value for altitude
    const val COORDINATES_ERR = 0x7FFF // Same as ALTITUDE_ERR
    const val MIN_ALT = -500 // Minimum altitude
    const val MAX_ALT = 5000 // Maximum altitude
    const val ALT_OFFSET = 1000 // Offset for altitude encoding

    /**
     * Packs latitude, longitude, and altitude into a 64-bit integer
     */

}

fun packGPS_Coord(lat: Double, lon: Double, alt16: Int): Long {
    var alt = alt16

    // Validate latitude and longitude
    if (lat !in -90.0..90.0) {
        alt = Coordinatess.ALTITUDE_ERR
    }
    if (lon !in -180.0..180.0) {
        alt = Coordinatess.ALTITUDE_ERR
    }

    // Scale and convert to integers
    val scaledLat = ((lat + 90) * ((1 shl 24) - 1) / 180).toInt() // Scale to 24-bit range
    val scaledLon = ((lon + 180) * ((1 shl 25) - 2) / 360).toInt() // Scale to 25-bit range

    // Handle altitude
    val altitude: UShort = when {
        alt != Coordinatess.COORDINATES_ERR -> {
            when {
                alt < Coordinatess.MIN_ALT -> Coordinatess.COORDINATES_ERR.toUShort()
                alt > Coordinatess.MAX_ALT -> Coordinatess.COORDINATES_ERR.toUShort()
                else -> (alt + Coordinatess.ALT_OFFSET).toUShort()
            }
        }
        else -> alt.toUShort() and 0x7FFFu // Ensure only 15 bits
    }

    // Pack into a 64-bit long
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

    // Convert back to original ranges
    val lat = scaledLat * 180.0 / ((1 shl 24) - 1) - 90.0
    val lon = scaledLon * 360.0 / ((1 shl 25) - 2) - 180.0

    // Process altitude
    val altitude = if (rawAlt != Coordinatess.COORDINATES_ERR.toUShort()) {
        (rawAlt - Coordinatess.ALT_OFFSET.toUShort()).toLong()
    } else {
        rawAlt.toLong()
    }

    return Triple(lat, lon, altitude)
}
