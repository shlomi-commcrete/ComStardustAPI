package com.commcrete.bittell.util.bittel_package.model

data class StardustFileStartPackage(
    val type: Int,
    val total: Int,
    val spare: Int,
    val spareData: Int
) {
    fun toArrayInt(): Array<Int> {
        require(type in 0..255) { "Type must fit in 1 byte (0-255)" }
        require(total in 0..65535) { "Total must fit in 2 bytes (0-65535)" }
        require(spare in 0..65535) { "Spare must fit in 2 bytes (0-65535)" }
        require(spareData in 0..255) { "SpareData must fit in 1 byte (0-255)" }

        val totalHighByte = (total shr 8) and 0xFF
        val totalLowByte = total and 0xFF

        val spareHighByte = (spare shr 8) and 0xFF
        val spareLowByte = spare and 0xFF

        return arrayOf(
            type,               // 1 byte
            totalHighByte,      // 1 byte
            totalLowByte,       // 1 byte
            spareHighByte,      // 1 byte
            spareLowByte,       // 1 byte
            spareData           // 1 byte (new)
        )
    }
}