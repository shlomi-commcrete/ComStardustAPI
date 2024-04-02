package com.commcrete.stardust.stardust.model

import android.location.Location
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.util.CoordinatesUtil

class StardustLocationParser : StardustParser() {

    companion object{

        //todo change 24 bits lat 25 bit lon 15 bits alt
        const val locationLength = 8
        const val sosTypeLength = 1
    }

    fun parseLocation (StardustPackage: StardustPackage) : StardustLocationPackage? {
        StardustPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            val locationBytes = cutByteArray(byteArray, locationLength, offset)
            val locations = CoordinatesUtil().unpackLocation(locationBytes)

            return StardustLocationPackage(
                latitude = locations[0],
                longitude = locations[1],
                height = locations[2].toInt(),
                year = 0,
                month = 0,
                day = 0,
                hour = 0,
                minute = 0,
                second = 0
            )
        }
        return null
    }

    fun parseSOS (StardustPackage: StardustPackage) : StardustSOSPackage? {
        StardustPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 3
            val sosTypeBytes = cutByteArray(byteArray, sosTypeLength, offset)
            offset = offset.plus(sosTypeLength)
            val locationBytes = cutByteArray(byteArray, locationLength, offset)
            val locations = CoordinatesUtil().unpackLocation(locationBytes)

            return StardustSOSPackage(
                latitude = locations[0],
                longitude = locations[1],
                height = locations[2].toInt(),
                year = 0,
                month = 0,
                day = 0,
                hour = 0,
                minute = 0,
                second = 0,
                sosType = byteArrayToInt(sosTypeBytes)
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