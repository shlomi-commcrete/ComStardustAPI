package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import java.util.zip.GZIPInputStream

object FileSendUtils {

    val isSendingInProgress : MutableLiveData<Boolean> = MutableLiveData(false)
    val sendingPercentage : MutableLiveData<Int> = MutableLiveData(0)
    private var isComplete : MutableLiveData<Boolean> = MutableLiveData(false)
    private val mutablePackagesMap : MutableMap<Float,StardustFilePackage > = mutableMapOf()
    private var current : MutableLiveData<Float> = MutableLiveData(0f)
    private var sendInterval : Long = 1300

    private var packagesSent = 0
    private var dest : String? = null
    private var onFileStatusChange : OnFileStatusChange? = null
    private var sendType : StardustFileStartParser.FileTypeEnum? = null
    private var stardustAPIPackage : StardustAPIPackage? = null
    private val handler : Handler = Handler(Looper.getMainLooper())
    var randomMisses : MutableSet<Int> = mutableSetOf()
    private val runnable : Runnable = Runnable {
        val mPackage = mutablePackagesMap[current.value]
        if(mPackage != null){
//            if(!randomMisses.contains(current.value?.toInt())) {
                sendPackage(mPackage, dest)
//            }
        }
        current.value = current.value?.plus(1)
        resetSendTimer()
        updateStep(mutablePackagesMap.size, )
    }

    fun sendFile (stardustAPIPackage: StardustAPIPackage, file: File, fileStartParser: StardustFileStartParser.FileTypeEnum, onFileStatusChange: OnFileStatusChange) {
        this.onFileStatusChange = onFileStatusChange
        this.stardustAPIPackage = stardustAPIPackage
        isSendingInProgress.value = true
        val fileList = listOf(file)
        val numOfPackages = calculateNumOfPackages(fileList, stardustAPIPackage.spare)
        this.onFileStatusChange?.startSending()
        dest = stardustAPIPackage.destination
        var packages =  createPackages(fileList)

        if(stardustAPIPackage.spare > 0) {
            val dataWithSpare = createSparePackages(packages, stardustAPIPackage.spare)
           packages = dataWithSpare.first
            createStartPackage(fileStartParser,
                numOfPackages, dest, stardustAPIPackage, dataWithSpare.second)
        } else {
            createStartPackage(fileStartParser,
                numOfPackages, dest, stardustAPIPackage, 0)
        }
//        getRandomMisses(stardustAPIPackage.spare, numOfPackages)
        mutablePackagesMap.clear()
        mutablePackagesMap.putAll(packages)
//        testDecode(packages, stardustAPIPackage.spare)
        resetSendTimer()
        saveLocalMessages(
            stardustAPIPackage.destination,stardustAPIPackage.source, fileStartParser,
            fileLocation = fileList[0].absolutePath)
    }

    private fun updateStep (numOfPackages: Int) {
        sendingPercentage.value = ((current.value?.toDouble()?.div(numOfPackages))?.times(100))?.toInt()
//        Log.d("updateStep", "${sendingPercentage.value}")
        sendingPercentage.value?.let { this.onFileStatusChange?.updateStep(it) }
        if(sendingPercentage.value == 100) {
            finishSending()
        }
    }

    private fun getRandomMisses(spare: Int, numOfPackages: Int): List<Int> {
        val count = maxOf(1, (kotlin.math.sqrt(spare.toDouble())).toInt())
        randomMisses = mutableSetOf<Int>()

        while (randomMisses.size < count) {
            val rand = (0 until numOfPackages).random()
            randomMisses.add(rand)
        }

        return randomMisses.toList()
    }

    private fun saveLocalMessages (chatID : String, userId : String, fileTypeEnum: StardustFileStartParser.FileTypeEnum,
                                   fileLocation : String) {
        val context = DataManager.context
        Scopes.getDefaultCoroutine().launch {
            val text = if (fileTypeEnum == StardustFileStartParser.FileTypeEnum.TXT) "File Sent" else "Image Sent"
            val isImage = (fileTypeEnum == StardustFileStartParser.FileTypeEnum.JPG)
            val isFile = (fileTypeEnum == StardustFileStartParser.FileTypeEnum.TXT)

            // Create the destination directory
            val destDir = File("${context.filesDir}/$chatID/files")
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            // Create the destination file
            val originalFile = File(fileLocation)
            // Determine the file extension
            val fileExtension = if (isImage) ".jpg" else if (isFile) ".txt" else ""

            // Create the destination file with the appropriate extension
            val destFile = File(destDir, "${File(fileLocation).nameWithoutExtension}$fileExtension")


            try {
                // Copy the file to the destination
                if(isFile){
                    decompressTextFile(originalFile, destFile)
                }else {
                    // Copy the file directly from the originalFile to the destFile
                    originalFile.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                // Use the new location for `fileLocation`
                val newFileLocation = destFile.absolutePath

                val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
                val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())
                val messageItem = MessageItem(
                    senderID = userId,
                    text = text,
                    epochTimeMs = Date().time,
                    chatId = chatID,
                    isImage = isImage,
                    isFile = isFile,
                    fileLocation = newFileLocation // Updated location
                )

                val chatItem = chatsRepo.getChatByBittelID(chatID)
                chatItem?.message = Message(
                    senderID = userId,
                    text = text,
                    seen = false
                )

                messagesRepository.saveFileMessage(messageItem)
                chatItem?.let { chatsRepo.addChat(it) }

            } catch (e: Exception) {
                e.printStackTrace()
                println("Error saving file locally: ${e.message}")
            }
        }
    }
    private fun createStartPackage (
        type: StardustFileStartParser.FileTypeEnum,
        totalPackages: Int,
        dest: String?,
        stardustAPIPackage: StardustAPIPackage,
        spareData : Int
    ){
        if(dest == null) {
            return
        }
        // TODO: Add encode and spare
        val fileStart =  StardustFileStartPackage(type = type.type, total = totalPackages, stardustAPIPackage.spare, spareData)
        sendType = type
        val radio = CarriersUtils.getRadioToSend(functionalityType =  if(type == StardustFileStartParser.FileTypeEnum.TXT)
            FunctionalityType.FILE else FunctionalityType.IMAGE, carrier = stardustAPIPackage.carrier
        )
        DataManager.getClientConnection(DataManager.context).let {
            SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let { appId ->
                val sosString = "STR"
                val sosBytes = sosString.toByteArray()
                var data : Array<Int> = arrayOf()
                data = data.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes).size + fileStart.toArrayInt().size)
                data = data.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes))
                data = data.plus(fileStart.toArrayInt())
                val fileStartMessage = StardustPackageUtils.getStardustPackage(
                    source = appId , destenation = dest, stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                    data = data)
                fileStartMessage.stardustControlByte.stardustDeliveryType = radio.second
                it.addMessageToQueue(fileStartMessage)
            }
        }
    }
    private fun createSparePackages(
        packages: Map<Float, StardustFilePackage>,
        spare: Int
    ): Pair<Map<Float, StardustFilePackage>, Int> {
        val ldpc = LDPCCode(maxPackets = packages.size, parityPackets = spare)
        val dataList = packages.map { it.value.data }.toMutableList()

        var paddingAdded = 0

        // Pad the last packet to 135 bytes if needed
        if (dataList.isNotEmpty()) {
            val lastIndex = dataList.lastIndex
            val originalSize = dataList[lastIndex].size
            if (originalSize < 135) {
                paddingAdded = 135 - originalSize
                dataList[lastIndex] = dataList[lastIndex].copyOf(135) // Pads with zeros
            }
        }

        val newArray = ldpc.encode(dataList)
        val bittelFileList = mutableMapOf<Float, StardustFilePackage>()

        for ((index, data) in newArray.withIndex()) {
            val isLast = index == newArray.lastIndex
            bittelFileList[index.toFloat()] = StardustFilePackage(
                current = index,
                data = data,
                isLast = isLast
            )
        }

        return Pair(bittelFileList, paddingAdded)
    }

    private fun testDecode(packages: Map<Float, StardustFilePackage>, spare: Int) {
        val data = packages.values.map { it.data }
        val ldpc = LDPCCode(maxPackets = packages.size, parityPackets = spare)
        val decoded = ldpc.decode(data, listOf(2))
        Log.d("decoded", "decoded = ${decoded.size}")
    }

    private fun createPackages(files: List<File>): Map<Float, StardustFilePackage> {
        var packageIndex = 0
        var packageData = 0
        val chunkSize = 135
        val bittelFileList = mutableMapOf<Float, StardustFilePackage>()

        for ((fileIndex, file) in files.withIndex()) { // Iterate with index
            val fileBytes = file.readBytes() // Read file as byte array
            Timber.tag("FileUpload").d("fileBytes : ${fileBytes.size}")

            var offset = 0
            while (offset < fileBytes.size) {
                val end = minOf(offset + chunkSize, fileBytes.size) // Calculate end of the chunk
                val chunk = fileBytes.copyOfRange(offset, end) // Create the chunk

                packageData += chunk.size
                val isLast = (offset + chunkSize >= fileBytes.size) && (fileIndex == files.lastIndex) // Check if last package

                bittelFileList[packageIndex.toFloat()] = StardustFilePackage(
                    current = packageIndex,
                    data = chunk,
                    isLast = isLast // Set last package flag
                )

                Timber.tag("FileUpload").d("chunk : ${chunk.size}, isLast: $isLast")

                offset += chunkSize
                packageIndex++
            }
        }

        Timber.tag("FileUpload").d("Total chunkSize : $packageData")
        Timber.tag("FileUpload").d("Total packages created : ${bittelFileList.size}")

        return bittelFileList
    }


    fun decompressTextFile(inputFile: File, outputFile: File) {
        FileInputStream(inputFile).use { fis ->
            GZIPInputStream(fis).use { gzis ->
                FileOutputStream(outputFile).use { fos ->
                    gzis.copyTo(fos)
                }
            }
        }
    }

    fun handleFileStartReceive (
        bittelFileStartPackage: StardustFileStartPackage,
        mPackage: StardustPackage
    ) {
        FileReceivedUtils.getInitFile(bittelFileStartPackage, mPackage)
    }

    fun handleFileReceive (bittelFilePackage : StardustFilePackage,
                           mPackage: StardustPackage) {
        FileReceivedUtils.getFile(bittelFilePackage, mPackage)
    }

    private fun sendPackage (stardustFilePackage: StardustFilePackage, dest : String?) {
        if(dest == null) {
            return
        }
        DataManager.getClientConnection(DataManager.context).let {
            val radio = CarriersUtils.getRadioToSend(functionalityType =  if(sendType == StardustFileStartParser.FileTypeEnum.TXT)
                FunctionalityType.FILE else FunctionalityType.IMAGE, carrier = stardustAPIPackage?.carrier
            )
            this.sendInterval = if(radio.first != null && radio.first!!.type == StardustConfigurationParser.StardustTypeFunctionality.ST) {
                300
            } else {
                1300
            }
            SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let { appId ->
                val fileStartMessage = StardustPackageUtils.getStardustPackage(
                    source = appId , destenation = dest, stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                    data = stardustFilePackage.toArrayInt())
                fileStartMessage.stardustControlByte.stardustDeliveryType = radio.second
                if(stardustFilePackage.isLast) {
                    fileStartMessage.stardustControlByte.stardustPartType = StardustControlByte.StardustPartType.LAST
                }
                packagesSent ++
                Timber.tag("FileUpload").d("send : $packagesSent")
                it.addMessageToQueue(fileStartMessage)
            }
        }
    }

    private fun resetSendTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, sendInterval)
    }

    private fun removeSendTimer() {
        try {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateNumOfPackages(files: List<File>, spare: Int) : Int {
        var length = 0
        val chunkSize = 135
        for (file in files) {
            length = length + (file.length().div(chunkSize)).toInt()
            if(file.length().mod(chunkSize) != 0) {
                length ++
            }
        }
        return length + spare
    }

    private fun calculateSendTime(numOfPackages: Int): String {
        val totalSeconds = numOfPackages * 1.3 // Total time in seconds as a Double
        val minutes = totalSeconds.toInt() / 60 // Whole minutes
        val seconds = totalSeconds % 60 // Remaining seconds

        return if (minutes > 0) {
            String.format(
                "%d minute%s and %.1f second%s",
                minutes,
                if (minutes > 1) "s" else "",
                seconds,
                if (seconds > 1.0) "s" else ""
            )
        } else {
            String.format("%.1f second%s", totalSeconds, if (totalSeconds > 1.0) "s" else "")
        }
    }

    fun stopSendingPackages() {
        removeSendTimer()
        isSendingInProgress.value = false
        sendingPercentage.value = 0
        current.value = 0f
        packagesSent = 0
        isComplete.value = false
        this.onFileStatusChange?.stopSending()
        sendType = null
        sendInterval = 1300
    }

    private fun finishSending() {
        isSendingInProgress.value = false
        sendingPercentage.value = 0
        removeSendTimer()
        current.value = 0f
        packagesSent = 0
        Handler(Looper.getMainLooper()).postDelayed({isComplete.value = false}, 3000)
        isComplete.value = true
        this.onFileStatusChange?.finishSending()
        sendType = null
        sendInterval = 1300
    }

    interface OnFileStatusChange {
        fun startSending () {}
        fun finishSending () {}
        fun stopSending () {}
        fun updateStep (percentage : Int) {}
    }
}