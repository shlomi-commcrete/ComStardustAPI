package com.commcrete.bittell.util.bittel_package.model

import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustParser

class StardustFileResponseParser : StardustParser() {

    companion object{
        const val percentageLength = 1
    }

    fun parseFileResponse (bittelPackage: StardustPackage) : StardustFileResponsePackage? {
        bittelPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            val percentageBytes = cutByteArray(byteArray, percentageLength, offset)
            offset += percentageLength

            return StardustFileResponsePackage( percentage = byteArrayToInt(percentageBytes))

        }
        return null
    }
}