package com.commcrete.stardust.audio

import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import com.ustadmobile.codec2.Codec2Decoder
import timber.log.Timber

class Codec2Decoder {

    var mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)

    fun decode (stardustPackage: StardustPackage) : ByteArray {
        var bytesListToReturn : ByteArray = byteArrayOf()
        stardustPackage.data?.let { dataArray ->
            val byteArray = intArrayToByteArray(dataArray.toMutableList())
            var bytes = splitByteArray(byteArray, 7)
            var bytesListToPlay : MutableList<ByteArray> = mutableListOf()
            for(mByte in bytes) {
                var decodedBytes = handleBittelAudioMessage(mByte)
                if(!mByte.contentEquals(PlayerUtils.embpyByte)){
                    bytesListToPlay.add(decodedBytes)
                }
                bytesListToReturn = combine(bytesListToPlay)
            }
        }
        return bytesListToReturn
    }

    private fun intArrayToByteArray(intArray: MutableList<Int>): ByteArray {
        val byteArray = ByteArray(intArray.size)
        for (i in intArray.indices) {
            byteArray[i] = intArray[i].toByte()
        }
        return byteArray
    }

    private fun splitByteArray(input: ByteArray, chunkSize: Int): List<ByteArray> {
        Timber.tag("receiveBeforeSplit").d("input : ${input.toHex()}")
        val splits = mutableListOf<ByteArray>()
        var startIndex = 0

        while (startIndex < input.size) {
            // Calculate endIndex for the current chunk
            val endIndex = minOf(startIndex + chunkSize, input.size)
            // Copy a portion of the array into a new array
            val chunk = input.copyOfRange(startIndex, endIndex)
            // Add the chunk to the result list
            splits.add(chunk)
            // Move the start index forward by chunkSize
            startIndex += chunkSize
        }

        val result = mutableListOf<ByteArray>()
        for (split in splits) {
            Timber.tag("receiveAfterSplit").d("split : ${split.toHex()}")
            result.addAll(splitByteArray2(split))
        }
        return result
    }

    private fun splitByteArray2(combined: ByteArray): List<ByteArray> {
        return try {
            Timber.tag("concatenateByteArraysWithIgnoring")
                .d("byteArray origin : ${combined.toHex()}")

            val byteArray1 = ByteArray(4)
            val byteArray2 = ByteArray(4)

            // Extract byteArray1 from the first 4 bytes of the combined array
            for (i in 0 until 4) {
                byteArray1[i] = combined[i]
            }
            byteArray1[3] = (byteArray1[3].toInt() and 0xF0).toByte()

            // Reverse the manipulation to retrieve byteArray2
            byteArray2[0] = ((combined[3].toUByte().toInt() shl 4) or (combined[4].toUByte().toInt() shr 4)).toByte()
            byteArray2[1] = ((combined[4].toUByte().toInt() shl 4) or (combined[5].toUByte().toInt() shr 4)).toByte()
            byteArray2[2] = ((combined[5].toUByte().toInt() shl 4) or (combined[6].toUByte().toInt() shr 4)).toByte()
            byteArray2[3] = (combined[6].toUByte().toInt() shl 4).toByte()

            Timber.tag("receiveAfterSplit").d("byteArray 1 : ${byteArray1.toHex()}")
            Timber.tag("receiveAfterSplit").d("byteArray 2 : ${byteArray2.toHex()}")

            listOf(byteArray1, byteArray2)

        } catch (e: Exception) {
            Timber.tag("splitByteArray2").e(e, "Error while splitting byte array")
            // Return safe default
            listOf(ByteArray(4), ByteArray(4))
        }
    }

    private fun handleBittelAudioMessage(byteArray: ByteArray) : ByteArray{
        try {
            val byteBuffer = mCodec2Decoder.readFrame(byteArray)
            val bDataCodec = byteBuffer.array()
            val data = arrayListOf<Byte>()
            for (byte in bDataCodec) data.add(byte)
            return data.toByteArray()
        }catch (e : Exception) {
            e.printStackTrace()
            clearDecoder()
            return byteArrayOf()
        }
    }

    private fun clearDecoder() {
        mCodec2Decoder.destroy()
        mCodec2Decoder.rawAudioOutBytesBuffer.clear()
        mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
    }

    private fun combine(byteArrayList: List<ByteArray>): ByteArray {
        var combinedSize = 0
        for (array in byteArrayList) {
            combinedSize += array.size
        }

        val result = ByteArray(combinedSize)
        var position = 0
        for (array in byteArrayList) {
            System.arraycopy(array, 0, result, position, array.size)
            position += array.size
        }

        return result
    }
}