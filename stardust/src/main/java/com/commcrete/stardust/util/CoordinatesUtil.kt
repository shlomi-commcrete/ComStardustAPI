package com.commcrete.stardust.util

import android.location.Location
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.location.Coordinates

class CoordinatesUtil {

    var lat = 32.00532341422646f
    var lon = 34.95738549095443f
    var alt = (0x7FFF / 4).toShort()

    val TAG = "CoordinatesTest"

    fun packLocation(location: Location) : Array<Int>{
        val coordinate = Coordinates()
        val coordBytes = ByteArray(8)
        coordinate.packGPSCoordData(location.latitude.toFloat(), location.longitude.toFloat()
            , location.altitude.toInt().toShort(), coordBytes)
        return StardustPackageUtils.byteArrayToIntArray(coordBytes.reversedArray())
    }

    fun packEmptyLocation() : Array<Int>{
        val coordinate = Coordinates()
        val coordBytes = ByteArray(8)
        coordinate.packGPSCoordData(200.toFloat(), 200.toFloat()
            , ((-1000).toShort()), coordBytes)
        return StardustPackageUtils.byteArrayToIntArray(coordBytes.reversedArray())
    }

    fun unpackLocation(byteArray: ByteArray): FloatArray {
        val unpackedLatLonAlt = FloatArray(3)
        val coordinate = Coordinates()
        coordinate.unpackGPSCoordData(byteArray.reversedArray(), unpackedLatLonAlt)
        return unpackedLatLonAlt
    }
}