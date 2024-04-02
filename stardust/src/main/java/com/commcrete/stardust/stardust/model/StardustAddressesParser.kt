package com.commcrete.stardust.stardust.model

class StardustAddressesParser : StardustParser() {

    companion object{
        const val StardustIDBytesLength = 8
        const val smartphoneIDBytesLength =8
    }

    fun parseAddresses (StardustPackage: StardustPackage) : StardustAddressesPackage? {
        StardustPackage.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            val StardustIDBytes = cutByteArray(byteArray, StardustIDBytesLength, offset)
            offset += StardustIDBytesLength
            val smartphoneIDBytes = cutByteArray(byteArray,
                smartphoneIDBytesLength, offset)
            offset += smartphoneIDBytesLength
            return StardustAddressesPackage(
                stardustID = StardustIDBytes.reversedArray().toHex().substring(8,16),
                smartphoneID = smartphoneIDBytes.reversedArray().toHex().substring(8,16),
                emergencyID = "",

            )
        }
        return null
    }
}