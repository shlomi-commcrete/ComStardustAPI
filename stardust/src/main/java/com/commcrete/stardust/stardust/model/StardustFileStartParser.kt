package com.commcrete.bittell.util.bittel_package.model

import com.commcrete.bittell.util.text_utils.getAsciiValue
import com.commcrete.bittell.util.text_utils.getCharValue
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustParser


class StardustFileStartParser : StardustParser() {

    companion object{
        const val initLength = 4
        const val initLength2 = 3
        const val typeLength = 1
        const val totalLength = 2
        const val spareLength = 2
        const val spareDataLength = 1
        const val fileEndingLength = 5
        const val fileNameLength = 50
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
            val spareDataBytes = cutByteArray(byteArray, spareDataLength, offset)
            offset += spareDataLength

            //file Data
            val fileEndingBytes = cutByteArray(byteArray, fileEndingLength, offset)
            offset += fileEndingLength
            val fileNameBytes = cutByteArray(byteArray, fileNameLength, offset)
            offset += fileNameLength

            return StardustFileStartPackage( type = byteArrayToInt(typeBytes), total =
            byteArrayToUInt(totalBytes.reversedArray()).toInt(),
                spare = byteArrayToUInt(spareBytes.reversedArray()).toInt(),
                spareData = byteArrayToUInt(spareDataBytes.reversedArray()).toInt(),
                fileName = getCharValue(String(removeZeros(fileNameBytes)) ) ,
                fileEnding = getCharValue(String(removeZeros(fileEndingBytes))) )

        }
        return null
    }

    fun removeZeros(input: ByteArray): ByteArray {
        return input.filter { it != 0.toByte() }.toByteArray()
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
            val spareBytes = cutByteArray(byteArray, spareLength, offset)
            offset += spareLength
            val spareDataBytes = cutByteArray(byteArray, spareDataLength, offset)
            offset += spareDataLength

            //file Data
            val fileEndingBytes = cutByteArray(byteArray, fileEndingLength, offset)
            offset += fileEndingLength
            val fileNameBytes = cutByteArray(byteArray, fileNameLength, offset)
            offset += fileNameLength


            return StardustFileStartPackage( type = byteArrayToInt(typeBytes), total =
            byteArrayToUInt(totalBytes.reversedArray()).toInt(),
                spare = byteArrayToUInt(spareBytes.reversedArray()).toInt(),
                spareData = byteArrayToUInt(spareDataBytes.reversedArray()).toInt(),
                fileName = getCharValue(String(removeZeros(fileNameBytes)) ) ,
                fileEnding = getCharValue(String(removeZeros(fileEndingBytes))) )

        }
        return null
    }

    enum class FileTypeEnum (val type : Int){
        TXT (0),
        JPG(1),
    }
}