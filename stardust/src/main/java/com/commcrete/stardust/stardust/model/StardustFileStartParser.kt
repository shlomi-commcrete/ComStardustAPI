package com.commcrete.bittell.util.bittel_package.model

import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustParser


class StardustFileStartParser : StardustParser() {

    companion object{
        const val initLength = 4
        const val initLength2 = 3
        const val typeLength = 1
        const val totalLength = 2
        const val spareLength = 2
    }

    fun parseFileStart (bittelPackage: StardustPackage) : StardustFileStartPackage? {
        bittelPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            offset += initLength
            val typeBytes = cutByteArray(byteArray, typeLength, offset)
            offset += typeLength
            val totalBytes = cutByteArray(byteArray, totalLength, offset)
            offset += totalLength
            val spareBytes = cutByteArray(byteArray, spareLength, offset)
            offset += spareLength

            return StardustFileStartPackage( type = byteArrayToInt(typeBytes), total =
            byteArrayToUInt(totalBytes.reversedArray()).toInt(),
                spare =
                byteArrayToUInt(totalBytes.reversedArray()).toInt())

        }
        return null
    }

    fun parseFileStar2 (bittelPackage: StardustPackage) : StardustFileStartPackage? {
        bittelPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            offset += initLength2
            val typeBytes = cutByteArray(byteArray, typeLength, offset)
            offset += typeLength
            val totalBytes = cutByteArray(byteArray, totalLength, offset)
            offset += totalLength

            return StardustFileStartPackage( type = byteArrayToInt(typeBytes), total =
            byteArrayToUInt(totalBytes.reversedArray()).toInt(),
                spare =
                byteArrayToUInt(totalBytes.reversedArray()).toInt())

        }
        return null
    }

    enum class FileTypeEnum (val type : Int){
        TXT (0),
        JPG(1)
    }
}