package com.commcrete.stardust.stardust.model

import com.commcrete.bittell.util.text_utils.getAsciiValue
import com.commcrete.stardust.util.FileUtils

data class StardustFileStartPackage(
    val type: Int,
    val total: Int,
    val spare: Int,
    val spareData: Int,
    val fileEnding : String,
    val fileName : String,
    /**
     * The file's size in bytes before it was gzipped — for an image, which is not compressed,
     * the size as sent. 0 means unknown: a sender that predates the field, or a size past
     * [MAX_ORIGINAL_SIZE]. Last and defaulted so existing constructor calls keep compiling.
     */
    val originalSize: Int = 0,
) {
    fun toArrayInt(): Array<Int> {
        require(type in 0..255) { "Type must fit in 1 byte (0–255)" }
        // Must agree with FileSender.MAX_TOTAL_PACKAGES, which refuses a larger send up front.
        require(total in 0..65535) { "Total must fit in 2 bytes (0–65535)" }
        require(spare in 0..65535) { "Spare must fit in 2 bytes (0–65535)" }
        require(spareData in 0..255) { "SpareData must fit in 1 byte (0–255)" }
        require(originalSize in 0..MAX_ORIGINAL_SIZE) { "OriginalSize must fit in 3 bytes (0–$MAX_ORIGINAL_SIZE)" }

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

        // Big-endian like total and spare. Appended after the fixed-width name, matching the
        // JS client, so a receiver that stops reading at the name still parses the rest.
        val originalSizeInts = arrayOf(
            (originalSize shr 16) and 0xFF,
            (originalSize shr 8) and 0xFF,
            originalSize and 0xFF,
        )

        // Return header + ending + name + original size (no dynamic length fields)
        return header + fileEndingInts.toTypedArray() + fileNameInts.toTypedArray() + originalSizeInts
    }

    val fileType: FileUtils.FileType = FileUtils.FileType.fromBitCode(type) ?: FileUtils.FileType.File

    companion object {
        /** The most [originalSize] can carry: it is 3 bytes on the wire. */
        const val MAX_ORIGINAL_SIZE = 0xFFFFFF
    }

}