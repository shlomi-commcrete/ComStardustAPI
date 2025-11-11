package com.commcrete.stardust.stardust.model

data class StardustFileStartPackage(
    val type: Int,
    val total: Int,
    val spare: Int,
    val spareData: Int,
    val fileEnding : String,
    val fileName : String
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

        val header = arrayOf(
            type,
            totalHighByte,
            totalLowByte,
            spareHighByte,
            spareLowByte,
            spareData
        )

        val fileEndingBytes = fileEnding.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }
        val fileNameBytes = fileName.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }

        val fileEndingLength = fileEndingBytes.size
        val fileNameLength = fileNameBytes.size

        return header +
                arrayOf(fileEndingLength) +
                fileEndingBytes.toTypedArray() +
                arrayOf(fileNameLength) +
                fileNameBytes.toTypedArray()
    }
}