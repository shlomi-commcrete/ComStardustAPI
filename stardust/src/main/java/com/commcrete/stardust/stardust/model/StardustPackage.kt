package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.Ack

data class StardustPackage(
    val syncBytes: Array<Int>, var destinationBytes: Array<Int>, var sourceBytes: Array<Int>,
    var stardustControlByte: StardustControlByte, var stardustOpCode: StardustPackageUtils.StardustOpCode,
    val length: Int, var data: Array<Int>? = null, var checkXor: Int? = 0,
    var pullTimer : Int = 0
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

    fun getStardustPackageToCheckXor () : MutableList<Int> {
        val packageToCheck = mutableListOf<Int>()
        for (data in syncBytes) {
            appendToIntArray(data, packageToCheck)
        }
        for (data in destinationBytes) {
            appendToIntArray(data, packageToCheck)
        }
        for (data in sourceBytes) {
            appendToIntArray(data, packageToCheck)
        }
        appendToIntArray(stardustControlByte.getControlByteValue(), packageToCheck)
        appendToIntArray(stardustOpCode.codeID, packageToCheck)
        appendToIntArray(length, packageToCheck)
        data?.let { dataList ->
            for (data in dataList) {
                appendToIntArray(data, packageToCheck)
            }
        }
        return packageToCheck
    }

    fun getStardustPackageToSend () : ByteArray{
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
        data?.let { dataList ->
            for (data in dataList) {
                appendToIntArray(data, packageToSend)
            }
        }
        checkXor?.let { checkXor ->
            appendToIntArray(checkXor, packageToSend)
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
        data?.let { dataList ->
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
        data?.let {
            return String(intArrayToByteArray(it.toMutableList()))
        }
        return null
    }

    fun getDataSizeLength () : Int {
        data?.let {
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

    fun dataToHex (): String {
        try {
            data?.let {
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

    override fun toString(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("&&&&&&&&&&&&&&&&&&&&&&&&&&\n")
        stringBuilder.append("Full Byte Array : \n")
        stringBuilder.append("&&&&&&&&&&&&&&&&&&&&&&&&&&\n")
        stringBuilder.append("Destenation : ${getDestAsString()}\n")
        stringBuilder.append("Source : ${getSourceAsString()}\n")
        stringBuilder.append("Length : $length\n")
        data?.let {
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

    fun isEqual(StardustPackage: StardustPackage) : Boolean{
        return (this.stardustOpCode == StardustPackage.stardustOpCode && this.stardustControlByte == StardustPackage.stardustControlByte
            && this.data.contentEquals(StardustPackage.data))
    }

}

fun String.getSrcDestMin4Bytes() : String {
    var output = this
    while (output.length < 8){
        output = "0$output"
    }
    return output
}
