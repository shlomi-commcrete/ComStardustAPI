package com.commcrete.bittell.util.bittel_package.model

import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustParser

class StardustFileParser : StardustParser() {

    companion object{
        const val currentLength = 2
        const val sizeLength = 1
    }
    fun parseFile (bittelPackage: StardustPackage) : StardustFilePackage? {
        bittelPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            val sizeBytes = cutByteArray(byteArray, sizeLength, offset)
            offset += sizeLength
            val size = (byteArrayToUInt(sizeBytes) - currentLength.toUInt()).toInt()
            val currentBytes = cutByteArray(byteArray, currentLength, offset)
            offset += currentLength
            val dataBytes = cutByteArray(byteArray, size, offset)
            offset += size

            if(dataBytes.isEmpty()) {
                return parseFile2(bittelPackage)
            }else {
                return StardustFilePackage( current = byteArrayToInt(currentBytes.reversedArray()), data = dataBytes)
            }


        }
        return null
    }

    private fun parseFile2 (bittelPackage: StardustPackage) : StardustFilePackage? {
        bittelPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            val sizeBytes = bittelPackage.length
//            offset += sizeLength
            val size = (sizeBytes.minus(currentLength))
            val currentBytes = cutByteArray(byteArray, currentLength, offset)
            offset += currentLength
            val dataBytes = cutByteArray(byteArray, size, offset)
            offset += size

            return StardustFilePackage( current = byteArrayToInt(currentBytes.reversedArray()), data = dataBytes)

        }
        return null
    }
}