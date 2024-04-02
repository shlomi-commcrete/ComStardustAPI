package com.commcrete.stardust.stardust.model

import java.nio.ByteBuffer

open class StardustParser {

    fun ByteArray.toHex(): String {
        return joinToString(separator = "") {
            "%02x".format(it)
        }
    }


    fun logByteArray(tagTitle: String, bDataCodec: ByteArray) {
        val stringBuilder = StringBuilder()
        for (element in bDataCodec) {
            stringBuilder.append("${element},")
        }
    }

    fun intArrayToByteArray(intArray: MutableList<Int>): ByteArray {
        val byteArray = ByteArray(intArray.size)
        for (i in intArray.indices) {
            byteArray[i] = intArray[i].toByte()
        }
        return byteArray
    }


    fun byteArrayToInt(byteArray: ByteArray): Int {
        var result = 0
        for (i in byteArray.indices) {
            result = result or (byteArray[i].toInt() shl 8 * i)
        }
        return result
    }

    fun byteArrayToLong(byteArray: ByteArray): Long {
        if(byteArray.isEmpty()){
            return 0
        }
        val buffer = ByteBuffer.wrap(byteArray.copyOf())
        return buffer.long
    }

    fun cutByteArray(byteArray: ByteArray, length: Int, offset: Int): ByteArray {
        try {
            if(byteArray.size < (length + offset)){
                return ByteArray(0)
            }
            val remaining = byteArray.size - offset
            val chunkLength = if (remaining >= length) length else remaining
            return byteArray.copyOfRange(offset, offset + chunkLength)
        }catch (e :Exception){
            e.printStackTrace()
            return ByteArray(0)
        }
    }

    fun byteArrayToFloat(byteArray: ByteArray): Float {
        val buffer = ByteBuffer.wrap(byteArray.copyOf())
        return buffer.float
    }

    fun doubleToFourByteArray(doubleValue: Double): ByteArray {
        val buffer = ByteBuffer.allocate(java.lang.Double.BYTES)
        buffer.putDouble(doubleValue)
        val fullByteArray = buffer.array()
        return fullByteArray.copyOf(4)
    }

    fun floatToByteArray(value: Float): ByteArray {
        val intBits = java.lang.Float.floatToIntBits(value)
        return byteArrayOf(
            (intBits shr 24 and 0xFF).toByte(),
            (intBits shr 16 and 0xFF).toByte(),
            (intBits shr 8 and 0xFF).toByte(),
            (intBits and 0xFF).toByte()
        )
    }
    fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }



    fun byteArrayToUInt32(bytes: ByteArray): UInt {
        if (bytes.size != 4) {
            throw IllegalArgumentException("Byte array must be exactly 4 bytes long")
        }

        return ((bytes[0].toUInt() and 0xFFu) shl 24) or
                ((bytes[1].toUInt() and 0xFFu) shl 16) or
                ((bytes[2].toUInt() and 0xFFu) shl 8) or
                (bytes[3].toUInt() and 0xFFu)
    }
}
fun Int.intToByteArray(): ByteArray {
    return byteArrayOf(
        (this shr 24 and 0xFF).toByte(),
        (this shr 16 and 0xFF).toByte(),
        (this shr 8 and 0xFF).toByte(),
        (this and 0xFF).toByte()
    )
}

fun ByteArray.toHex(): String {
    return joinToString(separator = "") {
        " %02x".format(it)
    }
}

fun combineByteArrays(vararg byteArrays: ByteArray): ByteArray {
    return byteArrays.reduce { acc, byteArray -> acc + byteArray }
}