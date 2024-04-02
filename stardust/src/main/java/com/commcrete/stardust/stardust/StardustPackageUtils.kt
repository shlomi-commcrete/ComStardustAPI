package com.commcrete.stardust.stardust

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustPackageParser
import com.commcrete.stardust.util.DataManager

object StardustPackageUtils {

    val SYNC_BYTES = arrayOf(0x37, 0x65, 0x21, 0x84)
    const val Ack = 0x01
    const val Nack = 0x02
    private val packagesList : MutableList<StardustPackageParser> = mutableListOf()
    val packageLiveData : MutableLiveData<StardustPackage?> = MutableLiveData()
    private val handler : Handler = Handler(Looper.getMainLooper())
    private var lastByteArray : ByteArray? = null
    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        packageLiveData.value = null
        packagesList.clear()
    }
    private val handlerByteArray : Handler = Handler(Looper.getMainLooper())
    private val runnableByteArray : Runnable = kotlinx.coroutines.Runnable {
        lastByteArray = null
    }
    private var bittelPackageHandler : StardustPackageHandler? = null

    init {
        try {
            bittelPackageHandler = DataManager.getStardustPackageHandler(DataManager.context)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }
    enum class StardustOpCode (val codeID : Int, val stardustControlByte: StardustControlByte) {
        //Requests
        UPDATE_TX_FREQUENCY (0x01,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_RX_FREQUENCY (0x02,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_LO_POWER_TX (0x03,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_LO_POWER_RX (0x04,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_HR_POWER_TX (0x05,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_LR_POWER_TX (0x06,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_LO_FREQUENCY (0x07,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        READ_STATUS (0x0C,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        SAVE_CONFIGURATION (0x0D,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        RESET_BITTEL (0x0E,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        PING (0x0F,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_VERSION (0x10,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_BITTEL_VERSION (0x11,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        BURN_BITTEL_VERSION (0x12,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_BITTEL_BOOT_ADDRESS (0x13,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        SEND_MESSAGE (0x15,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
         SEND_SOS (0x15,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        SEND_PTT (0x15,
            StardustControlByte(
                StardustControlByte.StardustPackageType.SPEECH, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        REQUEST_ADDRESS (0x16,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        REQUEST_LOCATION (0x17,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_ADDRESS (0x18,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_BITTEL_TYPE (0x19,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_UART_PORT (0x1A,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_POLYGON_FREQ (0x1D,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_POLYGON (0x1E,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_SERVER_BITTEL_CONNECT (0x20,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_BITTEL_LOGS (0x23,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),

        //Responses
        READ_CONFIGURATION_RESPONSE (0x8C,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        SAVE_CONFIG_RESPONSE (0x8D,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        PING_RESPONSE (0x8F,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        RECEIVE_VERSION (0x90,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_BITTEL_VERSION_RESPONSE (0x91,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_BITTEL_VERSION_PACKAGE_RESPONSE (0x92,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_BITTEL_BOOT_ADDRESS_RESPONSE (0x93,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        SEND_DATA_RESPONSE (0x95,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_ADDRESSES (0x96,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        RECEIVE_LOCATION (0x97,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_ADDRESS_RESPONSE (0x98,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        UPDATE_POLYGON_FREQ_RESPONSE (0x9D,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_POLYGON_RESPONSE (0x9E,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
        GET_BITTEL_LOGS_RESPONSE (0xA3,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),

        //Interrupts
        UPDATE_POLYGON_INTERRUPT (0xFA,
            StardustControlByte(
                StardustControlByte.StardustPackageType.DATA, StardustControlByte.StardustDeliveryType.HR,
                StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK, StardustControlByte.StardustPartType.LAST,
                StardustControlByte.StardustServer.NOT_SERVER,
                StardustControlByte.StardustMessageType.REGULAR
            )
        ),
    }

    enum class BittelAddressUpdate (val id : Int) {
        BITTEL (0),
        SMARTPHONE(1),
        EMERGENCY(2)
    }


        fun getStardustPackage (source : String, destenation : String, stardustOpCode: StardustOpCode, data : Array<Int>? = null) : StardustPackage {
        val bittelPackage = StardustPackage(
            syncBytes = SYNC_BYTES, stardustOpCode = stardustOpCode, stardustControlByte = stardustOpCode.stardustControlByte,
        length = if(data.isNullOrEmpty()) 0 else data.size,
            destinationBytes = hexStringToByteArray(destenation), sourceBytes = hexStringToByteArray(
                source
            )
        )
        bittelPackage.data = data
        bittelPackage.checkXor = getCheckXor(bittelPackage.getStardustPackageToCheckXor())
        return bittelPackage
    }

    fun changeSource(bittelPackage: StardustPackage, source: String){
        bittelPackage.sourceBytes = hexStringToByteArray(source)
    }

    fun changeDestination(bittelPackage: StardustPackage, destination : String ){
        bittelPackage.destinationBytes = hexStringToByteArray(destination)
    }

    fun getCheckXor (dataList : MutableList<Int>): Int {
        // Check Xor
        var xorByte = 0
        for (byte in dataList){
            xorByte = xorByte xor byte
        }
        return xorByte
    }

    private fun longToLittleEndianByteArray(value: Long): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) {
            result[i] = (value shr (8 * i)).toByte()
        }
        return result
    }

    private fun getDataFromLong(value : Long) : Array<Int> {
        val byteArray = longToLittleEndianByteArray(value)
        val data = byteArrayToIntArray(byteArray)
        return data
    }

    fun charArrayToIntArray(charArray: CharArray): Array<Int> {
        // Convert each Char element to its corresponding Int representation
        return charArray.map { it.code }.toTypedArray()
    }

    fun byteArrayToIntArray(byteArray: ByteArray): Array<Int> {
        val intArray = IntArray(byteArray.size)
        for (i in byteArray.indices) {
            intArray[i] = byteArray[i].toInt()
        }
        return intArray.toTypedArray()
    }



    fun shortArrayToIntArray(shortArray: ShortArray): Array<Int> {
        val intArray = IntArray(shortArray.size)
        for (i in shortArray.indices) {
            intArray[i] = shortArray[i].toInt()
        }
        return intArray.toTypedArray()
    }

    private fun getSource() : Array<Int> {
        return arrayOf(0x4A, 0x3B, 0x2C, 0x1D, 0xFE)
    }

    private fun getDestination() : Array<Int> {
        return arrayOf(0x34, 0x12, 0xEF, 0xCD, 0xAB)
    }

    fun longToByteArray(longValue: Long): Array<Int> {
        val byteArray = ByteArray(5)

        for (i in 0 until 5) {
            byteArray[i] = (longValue ushr (8 * (4 - i))).toByte()
        }

        return byteArray.map { it.toInt() }.toTypedArray()
    }

    fun hexStringToByteArray(hexString: String): Array<Int> {
        var hex = hexString
        while (hex.length != 8){
            hex = "0$hex"
        }

        val byteArray = ByteArray(4)
        for (i in 3 downTo 0) {
            val startIndex = i * 2
            val endIndex = startIndex + 2
            val byteValue = hex.substring(startIndex, endIndex).toInt(16)
            byteArray[i] = byteValue.toByte()
        }

        return byteArray.map { it.toInt() }.toTypedArray().reversedArray()
    }
    fun intToHexArray(input: Int): IntArray {
        // Calculate the number of nibbles (half-bytes) needed
        val nibblesCount = (Integer.SIZE + 3) / 4

        // Initialize the array to store the nibbles
        val hexArray = IntArray(nibblesCount)

        // Convert the integer to hexadecimal and store each nibble in the array
        for (i in 0 until nibblesCount) {
            val nibbleValue = (input ushr (4 * i)) and 0xF
            hexArray[nibblesCount - 1 - i] = nibbleValue
        }

        return hexArray
    }

    fun handlePackageReceived (byteArray: ByteArray) {
        if(lastByteArray == null || lastByteArray?.contentEquals(byteArray) == false){
            lastByteArray = byteArray
            lastByteArray?.let { logByteArray("handlePackageReceivedlastByteArray", it) }
            logByteArray("handlePackageReceivedbyteArray", byteArray)
            try {
                if(packagesList.isNotEmpty() && packagesList[packagesList.lastIndex] == null ){
                    packagesList.removeAt(packagesList.lastIndex)
                }
            }catch (e : Exception) {
                e.printStackTrace()
            }
            if(packagesList.isEmpty() || packagesList[packagesList.lastIndex].isFinished){
                packagesList.add(StardustPackageParser())
            }
            val isFinished = packagesList[packagesList.lastIndex].populateByteBuffer(byteArray)
            val mPackage =  packagesList[packagesList.lastIndex]
            if(isFinished){
                val bittelPackage = packagesList[packagesList.lastIndex].getStardustPackageFromBuffer()
                bittelPackage?.let {
                    bittelPackageHandler?.handleStardustPackage(it)
                    packagesList.remove(mPackage)
                }
            } else if(mPackage.isDefect){
                packagesList.remove(mPackage)
            }
        }
        resetTimer()
        resetTimerByteArray()
    }

    private fun logByteArray(tagTitle: String, bDataCodec: ByteArray) {
        val stringBuilder = StringBuilder()
        for (element in bDataCodec) {
            stringBuilder.append("${element},")
        }
    }

    private fun resetTimer(){
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 3000)
    }

    private fun resetTimerByteArray(){
        handlerByteArray.removeCallbacks(runnableByteArray)
        handlerByteArray.removeCallbacksAndMessages(null)
        handlerByteArray.postDelayed(runnableByteArray, 2000)
    }
}

fun String.hexStringToByteArray(): ByteArray {
//        if (hexString.length != 10 || !hexString.matches("[0-9A-Fa-f]+".toRegex())) {
//            return null // Invalid input, return null or handle the error accordingly
//        }
    var hex = this
    while (hex.length != 8){
        hex = "0$hex"
    }

    val byteArray = ByteArray(4)
    for (i in 3 downTo 0) {
        val startIndex = i * 2
        val endIndex = startIndex + 2
        val byteValue = hex.substring(startIndex, endIndex).toInt(16)
        byteArray[i] = byteValue.toByte()
    }

    return byteArray
}