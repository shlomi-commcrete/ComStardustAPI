package com.commcrete.stardust.stardust.model

import com.commcrete.bittell.util.text_utils.getAsciiValue
import com.commcrete.stardust.util.FileUtils

data class StardustFileStartPackage(
    val type: Int,
    val total: Int,
    val spare: Int,
    val spareData: Int,
    val fileEnding : String,
    val fileName : String
) {
    fun toArrayInt(): Array<Int> {
        require(type in 0..255) { "Type must fit in 1 byte (0–255)" }
        require(total in 0..65535) { "Total must fit in 2 bytes (0–65535)" }
        require(spare in 0..65535) { "Spare must fit in 2 bytes (0–65535)" }
        require(spareData in 0..255) { "SpareData must fit in 1 byte (0–255)" }

        val totalHighByte = (total shr 8) and 0xFF
        val totalLowByte = total and 0xFF
        val spareHighByte = (spare shr 8) and 0xFF
        val spareLowByte = spare and 0xFF

        val header = arrayOf(type, totalHighByte, totalLowByte, spareHighByte, spareLowByte, spareData)

        // Convert strings to UTF-8 bytes

        val fileEndingBytes = getAsciiValue(fileEnding)
        val fileNameBytes = getAsciiValue(fileName)

        // Pad or truncate to fixed sizes
        val paddedEnding = ByteArray(5)
        val paddedName = ByteArray(50)
        fileEndingBytes.copyInto(paddedEnding, endIndex = minOf(fileEndingBytes.size, 5))
        fileNameBytes.copyInto(paddedName, endIndex = minOf(fileNameBytes.size, 50))

        val fileEndingInts = paddedEnding.map { it.toInt() and 0xFF }
        val fileNameInts = paddedName.map { it.toInt() and 0xFF }

        // Return header + ending + name (no dynamic length fields)
        return header + fileEndingInts.toTypedArray() + fileNameInts.toTypedArray()
    }

    val fileType: FileUtils.FileType = when (type) {
        0 -> FileUtils.FileType.FILE
        else -> FileUtils.FileType.IMAGE
    }

}