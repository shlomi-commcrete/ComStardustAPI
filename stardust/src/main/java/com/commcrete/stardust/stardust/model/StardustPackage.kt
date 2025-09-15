package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.crypto.CryptoUtils
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.Ack
import com.commcrete.stardust.stardust.StardustPackageUtils.byteArrayToIntArray

data class StardustPackage(
    val syncBytes: Array<Int>, var destinationBytes: Array<Int>, var sourceBytes: Array<Int>,
    var stardustControlByte: StardustControlByte, var stardustOpCode: StardustPackageUtils.StardustOpCode,
    val length: Int, var data: Array<Int>? = null, var checkXor: Int? = 0,
    var pullTimer : Int = 0,
    var lengthForCrypt : Int = 0,
    var openControlByte: OpenStardustControlByte = OpenStardustControlByte(),
    var cryptData : Array<Int> = arrayOf()
) : StardustParser(){

    var retryCounter = 0

    var isDemandAck : Boolean? = false
    var messageNumber : Int = 1
    var idNumber : Long = 1


    companion object{
        const val MAX_RETRY_COUNTER = 0
        const val DELAY_TS = 15L

        fun isDifferentLengthCheck(
            StardustControlByte: StardustControlByte,
            StardustOpCode: StardustPackageUtils.StardustOpCode
        ) : Boolean{
            return StardustOpCode == StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION
//                    || (StardustOpCode == StardustPackageUtils.StardustOpCode.SEND_MESSAGE && StardustControlByte.StardustlPackageType == StardustControlByte.StardustPackageType.DATA)
        }
    }

    private fun encryptData () {
        val byteArray = getDataForEncryption()
        val encrypted = CryptoUtils.encryptData(byteArray)
        lengthForCrypt = byteArray.size
        cryptData = byteArrayToIntArray(encrypted)
    }

    private fun getDataForEncryption () : ByteArray{
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

    fun getStardustPackageToCheckXor () : MutableList<Int> {
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

    fun getStardustPackageToSendList () : List<ByteArray>{
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
        for( mPackage in listIterator){
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
        return null
    }

    fun getDataSizeLength () : Int {
        getPaddedData(data).let {
            return it.size
        }
        return 0
    }

    fun intArrayToHexString(intArray: Array<Int>): String {
        val stringBuilder = StringBuilder()

        intArray.reversedArray().forEach { intValue ->
            val hexString = Integer.toHexString(intValue)
            stringBuilder.append(hexString.padStart(2, '0')) // Ensure 8 characters for each integer
        }

        val stringToReturn = stringBuilder.toString().replace("ffffff", "")
        if(stringToReturn.startsWith("0000")){
            return stringToReturn.replaceFirst("0000","")
        }else {
            return stringToReturn
        }
    }

    fun getSourceAsString () : String {
        return intArrayToHexString(sourceBytes).getSrcDestMin4Bytes()
    }

    fun getDestAsString () : String {
        return try {
            intArrayToHexString(destinationBytes).getSrcDestMin4Bytes()
        }catch (e : Exception) {
            "null"
        }

    }

    fun toHex (): String {
        try {
            return getStardustPackageToSend ().joinToString(" ") { "%02X".format(it.toLong() and 0xFF) }
        }catch (e : Exception){
            e.printStackTrace()
            return ""
        }
    }

    fun toHexEnc (): String {
        try {
            return getDataForEncryption().joinToString(" ") { "%02X".format(it.toLong() and 0xFF) }
        }catch (e : Exception){
            e.printStackTrace()
            return ""
        }
    }

    fun getPaddedData(data: Array<Int>?): Array<Int> {
        val size = 4
        if (data == null) {
            // null → return zero-padded array
            return Array(size) { 0 }
        }
        if (data.size < size) {
            // smaller → pad with zeros
            val result = Array(size) { 0 }
            for (i in data.indices) {
                result[i] = data[i]
            }
            return result
        }
        // large enough → return original array
        return data
    }

    fun dataToHex (): String {
        try {
            getPaddedData(data).let {
                val stringBuilder = StringBuilder()
                stringBuilder.append(intArrayToHexString(it))
                stringBuilder.append("\n")
            }
        }catch (e : Exception){
            e.printStackTrace()
            return "\n"
        }
        return "\n"
    }

    fun encryptPackage () {

    }

    fun decryptPackage () {

    }

    override fun toString(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("&&&&&&&&&&&&&&&&&&&&&&&&&&\n")
        stringBuilder.append("Full Byte Array : \n")
        stringBuilder.append("&&&&&&&&&&&&&&&&&&&&&&&&&&\n")
        stringBuilder.append("OpCode : ${stardustOpCode}\n")
        stringBuilder.append("Destenation : ${getDestAsString()}\n")
        stringBuilder.append("Source : ${getSourceAsString()}\n")
        stringBuilder.append("Length : $length\n")
        getPaddedData(data).let {
            if(stardustControlByte.stardustPackageType == StardustControlByte.StardustPackageType.SPEECH || stardustOpCode == StardustPackageUtils.StardustOpCode.REQUEST_LOCATION ){
                stringBuilder.append("Data : \n")
                for (mData in it ) {
                    stringBuilder.append("$mData ")
                }
                stringBuilder.append("\n")
            }else {
                stringBuilder.append("Data : ${getDataAsString()}\n")
            }
        }
        try {
            stringBuilder.append("toHex : ${toHex()}\n")
        }catch (e : Exception) {
            e.printStackTrace()
        }
        try {
            stringBuilder.append("Before Enc : ${toHexEnc()}\n")
        }catch (e : Exception) {
            e.printStackTrace()
        }
        try {
            stringBuilder.append("Padded Data : ${getPaddedData(data)}\n")
        }catch (e : Exception) {
            e.printStackTrace()
        }
        stringBuilder.append("&&&&&&&&&&&&&&&&&&&&&&&&&&\n")
        return stringBuilder.toString()
    }

    fun isAbleToSendAgain(): Boolean {
        return retryCounter <= MAX_RETRY_COUNTER
    }

    fun updateRetryCounter(){
        retryCounter++
    }

    fun isAck(): Boolean {
        if(data!=null && data?.size!! >= 1){
            return (data!![0] == Ack)
        }
        return false
    }

    fun isEqual(bittelPackage: StardustPackage) : Boolean{
        return (this.stardustOpCode == bittelPackage.stardustOpCode &&
                this.stardustControlByte.stardustPackageType == bittelPackage.stardustControlByte.stardustPackageType &&
                this.stardustControlByte.stardustAcknowledgeType == bittelPackage.stardustControlByte.stardustAcknowledgeType &&
                this.stardustControlByte.stardustPackageType == bittelPackage.stardustControlByte.stardustPackageType &&
                this.stardustControlByte.stardustServer == bittelPackage.stardustControlByte.stardustServer &&
                this.stardustControlByte.stardustMessageType == bittelPackage.stardustControlByte.stardustMessageType
                && this.data.contentEquals(bittelPackage.data))
    }

}

fun String.getSrcDestMin4Bytes() : String {
    var output = this
    while (output.length < 8){
        output = "0$output"
    }
    return output
}
