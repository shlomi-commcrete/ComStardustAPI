package com.commcrete.stardust.location


const val INT16_MAX = 32767L
const val LAT_FACTOR = 8388607L
const val LON_FACTOR = 8388607L
const val ALT_OFFSET = 1000L
const val ALT_BELOW_LOWER_SCALE_ERR = INT16_MAX - 1
const val ALT_ABOVE_UPPER_SCALE_ERR = INT16_MAX
const val MAX_ALT = INT16_MAX - ALT_OFFSET
const val ALTITUDE_ERR = INT16_MAX - 2

fun packGPS_Coord(lat: Double, lon: Double, alt16: Int): Long {
    var result : Long = 0
    var alt: Long

    val intLat = (Math.floor(lat.times(LAT_FACTOR)).toLong() shr 8)
    val intLon = (Math.floor(lon * LON_FACTOR).toLong() shr 7)

    alt = when {
        alt16 != ALTITUDE_ERR.toInt() -> when {
            alt16 < -ALT_OFFSET -> ALT_BELOW_LOWER_SCALE_ERR
            alt16 >= MAX_ALT -> ALT_ABOVE_UPPER_SCALE_ERR
            else -> (alt16 + ALT_OFFSET).toLong()
        }
        else -> ALTITUDE_ERR
    }

    result = result or alt
    result = result and 0x7fff
    result = result or (intLon shl 15)
    result = result and 0xffffffffff
    result = result or (intLat shl 40)

    return result
}

fun unPackGPS_Coord(gpsData: Long): Triple<Double, Double, Long> {
    var data = gpsData
    val altValue = data and 0x7fff
    var alt = altValue

    if (alt != ALT_ABOVE_UPPER_SCALE_ERR && alt != ALT_BELOW_LOWER_SCALE_ERR) {
        alt -= ALT_OFFSET
    }

    data = data shr 15
    val intLon = ((data and 0x1ffffff) shl 7).toInt()
    val lon = intLon.toDouble() / LON_FACTOR.toDouble()

    data = data shr 25
    val intLat = ((data and 0xffffff) shl 8).toInt()
    val lat = intLat.toDouble() / LAT_FACTOR.toDouble()

    return Triple(lat, lon, alt)
}