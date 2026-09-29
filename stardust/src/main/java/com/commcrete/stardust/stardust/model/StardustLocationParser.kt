package com.commcrete.stardust.stardust.model

import android.location.Location
import android.util.Log
import com.commcrete.stardust.room.new_db.message.SosType
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.util.CoordinatesUtil
import com.commcrete.stardust.util.SOSUtils
import java.util.Date

class StardustLocationParser : StardustParser() {

    companion object{

        //todo change 24 bits lat 25 bit lon 15 bits alt
        const val locationLength = 8
        const val sosTypeLength = 1
        private const val LOG_TAG = "SOSDebug"
    }

    fun parseLocation(stardustPackage: StardustPackage): LocationPackage? {
        val intArray = stardustPackage.data ?: return null
        val byteArray = intArrayToByteArray(intArray.toMutableList())
        val offset = 0
        if (byteArray.size < offset + locationLength) return null

        val locationBytes = cutByteArray(byteArray, locationLength, offset)
        val locations = CoordinatesUtil().unpackLocation(locationBytes)

        return LocationPackage(
            location = Location(stardustPackage.senderId).apply {
                latitude = locations[0].toDouble()
                longitude = locations[1].toDouble()
                altitude = locations[2].toDouble()
            },
            date = Date()
        )
    }

    /**
     * Reads the SOS REPORT layout: `['S','O','S'][type][8-byte location]`, so at least
     * 12 bytes. A real SOS carries only the location and must go through [parseSOSReal];
     * handing one to this function is what silently dropped it, hence the log on reject.
     */
    fun parseSOS(stardustPackage: StardustPackage): SOSPackage? {
        val intArray = stardustPackage.data ?: run {
            Log.w(LOG_TAG, "parseSOS: no data on ${stardustPackage.stardustOpCode}")
            return null
        }
        val byteArray = intArrayToByteArray(intArray.toMutableList())

        val sosTypeOffset = 3
        val locationOffset = sosTypeOffset + sosTypeLength
        if (byteArray.size < locationOffset + locationLength) {
            Log.w(LOG_TAG,
                "parseSOS: payload too short on ${stardustPackage.stardustOpCode} — " +
                    "${byteArray.size} bytes, needs ${locationOffset + locationLength}"
            )
            return null
        }

        val sosTypeBytes = cutByteArray(byteArray, sosTypeLength, sosTypeOffset)
        val locationBytes = cutByteArray(byteArray, locationLength, locationOffset)
        val locations = CoordinatesUtil().unpackLocation(locationBytes)

        return SOSPackage(
            location = Location(stardustPackage.senderId).apply {
                latitude = locations[0].toDouble()
                longitude = locations[1].toDouble()
                altitude = locations[2].toDouble()
            },
            date = Date(),
            sosType = byteArrayToInt(sosTypeBytes).let { SOSUtils.SOS_REPORT_TYPES.fromCode(it) }
        )
    }

    /**
     * Reads the REAL-SOS layout: an 8-byte packed location at offset 0 and nothing else,
     * with no report type to carry. See [parseSOS] for the report layout.
     */
    fun parseSOSReal(stardustPackage: StardustPackage): SOSPackage? {
        stardustPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            val offset = 0
            if (byteArray.size < offset + locationLength) {
                Log.w(LOG_TAG,
                    "parseSOSReal: payload too short on ${stardustPackage.stardustOpCode} — " +
                        "${byteArray.size} bytes, needs $locationLength"
                )
                return null
            }
            val locationBytes = cutByteArray(byteArray, locationLength, offset)
            val locations = CoordinatesUtil().unpackLocation(locationBytes)

            return SOSPackage(
                location = Location(stardustPackage.senderId).apply {
                    latitude = locations[0].toDouble()
                    longitude = locations[1].toDouble()
                    altitude = locations[2].toDouble()
                },
                date = Date(),
                sosType = null
            )
        }
        return null
    }

    fun getEmptyLocation() : Array<Int>{
        val byteArray = ByteArray(13)
        var loop = 0
        for (byte in byteArray){
            byteArray[loop] = -1
            loop++
        }
        byteArray[0] = 12
        return StardustPackageUtils.byteArrayToIntArray(byteArray)
    }

    fun getLocation(location: Location) : Array<Int>{
        val size = byteArrayOf(12)
        val lat = floatToByteArray(location.latitude.toFloat())
        val lon = floatToByteArray(location.longitude.toFloat())
        val alt = intToByteArray(location.altitude.toInt())
        return StardustPackageUtils.byteArrayToIntArray(combineByteArrays(size, lat, lon, alt))
    }

}