package com.commcrete.stardust.util.audio

import com.commcrete.stardust.stardust.model.toHex
import com.ustadmobile.codec2.Codec2Decoder
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class PTTCodecDecoderSession : PttReceiveSession() {

    // ── Constants ─────────────────────────────────────────────────────────────────────────────────
    val embpyByte: ByteArray = byteArrayOf(0, 0, 0, 0)
    val sampleRate = 8000
    val bufferSizeMulti = 1.0
    val speedFactor = 1.0f

    // ── Legacy Codec2 receive session state ───────────────────────────────────────────────────────
    val byteArrayOutputStream = ByteArrayOutputStream()

    var mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)


    // ── File I/O ──────────────────────────────────────────────────────────────────────────────────
    fun writePTTReceivedData(pttAudio: ByteArray, file: File) {
        val outputStream: OutputStream = FileOutputStream(file, true)
        val bufferedOutputStream = BufferedOutputStream(outputStream)
        val dataOutputStream = DataOutputStream(bufferedOutputStream)
        dataOutputStream.write(pttAudio)
        dataOutputStream.close()
    }



    // ── Codec2 decode ─────────────────────────────────────────────────────────────────────────────
    fun handleBittelAudioMessage(byteArray: ByteArray): ByteArray {
        return try {
            val byteBuffer = mCodec2Decoder.readFrame(byteArray)
            val bDataCodec = byteBuffer.array()
            val data = arrayListOf<Byte>()
            for (byte in bDataCodec) data.add(byte)
            data.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            mCodec2Decoder.destroy()
            mCodec2Decoder.rawAudioOutBytesBuffer.clear()
            mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
            byteArrayOf()
        }
    }


    fun splitByteArray(input: ByteArray, chunkSize: Int): List<ByteArray> {
        Timber.tag("receiveBeforeSplit").d("input : ${input.toHex()}")
        val splits = mutableListOf<ByteArray>()
        var startIndex = 0
        while (startIndex < input.size) {
            val endIndex = minOf(startIndex + chunkSize, input.size)
            splits.add(input.copyOfRange(startIndex, endIndex))
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
            Timber.tag("concatenateByteArraysWithIgnoring").d("byteArray origin : ${combined.toHex()}")
            val byteArray1 = ByteArray(4)
            val byteArray2 = ByteArray(4)
            for (i in 0 until 4) byteArray1[i] = combined[i]
            byteArray1[3] = (byteArray1[3].toInt() and 0xF0).toByte()
            byteArray2[0] = ((combined[3].toUByte().toInt() shl 4) or (combined[4].toUByte().toInt() shr 4)).toByte()
            byteArray2[1] = ((combined[4].toUByte().toInt() shl 4) or (combined[5].toUByte().toInt() shr 4)).toByte()
            byteArray2[2] = ((combined[5].toUByte().toInt() shl 4) or (combined[6].toUByte().toInt() shr 4)).toByte()
            byteArray2[3] = (combined[6].toUByte().toInt() shl 4).toByte()
            listOf(byteArray1, byteArray2)
        } catch (e: Exception) {
            Timber.tag("splitByteArray2").e(e, "Error while splitting byte array")
            listOf(ByteArray(4), ByteArray(4))
        }
    }

    fun combine(byteArrayList: List<ByteArray>): ByteArray {
        var combinedSize = 0
        for (array in byteArrayList) combinedSize += array.size
        val result = ByteArray(combinedSize)
        var position = 0
        for (array in byteArrayList) {
            System.arraycopy(array, 0, result, position, array.size)
            position += array.size
        }
        return result
    }

}
