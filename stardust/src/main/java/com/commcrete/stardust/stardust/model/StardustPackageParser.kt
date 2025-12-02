package com.commcrete.stardust.stardust.model

import android.content.Context
import com.commcrete.stardust.crypto.CryptoUtils
import com.commcrete.stardust.stardust.StardustPackageUtils
import timber.log.Timber
import java.nio.ByteBuffer

class StardustPackageParser : StardustParser() {

    private var byteBuffer : ByteBuffer? = null
    var spareData : ByteArray? = null
    var packageState : PackageState = PackageState.NOT_ENOUGH_DATA
    var mPackage : StardustPackage? = null

    companion object{
        const val syncBytesLength = 4
        const val cryptLengthLength = 1
        const val cryptControlLength = 1
        const val destinationBytesLength = 4
        const val sourceBytesLength = 4
        const val controlBytesLength = 1
        const val opCodeBytesLength = 1
        const val lengthBytesLength = 1
        const val checkXorBytesLength = 1
    }

    fun populateByteBuffer(context: Context, byteArray: ByteArray?) : PackageState {
        try{
            if(byteBuffer == null) {
                byteBuffer = ByteBuffer.allocate(2048)
            }
            byteArray?.let {
                byteBuffer?.limit(byteBuffer?.position()?.plus(100) ?: 30000)
                byteBuffer?.put(it)
            }
            mPackage = getStardustPackageFromBuffer(context)
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

    fun getStardustPackageFromBuffer(context: Context) : StardustPackage?{
        byteBuffer?.let { buffer ->
            val byteArray = readFromByteBuffer(buffer)
//            logByteArray("getStardustPackageFromBuffer", byteArray)
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
            val cryptControlLengthBytes = cutByteArray(byteArray, cryptControlLength, offset)
            offset += cryptControlLength
            val openControl = OpenStardustControlByte().getStardustControlByteFromByte(cryptControlLengthBytes[0].toInt())
            val cryptLengthBytes = cutByteArray(byteArray, cryptLengthLength, offset)
            offset += cryptLengthLength
            val cryptLength = cryptLengthBytes[0].toUByte().toInt()

            if((cryptLength > (byteArray.size - offset))) {
                packageState = PackageState.NOT_ENOUGH_DATA
                return null
            }
            val cryptStart = offset
            var decryptData = byteArrayOf()
            if(openControl.stardustCryptType == OpenStardustControlByte.StardustCryptType.ENCRYPTED) {
                val encryptedBytes = byteArray.copyOfRange(offset, cryptLength+offset)
                decryptData = CryptoUtils.decryptData(context,encryptedBytes)
            } else {
                decryptData = byteArray.copyOfRange(offset, cryptLength+offset)
            }
            try {
                // anything AFTER the crypt payload is considered "spare"
                val endOfCrypt = cryptStart + cryptLength
                spareData = if (endOfCrypt < byteArray.size) {
                    byteArray.copyOfRange(endOfCrypt, byteArray.size)
                } else {
                    byteArrayOf() // or `null` if spareData is ByteArray?
                }
            } catch (e: Exception) {
                e.printStackTrace()
                spareData = byteArrayOf() // keep it sane on failure
            }

            offset = 0
            val destinationBytes = cutByteArray(decryptData, destinationBytesLength, offset)
            offset += destinationBytesLength
            val sourceBytes = cutByteArray(decryptData, sourceBytesLength, offset)
            offset += sourceBytesLength
            val controlBytes = cutByteArray(decryptData, controlBytesLength, offset)
            offset += controlBytesLength
            val opCodeBytes = cutByteArray(decryptData, opCodeBytesLength, offset)
            offset += opCodeBytesLength
            val opcode = getOpCode(opCodeBytes[0].toUByte())
            val controlByte = StardustControlByte().getStardustControlByteFromByte(controlBytes[0].toInt())
            var lengthBytes = cutByteArray(decryptData, lengthBytesLength, offset)
            var length = lengthBytes[0].toUByte().toInt()
            offset += lengthBytesLength
            var dataBytes : ByteArray? = null
            dataBytes = cutByteArray(decryptData, length, offset)
            offset += length
            val checkXorBytes = cutByteArray(decryptData, checkXorBytesLength, offset)

            if(opcode == StardustPackageUtils.StardustOpCode.SEND_MESSAGE && controlByte.stardustPackageType == StardustControlByte.StardustPackageType.DATA){
                val realLength = dataBytes[0].toUByte().toInt()
                dataBytes = dataBytes.copyOfRange(1, realLength+1)
                length = realLength
            }
            if(length > dataBytes.size) {
                packageState = PackageState.NOT_ENOUGH_DATA
                return null
            }
            packageState = if(openControl.stardustCryptType == OpenStardustControlByte.StardustCryptType.DECRYPTED) {
                if(checkXorBytes[0].toInt() == calcChecksum(byteArray, (offset + 6)).toInt()) PackageState.VALID else PackageState.INVALID_DATA
            } else {
                // Get the size of decryptData
                val decryptDataSize = decryptData.size
// Calculate starting position for replacement (total size minus decrypt data size)
                val startPos = byteArray.size - decryptDataSize
// Create new array with original start and replaced end
                var encryptedBytes = byteArray.copyOf()
                var encryptedBytesStart = byteArray.copyOf()
                encryptedBytesStart = encryptedBytes.copyOfRange(0, 6)
                encryptedBytes = encryptedBytesStart + decryptData
                if(checkXorBytes[0].toInt() == calcChecksum(encryptedBytes, (offset + 6)).toInt()) PackageState.VALID else PackageState.INVALID_DATA
            }
            if(packageState != PackageState.VALID) {
                return null
            }
            val StardustPackage = StardustPackage(
                context = context,
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