package com.commcrete.bittell.util.bittel_package.model

data class StardustFilePackage (
    val current : Int, var data: ByteArray, val isLast : Boolean = false
){
    fun toArrayInt(): Array<Int> {
        require(current in 0..65535) { "Current must fit in 2 bytes (0-65535)" }

        // Split `current` into two bytes
        val highByte = (current shr 8) and 0xFF // Extract the high byte
        val lowByte = current and 0xFF // Extract the low byte

        // Convert `data` ByteArray to an Array<Int>
        val dataAsIntArray = data.map { it.toInt() and 0xFF } // Convert each byte to unsigned int

        // Calculate the total length
        val totalLength = 2 + data.size // `2` for `current` split into two bytes

        // Combine the total length, `current` bytes, and `data` into a single array
        return arrayOf(totalLength, highByte, lowByte) + dataAsIntArray
    }

}