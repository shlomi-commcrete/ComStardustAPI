package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.crypto.CryptoUtils
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.Ack
import com.commcrete.stardust.stardust.StardustPackageUtils.byteArrayToIntArray
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.GroupsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

data class StardustPackage(
    val syncBytes: Array<Int>,
    var destinationBytes: Array<Int>,
    var sourceBytes: Array<Int>,
    var stardustControlByte: StardustControlByte,
    var stardustOpCode: StardustPackageUtils.StardustOpCode,
    val length: Int,
    var data: Array<Int>? = null,
    var checkXor: Int? = 0,
    var pullTimer : Int = 0,
    var lengthForCrypt : Int = 0,
    var openControlByte: OpenStardustControlByte = OpenStardustControlByte(),
    var cryptData : Array<Int> = arrayOf()
) : StardustParser(){

    var retryCounter = 0

    var isDemandAck : Boolean? = false
    var messageNumber : Int = 1
    var idNumber: Long? = null

    val senderId: String

    val groupId: String?

    val chatId: String

    init {
        val ids = GroupsUtils.resolveGroupAndContactSync(
            sourceId = getSourceAsString(),
            destinationId = getDestAsString()
        )
        senderId = ids.senderId
        groupId = ids.groupId
        chatId = runBlocking(Dispatchers.IO) {
            DataManager.getAppRepo().getChatIdForReceivedPackage(senderId, groupId)
        }
    }


    companion object{
        const val MAX_RETRY_COUNTER = 0
        const val DELAY_TS = 15L
        private const val DEFAULT_PADDED_DATA_SIZE = 4

        fun isDifferentLengthCheck(
            StardustControlByte: StardustControlByte,
            StardustOpCode: StardustPackageUtils.StardustOpCode
        ) : Boolean{
            return StardustOpCode == StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION
        }
    }

    private fun encryptData() {
        val byteArray = getDataForEncryption()
        val encrypted = CryptoUtils.encryptData(byteArray)
        lengthForCrypt = byteArray.size
        cryptData = byteArrayToIntArray(encrypted)
    }

    private fun getDataForEncryption() : ByteArray{
        val packageToCheck = mutableListOf<Int>()
        for (data in destinationBytes) {
            appendToIntArray(data, packageToCheck)
        }
        for (data in sourceBytes) {
            appendToIntArray(data, packageToCheck)
        }
        appendToIntArray(stardustControlByte.getControlByteValue(), packageToCheck)
        appendToIntArray(stardustOpCode.codeID, packageToCheck)
        appendToIntArray(maxOf(length, 4), packageToCheck)
        getPaddedData(data).let { dataList ->
            for (data in dataList) {
                appendToIntArray(data, packageToCheck)
            }
        }
        checkXor?.let { checkXor ->
            appendToIntArray(checkXor, packageToCheck)
        }
        return intArrayToByteArray(packageToCheck)
    }

    fun getStardustPackageToCheckXor() : MutableList<Int> {
        val packageToCheck = mutableListOf<Int>()
        val packageToEncrypt = mutableListOf<Int>()
        for (data in destinationBytes) {
            appendToIntArray(data, packageToEncrypt)
        }
        for (data in sourceBytes) {
            appendToIntArray(data, packageToEncrypt)
        }
        appendToIntArray(stardustControlByte.getControlByteValue(), packageToEncrypt)
        appendToIntArray(stardustOpCode.codeID, packageToEncrypt)
        appendToIntArray(maxOf(length, 4), packageToEncrypt)
        getPaddedData(data).let { dataList ->
            for (data in dataList) {
                appendToIntArray(data, packageToEncrypt)
            }
        }

        for (data in syncBytes) {
            appendToIntArray(data, packageToCheck)
        }
        lengthForCrypt = packageToEncrypt.size + 1 //+1 for checkXor
        appendToIntArray(openControlByte.getControlByteValue(), packageToCheck)
        appendToIntArray(lengthForCrypt, packageToCheck)

        val packageToReturn = mutableListOf<Int>().apply {
            addAll(packageToCheck)
            addAll(packageToEncrypt)
        }
        return packageToReturn
    }

    fun getStardustPackageToSend(): ByteArray {
        val packageToSend = mutableListOf<Int>()
        for (data in syncBytes) {
            appendToIntArray(data, packageToSend)
        }
        appendToIntArray(openControlByte.getControlByteValue(), packageToSend)
        appendToIntArray(lengthForCrypt, packageToSend)
        if(openControlByte.stardustCryptType == OpenStardustControlByte.StardustCryptType.ENCRYPTED) {
            encryptData()
            for (data in cryptData) {
                appendToIntArray(data, packageToSend)
            }
        } else {
            val getDataForEnc = getDataForEncryption()
            for (data in getDataForEnc) {
                appendToIntArray(data.toInt(), packageToSend)
            }
        }

        return intArrayToByteArray(packageToSend)
    }

    fun getStardustPackageToSendList() : List<ByteArray>{
        val packageToSend = mutableListOf<Int>()
        for (data in syncBytes) {
            appendToIntArray(data, packageToSend)
        }
        for (data in destinationBytes) {
            appendToIntArray(data, packageToSend)
        }
        for (data in sourceBytes) {
            appendToIntArray(data, packageToSend)
        }
        appendToIntArray(stardustControlByte.getControlByteValue(), packageToSend)
        appendToIntArray(stardustOpCode.codeID, packageToSend)
        appendToIntArray(length, packageToSend)
        getPaddedData(data).let { dataList ->
            for (data in dataList) {
                appendToIntArray(data, packageToSend)
            }
        }
        checkXor?.let { checkXor ->
            appendToIntArray(checkXor, packageToSend)
        }
        val listIterator = packageToSend.toList().chunked(20)
        var byteArrayList : MutableList<ByteArray> = mutableListOf()
        for(mPackage in listIterator){
            byteArrayList.add(intArrayToByteArray(mPackage.toMutableList()))
        }
        return byteArrayList
    }

    private fun appendToIntArray (data : Int, intArray: MutableList<Int>){
        intArray.add(data)
    }

    fun getDataAsString () : String? {
        getPaddedData(data).let {
            return String(intArrayToByteArray(it.toMutableList()))
        }
    }

    fun getDataSizeLength () : Int {
        getPaddedData(data).let {
            return it.size
        }
    }

    fun intArrayToHexString(intArray: Array<Int>): String {
        val stringBuilder = StringBuilder()

        intArray.reversedArray().forEach { intValue ->
            val hexString = Integer.toHexString(intValue)
            stringBuilder.append(hexString.padStart(2, '0')) // Ensure 8 characters for each integer
        }

        val stringToReturn = stringBuilder.toString().replace("ffffff", "")
        return if(stringToReturn.startsWith("0000")) {
            stringToReturn.replaceFirst("0000","")
        } else {
            stringToReturn
        }
    }

    fun getSourceAsString() : String {
        return intArrayToHexString(sourceBytes).getSrcDestMin4Bytes().lowercase()
    }

    fun getDestAsString() : String {
        return try {
            intArrayToHexString(destinationBytes).getSrcDestMin4Bytes().lowercase()
        } catch (e : Exception) {
            "null"
        }

    }

    fun toHex (): String {
        try {
            return getStardustPackageToSend().joinToString(" ") { "%02X".format(it.toLong() and 0xFF) }
        }catch (e : Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun toHexEnc (): String {
        try {
            return getDataForEncryption().joinToString(" ") { "%02X".format(it.toLong() and 0xFF) }
        } catch (e : Exception){
            e.printStackTrace()
            return ""
        }
    }

    fun getPaddedData(data: Array<Int>?, minSize: Int = DEFAULT_PADDED_DATA_SIZE): Array<Int> {
        require(minSize >= 0) { "minSize must be non-negative" }

        val source = data ?: emptyArray()
        if (source.size >= minSize) return source

        val padded = Array(minSize) { 0 }
        source.copyInto(padded, endIndex = source.size)
        return padded
    }


    override fun toString(): String {
        if (stardustOpCode == StardustPackageUtils.StardustOpCode.PING ||
            stardustOpCode == StardustPackageUtils.StardustOpCode.PING_RESPONSE
        ) {
            return ""
        }

        val paddedData = getPaddedData(data)
        val isBinaryPayload =
            stardustControlByte.stardustPackageType == StardustControlByte.StardustPackageType.SPEECH ||
                stardustOpCode == StardustPackageUtils.StardustOpCode.REQUEST_LOCATION

        val dataPreview = if (isBinaryPayload) {
            paddedData.joinToString(" ")
        } else {
            getDataAsString()?.takeIf { it.isNotBlank() } ?: paddedData.joinToString(" ")
        }

        val destination = runCatching { getDestAsString() }.getOrElse { "<unknown>" }
        val source = runCatching { getSourceAsString() }.getOrElse { "<unknown>" }
        val hex = runCatching { toHex() }.getOrElse { "<unavailable>" }
        val beforeEnc = runCatching { toHexEnc() }.getOrElse { "<unavailable>" }

        return buildString {
            appendLine("&&&&&&&&&&&&&&&&&&&&&&&&&&")
            appendLine("StardustPackage")
            appendLine("&&&&&&&&&&&&&&&&&&&&&&&&&&")
            appendLine("OpCode : $stardustOpCode")
            appendLine("Destination : $destination")
            appendLine("Source : $source")
            appendLine("SenderId : $senderId")
            appendLine("GroupId : ${groupId ?: "-"}")
            appendLine(
                "Control : pkg=${stardustControlByte.stardustPackageType}, " +
                    "delivery=${stardustControlByte.stardustDeliveryType}, " +
                    "ack=${stardustControlByte.stardustAcknowledgeType}, " +
                    "part=${stardustControlByte.stardustPartType}, " +
                    "server=${stardustControlByte.stardustServer}, " +
                    "msg=${stardustControlByte.stardustMessageType}"
            )
            appendLine("Length : $length")
            appendLine("Padded Length : ${paddedData.size}")
            appendLine("Data : $dataPreview")
            appendLine("Data Bytes : ${paddedData.joinToString(" ")}")
            appendLine("toHex : $hex")
            appendLine("Before Enc : $beforeEnc")
            append("&&&&&&&&&&&&&&&&&&&&&&&&&&")
        }
    }

    fun isAbleToSendAgain(): Boolean {
        return retryCounter <= MAX_RETRY_COUNTER
    }

    fun updateRetryCounter(){
        retryCounter++
    }

    fun isAck(): Boolean {
        return data?.getOrNull(0) == Ack
    }

    fun isEqual(pkg: StardustPackage) : Boolean{
        return (this.stardustOpCode == pkg.stardustOpCode &&
                this.stardustControlByte.stardustPackageType == pkg.stardustControlByte.stardustPackageType &&
                this.stardustControlByte.stardustAcknowledgeType == pkg.stardustControlByte.stardustAcknowledgeType &&
                this.stardustControlByte.stardustPackageType == pkg.stardustControlByte.stardustPackageType &&
                this.stardustControlByte.stardustServer == pkg.stardustControlByte.stardustServer &&
                this.stardustControlByte.stardustMessageType == pkg.stardustControlByte.stardustMessageType &&
                this.data.contentEquals(pkg.data))
    }
}

fun String.getSrcDestMin4Bytes() : String {
    var output = this
    while (output.length < 8){
        output = "0$output"
    }
    return output.lowercase()
}
