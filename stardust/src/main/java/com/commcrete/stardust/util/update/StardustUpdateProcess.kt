package com.commcrete.stardust.util.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.Data
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.stardust.model.StardustUpdateData
import com.commcrete.stardust.stardust.model.StardustUpdateDataParser
import com.example.mylibrary.R
import java.nio.ByteBuffer
import java.util.zip.CRC32

object StardustUpdateProcess {

    internal val clientConnection : ClientConnection = DataManager.getClientConnection(DataManager.context)

    var isProcessRunning = false

    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        isProcessRunning = false
    }
    private val handler : Handler = Handler(Looper.getMainLooper())
    private val file = FileUtils.readRawResourceAsByteArray(DataManager.context, R.raw.bittel_firmware)
    private var bittelUpdateData : StardustUpdateData? = null


    private fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 400000)
    }

    fun startUpdateProcess (context: Context) {
        isProcessRunning = true
        bittelUpdateData = StardustUpdateDataParser().parseUpdateData(file)
        getCurrentBittelBootAddress(context)
    }

    fun sendInitUpdateProcess(bottAddress: Array<Int>?, context: Context) {
        bottAddress?.let {bootAddress ->
            bittelUpdateData?.let { bittelUpdateData ->
                bittelUpdateData.bootAddress = bootAddress
            }
            SharedPreferencesUtil.getAppUser(context)?.let {
                val src = it.appId
                val dst = it.bittelId
                if(src != null && dst != null) {
                    val versionPackage = StardustPackageUtils.getStardustPackage(
                        source = src, destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_BITTEL_VERSION,
                        data = getBittelUpdateFilePackage()
                    )
                    clientConnection.addMessageToQueue(versionPackage)
                }
            }
        }
        resetTimer()
    }

    private fun getCurrentBittelBootAddress (context: Context) {
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val versionPackage = StardustPackageUtils.getStardustPackage(
                    source = src, destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.GET_BITTEL_BOOT_ADDRESS
                )
                clientConnection.addMessageToQueue(versionPackage)
            }
        }
        resetTimer()
    }

    private fun getBittelUpdateDataFile () : Array<Int>? {
        bittelUpdateData?.let {
            val address = it.getCurrentPackageNum().intToByteArray().reversedArray() //todo four bytes of address chunk num *    96
            val isLast = it.isLastPackageToBurn() //todo one byte of isLast
            val data = it.getCurrentPackageToBurn().copyOf().reversedArray() //todo 96 bytes of data (pad for 96 if is las and don't have enough)
            val CRC = it.getCurrentPackageToBurn().computeCRC32AsByteArray().reversedArray() //todo four bytes CRC
            it.updatePackageNum()
            CRC32Calculator.calculateSwCRCByByte(0xFFFFFFFF,data.copyOf(), data.size.toLong(), true)
            return StardustPackageUtils.byteArrayToIntArray(address + isLast +data + CRC)
        }
        return null
    }

    private fun getBittelUpdateFilePackage () : Array<Int>? {
        bittelUpdateData?.let {
            val size = it.totalSize.intToByteArray().reversedArray()
            val CRC = it.getCRC()
            return StardustPackageUtils.byteArrayToIntArray(size + CRC)
        }
        return null
    }

    fun startSendingUpdateData(context: Context) {
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val versionPackage = StardustPackageUtils.getStardustPackage(
                    source = src, destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.BURN_BITTEL_VERSION,
                    data = getBittelUpdateDataFile()
                )
                clientConnection.addMessageToQueue(versionPackage)
            }
        }
        resetTimer()
    }


    fun cancelProcess () {
        isProcessRunning = false
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
    }
}

fun ByteArray.computeCRC32AsByteArray(): ByteArray {
    val byteArray = this.copyOf().reversedArray()
    //1 : 64, b6, 3f , cd HEX
    val crc = CRC32()
    crc.update(byteArray)

    val crcValue = crc.value
    val buffer = ByteBuffer.allocate(8) // Long is 8 bytes
    buffer.putLong(crcValue)

    return buffer.array().copyOfRange(4, 8)
}