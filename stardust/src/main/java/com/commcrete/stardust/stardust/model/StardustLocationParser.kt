package com.commcrete.stardust.stardust.model

import android.location.Location
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.util.CoordinatesUtil
import java.util.Date

class StardustLocationParser : StardustParser() {

    companion object{

        //todo change 24 bits lat 25 bit lon 15 bits alt
        const val locationLength = 8
        const val sosTypeLength = 1
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

    fun parseSOS(stardustPackage: StardustPackage): SOSPackage? {
        val intArray = stardustPackage.data ?: return null
        val byteArray = intArrayToByteArray(intArray.toMutableList())

        val sosTypeOffset = 3
        val locationOffset = sosTypeOffset + sosTypeLength
        if (byteArray.size < locationOffset + locationLength) return null

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
            sosType = byteArrayToInt(sosTypeBytes)
        )
    }

    fun parseSOSReal(stardustPackage: StardustPackage): SOSPackage? {
        stardustPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            val offset = 0
            if (byteArray.size < offset + locationLength) return null
            val locationBytes = cutByteArray(byteArray, locationLength, offset)
            val locations = CoordinatesUtil().unpackLocation(locationBytes)

            return SOSPackage(
                location = Location(stardustPackage.senderId).apply {
                    latitude = locations[0].toDouble()
                    longitude = locations[1].toDouble()
                    altitude = locations[2].toDouble()
                },
                date = Date(),
                sosType = 0
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