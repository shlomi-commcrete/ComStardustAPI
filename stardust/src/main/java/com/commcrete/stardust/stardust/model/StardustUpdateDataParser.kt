package com.commcrete.stardust.stardust.model

class StardustUpdateDataParser : StardustParser() {

    companion object{
        const val crc1BytesLength = 4
        const val crc2BytesLength = 4
        const val fileSize1BytesLength = 4
        const val fileSize2BytesLength = 4
        const val fileSizePadded1BytesLength = 4
        const val fileSizePadded2BytesLength = 4
        const val packageSizeBytesLength = 1
        const val dataStartOffset = 96
    }

    fun parseUpdateData(byteArray: ByteArray) : StardustUpdateData? {
        try {
            var offset = 0
            val crc1Bytes = cutByteArray(byteArray,
                crc1BytesLength, offset)
            offset += crc1BytesLength
            val crc2Bytes = cutByteArray(byteArray,
                crc2BytesLength, offset)
            offset += crc2BytesLength
            val fileSize1Bytes = cutByteArray(byteArray,
                fileSize1BytesLength, offset)
            offset += fileSize1BytesLength
            val fileSize2Bytes = cutByteArray(byteArray,
                fileSize2BytesLength, offset)
            offset += fileSize2BytesLength
            val fileSizePadded1Bytes = cutByteArray(byteArray,
                fileSizePadded1BytesLength, offset)
            offset += fileSizePadded1BytesLength
            val fileSizePadded2Bytes = cutByteArray(byteArray,
                fileSizePadded2BytesLength, offset)
            offset += fileSizePadded2BytesLength
            val packageSizeBytes = cutByteArray(byteArray,
                packageSizeBytesLength, offset)
            offset += packageSizeBytesLength
            val dataBytesCombined = byteArray.copyOfRange(dataStartOffset, byteArray.size)
            val dataSize = dataBytesCombined.size /2
            if((dataSize % dataStartOffset) == 0) {
                val data1Bytes = dataBytesCombined.copyOfRange(0, dataSize)
                val data2Bytes = dataBytesCombined.copyOfRange(dataSize, dataBytesCombined.size)
                val StardustUpdateData = StardustUpdateData (
                    CRC1 = crc1Bytes,
                    CRC2 = crc2Bytes,
                    fileSize1 = fileSize1Bytes,
                    fileSize2 = fileSize2Bytes,
                    fileSizePadded1 = fileSizePadded1Bytes,
                    fileSizePadded2 = fileSizePadded2Bytes,
                    packageSize = packageSizeBytes[0].toInt(),
//                    totalSize = dataBytesCombined.size,
                    totalSize = dataSize,
                    data1 = data1Bytes,
                    data2 = data2Bytes,
                )
                return StardustUpdateData
            }
            return null
        }catch (e : Exception) {
            e.printStackTrace()
        }
        return null
    }
}