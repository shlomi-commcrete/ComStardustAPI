package com.commcrete.stardust.stardust.model

data class StardustUpdateData (
    val CRC1 : ByteArray,
    val CRC2 : ByteArray,
    val fileSize1 : ByteArray,
    val fileSize2 : ByteArray,
    val fileSizePadded1 : ByteArray,
    val fileSizePadded2 : ByteArray,
    val data1 : ByteArray,
    val data2 : ByteArray,
    val packageSize : Int,
    val totalSize : Int,
    var bootAddress : Array<Int>? = null,
    var packageUpdateNum : Int = 0
){
    companion object{
        val bootAddress1 = arrayOf(0,0,0,8)
    }

    fun getCurrentPackageToBurn(): ByteArray {
        val data = if (isBoot1()) data2 else data1
        return data.copyOfRange(getCurrentPackageNum(), packageSize)
    }

    fun getCurrentPackageNum (): Int {
        return packageUpdateNum * packageSize
    }

    fun isLastPackageToBurn(): Byte {
        return if ((totalSize - getCurrentPackageNum()) == packageSize) 1 else 0
    }

    fun isBoot1 () : Boolean{
        return bootAddress.contentEquals(bootAddress1)
    }

    fun getCRC () : ByteArray {
        return if(isBoot1()) CRC2 else CRC1
    }

    fun updatePackageNum () {
        packageUpdateNum++
    }
}