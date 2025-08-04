package com.commcrete.stardust.stardust.model

class StardustBatteryParser : StardustParser() {
    companion object{
        const val batteryLength = 1
    }
    fun parseBattery (bittelPackage: StardustPackage) : Int {
        try {
            bittelPackage.data?.let { intArray ->
                val byteArray = intArrayToByteArray(intArray.toMutableList())
                var offset = 0
                val bittelBatteryBytes = cutByteArray(byteArray,
                    batteryLength, offset)
                return byteArrayToInt(bittelBatteryBytes.reversedArray())
            }
        } catch ( e : Exception) {
            e.printStackTrace()
        }
        return 0
    }
}