package com.commcrete.stardust.stardust.model

class StardustAddressesParser : StardustParser() {

    companion object{
        const val StardustIDBytesLength = 8
        const val smartphoneIDBytesLength = 8
    }

    fun parseAddresses(pkg: StardustPackage): StardustAddressesPackage? {
        pkg.data?.let { intArray ->
            val byteArray = intArrayToByteArray(intArray.toMutableList())
            var offset = 0
            val StardustIDBytes = cutByteArray(byteArray, StardustIDBytesLength, offset)
            offset += StardustIDBytesLength
            val smartphoneIDBytes = cutByteArray(byteArray, smartphoneIDBytesLength, offset)
            offset += smartphoneIDBytesLength

            val stardustHex = StardustIDBytes.reversedArray().toHex()
            val smartphoneHex = smartphoneIDBytes.reversedArray().toHex()

            // Guard against malformed/short hex output before substring(8,16).
            if (stardustHex.length < 16 || smartphoneHex.length < 16) {
                return null
            }

            return StardustAddressesPackage(
                stardustID = stardustHex.substring(8, 16),
                smartphoneID = smartphoneHex.substring(8, 16),
                emergencyID = "",
            )
        }
        return null
    }
}