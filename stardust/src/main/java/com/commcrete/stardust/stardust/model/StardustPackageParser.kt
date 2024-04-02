package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.stardust.StardustPackageUtils
import java.nio.ByteBuffer

class StardustPackageParser : StardustParser() {

    private var byteBuffer : ByteBuffer? = null
    var isFinished = false
    var isDefect = false

    companion object{
        const val syncBytesLength = 4
        const val destinationBytesLength = 4
        const val sourceBytesLength = 4
        const val controlBytesLength = 1
        const val opCodeBytesLength = 1
        const val lengthBytesLength = 1
        const val checkXorBytesLength = 1
    }

    fun populateByteBuffer(byteArray: ByteArray?) : Boolean {
        try{
            if(byteBuffer == null) {
                byteBuffer = ByteBuffer.allocate(2048)
            }
            byteArray?.let {
                byteBuffer?.limit(byteBuffer?.position()?.plus(100) ?: 30000)
                byteBuffer?.put(it)
            }
            val StardustPackage =  getStardustPackageFromBuffer()
            StardustPackage?.let {
                if((StardustPackage.getDataAsString() !=null
                            && StardustPackage.length <= StardustPackage.getDataSizeLength())){
                    return true
                }
            }
        }catch (e : Exception){
            e.printStackTrace()
        }
        return false
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
            if(offset < 4 || !syncBytes.contentEquals(intArrayToByteArray(StardustPackageUtils.SYNC_BYTES.toMutableList()))){
                isDefect = true
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
            var length = lengthBytes[0].toInt()
            offset += lengthBytesLength
            var dataBytes : ByteArray? = null
            dataBytes = cutByteArray(byteArray, length, offset)
            offset += length
            val checkXorBytes = cutByteArray(byteArray, checkXorBytesLength, offset)
            if(opcode == StardustPackageUtils.StardustOpCode.SEND_MESSAGE && controlByte.stardustPackageType == StardustControlByte.StardustPackageType.DATA){
                dataBytes = dataBytes.copyOfRange(1, dataBytes.size)
                length -= 1
            }
            val StardustPackage = StardustPackage(
                syncBytes = StardustPackageUtils.byteArrayToIntArray(syncBytes),
                destinationBytes = StardustPackageUtils.byteArrayToIntArray(destinationBytes),
                sourceBytes = StardustPackageUtils.byteArrayToIntArray(sourceBytes),
                stardustControlByte = controlByte,
                stardustOpCode = opcode ,
                length = length,
                data = dataBytes.let { StardustPackageUtils.byteArrayToIntArray(it) },
                checkXor = if(checkXorBytes.isEmpty()) 0 else checkXorBytes[0].toInt()

            )
            return StardustPackage
        }
        return null
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