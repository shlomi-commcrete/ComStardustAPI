package com.commcrete.stardust.stardust.model

class StardustGroupStatusParser : StardustParser() {
    companion object{
        const val groupStatusLength = 1
    }
    fun parseGroupStatus (bittelPackage: StardustPackage) : Boolean {
        try {
            bittelPackage.data?.let { intArray ->
                val byteArray = intArrayToByteArray(intArray.toMutableList())
                var offset = 1
                val groupStatusBytes = cutByteArray(byteArray,
                    groupStatusLength, offset)
                val intValue = byteArrayToInt(groupStatusBytes.reversedArray())
                return intValue == 0
            }
        } catch ( e : Exception) {
            e.printStackTrace()
        }
        return false
    }
}