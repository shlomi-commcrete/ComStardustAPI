package com.commcrete.stardust.stardust.model

import com.example.mylibrary.R
import java.util.Date

class StardustLogParser : StardustParser() {

    companion object{
        const val logCounterBytesLength = 4
        const val sysClockBytesLength = 4
        const val gpsTimeBytesLength = 6
        const val typeBytesLength = 1
        const val codeBytesLength = 2
        const val sizeBytesLength = 1
    }

    fun parseLog (StardustPackage: StardustPackage) : StardustLogPackage? {
        StardustPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            val logCounterBytes = cutByteArray(byteArray, logCounterBytesLength, offset)
            offset += logCounterBytesLength
            val sysClockBytes = cutByteArray(byteArray, sysClockBytesLength, offset)
            offset += sysClockBytesLength
            val gpsTimeBytes = cutByteArray(byteArray, gpsTimeBytesLength, offset)
            offset += gpsTimeBytesLength
            val typeBytes = cutByteArray(byteArray, typeBytesLength, offset)
            offset += typeBytesLength
            val codeBytes = cutByteArray(byteArray, codeBytesLength, offset)
            offset += codeBytesLength
            val sizeBytes = cutByteArray(byteArray, sizeBytesLength, offset)
            offset += sizeBytesLength
            val size = sizeBytes[0].toInt()
            val dataBytes = cutByteArray(byteArray, size, offset)

            val code = PARSE_CODE_TYPE.fromId(codeBytes[0].toInt())
            return StardustLogPackage(
                counter = byteArrayToUInt32(logCounterBytes.reversedArray()).toInt(),
                syncClock = byteArrayToUInt32(sysClockBytes.reversedArray()).toInt(),
                gpsTime = StardustLogGPSParser().parseTime(gpsTimeBytes),
                type = PARSE_DATA_TYPE.fromId(typeBytes[0].toInt()),
                code = code ,
                size = size,
                data = dataBytes,
            )
        }
        return null
    }

    private fun getData () {

    }

    class StardustLogGPSParser : StardustParser() {
        companion object{
            const val yearBytesLength = 1
            const val monthBytesLength = 1
            const val dayBytesLength = 1
            const val hourBytesLength = 1
            const val minBytesLength = 1
            const val secondBytesLength = 1
        }

        fun parseTime (byteArray : ByteArray) : StardustGPSTimePackage {
            var offset = 0
            val yearBytes = cutByteArray(byteArray, yearBytesLength, offset)
            offset += yearBytesLength
            val monthBytes = cutByteArray(byteArray, monthBytesLength, offset)
            offset += monthBytesLength
            val dayBytes = cutByteArray(byteArray, dayBytesLength, offset)
            offset += dayBytesLength
            val hourBytes = cutByteArray(byteArray, hourBytesLength, offset)
            offset += hourBytesLength
            val minBytes = cutByteArray(byteArray, minBytesLength, offset)
            offset += minBytesLength
            val secondsBytes = cutByteArray(byteArray, secondBytesLength, offset)

            return StardustGPSTimePackage(
                year = yearBytes[0].toString(),
                month = monthBytes[0].toString(),
                day = dayBytes[0].toString(),
                hour = hourBytes[0].toString(),
                minute = minBytes[0].toString(),
                second = secondsBytes[0].toString(),
                appTs = Date().time
            )
        }
    }

    enum class PARSE_CODE_TYPE (val id : Int){
        STRING (0),
        DATA(1);

        companion object {
            fun fromId(id: Int): PARSE_CODE_TYPE? {
                return values().find { it.id == id }
            }
        }
    }

    enum class PARSE_DATA_TYPE (val id : Int, val color : Int){
        DEBUG (0, R.color.commcrete_blue),
        INFO(1, R.color.black),
        WARNING(2, R.color.commcrete_orange),
        ERROR(3, R.color.textError);

        companion object {
            fun fromId(id: Int): PARSE_DATA_TYPE? {
                return values().find { it.id == id }
            }
        }
    }
}