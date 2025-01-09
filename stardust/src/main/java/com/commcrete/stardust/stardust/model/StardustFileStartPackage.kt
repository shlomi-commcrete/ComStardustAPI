package com.commcrete.bittell.util.bittel_package.model

data class StardustFileStartPackage (
    val type : Int, val total : Int
){
    fun toArrayInt(): Array<Int> {
        require(type in 0..255) { "Type must fit in 1 byte (0-255)" }
        require(total in 0..65535) { "Total must fit in 2 bytes (0-65535)" }

        val highByte = (total shr 8) and 0xFF // Extract the high byte
        val lowByte = total and 0xFF // Extract the low byte

        return arrayOf(type, highByte, lowByte)
    }
}