package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.stardust.StardustPackageUtils
import timber.log.Timber
import java.nio.ByteBuffer

class StardustPackageParser : StardustParser() {

    private var byteBuffer : ByteBuffer? = null
    private var spareData : ByteBuffer? = null
    var packageState : PackageState = PackageState.NOT_ENOUGH_DATA

    companion object{
        const val syncBytesLength = 4
        const val destinationBytesLength = 4
        const val sourceBytesLength = 4
        const val controlBytesLength = 1
        const val opCodeBytesLength = 1
        const val lengthBytesLength = 1
        const val checkXorBytesLength = 1
    }

    fun populateByteBuffer(byteArray: ByteArray?) : PackageState {
        try{
            if(byteBuffer == null) {
                byteBuffer = ByteBuffer.allocate(2048)
            }
            byteArray?.let {
                byteBuffer?.limit(byteBuffer?.position()?.plus(100) ?: 30000)
                byteBuffer?.put(it)
            }
            getStardustPackageFromBuffer()
        }catch (e : Exception){
            e.printStackTrace()
        }
        return packageState
    }

    private fun getByteBuffer(): ByteBuffer? {
//        byteBuffer?.flip()
        return byteBuffer?.asReadOnlyBuffer()
    }

    private fun readFromByteBuffer(byteBuffer: ByteBuffer): ByteArray {
        val byteArray = ByteArray(byteBuffer.position())
        byteBuffer.flip()
        byteBuffer.get(byteArray)
        return byteArray
    }

    fun getStardustPackageFromBuffer() : StardustPackage?{
        byteBuffer?.let { buffer ->
            val byteArray = readFromByteBuffer(buffer)
            logByteArray("getStardustPackageFromBuffer", byteArray)
            var offset = 0
            val syncBytes = cutByteArray(byteArray, syncBytesLength, offset)
            offset += syncBytesLength
            if(byteArray.size < 4 ){
                packageState = PackageState.NOT_ENOUGH_DATA
                return null
            }
            if(!syncBytes.contentEquals(intArrayToByteArray(StardustPackageUtils.SYNC_BYTES.toMutableList()))) {
                packageState = PackageState.INVALID_DATA
                return null
            }
            val destinationBytes = cutByteArray(byteArray, destinationBytesLength, offset)
            offset += destinationBytesLength
            val sourceBytes = cutByteArray(byteArray, sourceBytesLength, offset)
            offset += sourceBytesLength
            val controlBytes = cutByteArray(byteArray, controlBytesLength, offset)
            offset += controlBytesLength
            val opCodeBytes = cutByteArray(byteArray, opCodeBytesLength, offset)
            offset += opCodeBytesLength
            val opcode = getOpCode(opCodeBytes[0].toUByte())
            val controlByte = StardustControlByte().getStardustControlByteFromByte(controlBytes[0].toInt())
            var lengthBytes = cutByteArray(byteArray, lengthBytesLength, offset)
            var length = lengthBytes[0].toUByte().toInt()
            offset += lengthBytesLength
            var dataBytes : ByteArray? = null
            dataBytes = cutByteArray(byteArray, length, offset)
            offset += length
            val checkXorBytes = cutByteArray(byteArray, checkXorBytesLength, offset)

            if(opcode == StardustPackageUtils.StardustOpCode.SEND_MESSAGE && controlByte.stardustPackageType == StardustControlByte.StardustPackageType.DATA){
                val realLength = dataBytes[0].toUByte().toInt()
                dataBytes = dataBytes.copyOfRange(1, realLength+1)
                length = realLength
            }
            if(length > dataBytes.size) {
                packageState = PackageState.NOT_ENOUGH_DATA
                return null
            }
            packageState = if(checkXorBytes[0].toInt() == calcChecksum(byteArray, offset).toInt()) PackageState.VALID else PackageState.INVALID_DATA
            if(packageState != PackageState.VALID) {
                return null
            }
            val StardustPackage = StardustPackage(
                syncBytes = StardustPackageUtils.byteArrayToIntArray(syncBytes),
                destinationBytes = StardustPackageUtils.byteArrayToIntArray(destinationBytes),
                sourceBytes = StardustPackageUtils.byteArrayToIntArray(sourceBytes),
                stardustControlByte = controlByte,
                stardustOpCode = opcode ,
                length = length,
                data = dataBytes.let { StardustPackageUtils.byteArrayToIntArray(it) },
                checkXor = if(checkXorBytes.isEmpty()) -2000 else checkXorBytes[0].toInt()

            )
            return StardustPackage
        }
        return null
    }

    private fun calcChecksum(data: ByteArray, size: Int): Byte {
        var result: Byte = 0
        for (i in 0 until size) {
            result = (result.toInt() xor data[i].toInt()).toByte()
        }
        return result
    }

    enum class PackageState {
        VALID,
        NOT_ENOUGH_DATA,
        INVALID_DATA
    }

    private fun getOpCode(opCodeByte: UByte): StardustPackageUtils.StardustOpCode {
        for (value in StardustPackageUtils.StardustOpCode.values()){
            if(value.codeID == opCodeByte.toInt()) {
                return value
            }
        }
        return StardustPackageUtils.StardustOpCode.GET_ADDRESSES
    }


}