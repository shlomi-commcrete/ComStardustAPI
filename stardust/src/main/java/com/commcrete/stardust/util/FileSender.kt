package com.commcrete.stardust.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.CarriersUtils.getRadioToSend
import com.commcrete.stardust.util.FileUtils.FileType
import com.commcrete.stardust.util.FileUtils.decompressTextFile
import com.commcrete.stardust.util.FileUtils.trimUntilUnderscore
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.Date

class FileSender(val context: Context, val data: FileUtils.FileTransferData.Send) {

    private val isSendingInProgress : MutableLiveData<Boolean> = MutableLiveData(false)
    private val sendingPercentage : MutableLiveData<Int> = MutableLiveData(0)
    private var isComplete : MutableLiveData<Boolean> = MutableLiveData(false)
    private val mutablePackagesMap : MutableMap<Float, StardustFilePackage > = mutableMapOf()
    private var current : MutableLiveData<Float> = MutableLiveData(0f)
    private var sendInterval : Long = 900
    private var packagesSent = 0
    private var onFileStatusChange : OnFileStatusChange? = null
    private val handler : Handler = Handler(Looper.getMainLooper())
    var randomMisses : MutableSet<Int> = mutableSetOf()
    private val runnable : Runnable = Runnable {
        val mPackage = mutablePackagesMap[current.value]
        if(mPackage != null) {
//            if(!randomMisses.contains(current.value?.toInt())) {
            sendPackage(stardustFilePackage = mPackage)
//            }
        }
        current.value = current.value?.plus(1)
        resetSendTimer()
        updateStep(mutablePackagesMap.size)
    }

    fun sendFile(onFileStatusChange: OnFileStatusChange) {

        this.onFileStatusChange = onFileStatusChange
        isSendingInProgress.value = true
        val fileList = listOf(data.file)
        val numOfPackages = calculateNumOfPackages(fileList, data.stardustAPIPackage.spare)
        this.onFileStatusChange?.startSending(data)
        Scopes.getDefaultCoroutine().launch {
            var packages =  createPackages(fileList)

            if(data.stardustAPIPackage.spare > 0) {
                val dataWithSpare = createSparePackages(packages, data.stardustAPIPackage.spare)
                packages = dataWithSpare.first
                createStartPackage(
                    totalPackages = numOfPackages,
                    spareData = dataWithSpare.second)
            } else {
                createStartPackage(
                    totalPackages = numOfPackages,
                    spareData =  0
                )
            }
            getRandomMisses(data.stardustAPIPackage.spare, numOfPackages)
            mutablePackagesMap.clear()
            mutablePackagesMap.putAll(packages)
//        testDecode(packages, stardustAPIPackage.spare)
            resetSendTimer()
        }

        saveLocalMessages()
    }

    fun stopSendingPackages() {
        removeSendTimer()
        isSendingInProgress.value = false
        sendingPercentage.value = 0
        current.value = 0f
        packagesSent = 0
        isComplete.value = false
        this.onFileStatusChange?.stopSending(data)
        sendInterval = 900
    }

    private fun updateStep (numOfPackages: Int) {
        sendingPercentage.value = ((current.value?.toDouble()?.div(numOfPackages))?.times(100))?.toInt()
//        Log.d("updateStep", "${sendingPercentage.value}")
        sendingPercentage.value?.let { this.onFileStatusChange?.updateStep(data, it) }
        if(sendingPercentage.value == 100) {
            finishSending()
        }
    }

    private fun getRandomMisses(spare: Int, numOfPackages: Int): List<Int> {
//        val count = maxOf(1, (kotlin.math.sqrt(spare.toDouble())).toInt())
        val count = spare
        randomMisses = mutableSetOf<Int>()

        while (randomMisses.size < count) {
            val rand = (0 until numOfPackages).random()
            randomMisses.add(rand)
        }

        Log.d("randomMisses" , "randomMisses $randomMisses")
        return randomMisses.toList()
    }

    private fun saveLocalMessages () {
        val chatID = data.stardustAPIPackage.destination
        val userId = data.stardustAPIPackage.source
        val fileLocation = data.file.absolutePath

        Scopes.getDefaultCoroutine().launch {
            val text = (if (data.fileType == FileType.File) "File Sent" else "Image Sent") +  ": ${data.file.name}"

            // Create the destination directory
            val destDir = File("${context.filesDir}/$chatID/files")
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            // Create the destination file
            val originalFile = File(fileLocation)
            // Determine the file extension
            val fileExtension = data.file.extension

            // Create the destination file with the appropriate extension
            val destFile = File(destDir, "${File(fileLocation).nameWithoutExtension}$fileExtension")


            try {
                // Copy the file to the destination
                when(data.fileType) {
                    FileType.Image -> {
                        originalFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    FileType.File -> {
                        decompressTextFile(originalFile, destFile)
                    }
                }

                // Use the new location for `fileLocation`
                val newFileLocation = destFile.absolutePath

                val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
                val messagesRepository = DataManager.getMessagesRepo(context)
                val messageItem = MessageItem(
                    senderID = userId,
                    text = text,
                    epochTimeMs = Date().time,
                    chatId = chatID,
                    isImage = data.fileType == FileUtils.FileType.Image,
                    isFile = data.fileType == FileUtils.FileType.File,
                    fileLocation = newFileLocation // Updated location
                )

                val chatItem = chatsRepo.getChatByBittelID(chatID)
                chatItem?.message = Message(
                    senderID = userId,
                    text = text,
                    seen = false
                )

                messagesRepository.saveMessage(context, messageItem)
                chatItem?.let { chatsRepo.addChat(it) }

            } catch (e: Exception) {
                e.printStackTrace()
                println("Error saving file locally: ${e.message}")
            }
        }
    }

    private fun first50BytesUtf8(input: String): String {
        val utf8 = input.toByteArray(Charsets.UTF_8)
        if (utf8.size <= 50) return input

        // Take only the first 50 bytes
        val cut = utf8.copyOf(50)

        // Now we need to avoid splitting a multi-byte char.
        // We trim invalid trailing bytes.
        var end = cut.size
        while (end > 0) {
            val tryString = try {
                String(cut.copyOf(end), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
            if (tryString != null) {
                return tryString
            }
            end--
        }

        return "" // fallback, should never happen
    }

    private fun getFileNameAndType(file: File): Pair<String, String> {
        val name = trimUntilUnderscore(file.nameWithoutExtension)
        val ext = file.extension.ifBlank { "unknown" }

        // Cut name to max 50 chars (avoid IndexOutOfBounds)
        val safeName = if (name.length > 50) name.substring(0, 50) else name

        return safeName to ext
    }

    private fun createStartPackage (
        totalPackages: Int,
        spareData: Int
    ){
        val fileName = first50BytesUtf8(data.file.nameWithoutExtension)
        val fileEnding = data.file.extension

        val fileStart = StardustFileStartPackage(type = data.fileType.bitCode, total = totalPackages, data.stardustAPIPackage.spare, spareData, fileEnding, fileName)

        val radio = getRadioToSend(functionalityType = data.fileType.relatedFunctionalityType(), carrier = data.stardustAPIPackage.carrier)  ?: return

        DataManager.getClientConnection(context).let {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let { appId ->
                val sosString = "STR"
                val sosBytes = sosString.toByteArray()
                var dataToSend : Array<Int> = arrayOf()
                dataToSend = dataToSend.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes).size + fileStart.toArrayInt().size)
                dataToSend = dataToSend.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes))
                dataToSend = dataToSend.plus(fileStart.toArrayInt())
                val fileStartMessage = StardustPackageUtils.getStardustPackage(
                    context,
                    source = appId,
                    destenation = data.stardustAPIPackage.destination,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                    data = dataToSend)
                fileStartMessage.stardustControlByte.stardustDeliveryType = radio.second
                it.addMessageToQueue(fileStartMessage)
            }
        }
    }

    private fun createSparePackages(
        packages: Map<Float, StardustFilePackage>,
        spare: Int
    ): Pair<Map<Float, StardustFilePackage>, Int> {
//        val ldpc = LDPCCode(maxPackets = packages.size, parityPackets = spare)
        val reed = ReedSolomon(totalDataPackets = packages.size, totalParityPackets = spare)
        val dataList = packages.map { it.value.data }.toMutableList()

        var paddingAdded = 0

        // Pad the last packet to 135 bytes if needed
        if (dataList.isNotEmpty()) {
            val lastIndex = dataList.lastIndex
            val originalSize = dataList[lastIndex].size
            if (originalSize < FILE_CHUNK_SIZE) {
                paddingAdded = FILE_CHUNK_SIZE - originalSize
                dataList[lastIndex] = dataList[lastIndex].copyOf(FILE_CHUNK_SIZE) // Pads with zeros
            }
        }

        val newArray = reed.encode(dataList)
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

    private fun saveTempFile(dataWithSpare: Pair<Map<Float, StardustFilePackage>, Int>) {
        val destDir = File("${DataManager.context.filesDir}/10000023/files")
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val ts = System.currentTimeMillis()
        val targetFile = File(destDir, "temm_save_$ts.txt")
        val datas = dataWithSpare.first.values
        FileOutputStream(targetFile).use { outputStream ->
            for (data in datas) {
                outputStream.write(data.data)
            }
        }
    }

    private fun testDecode(packages: Map<Float, StardustFilePackage>, spare: Int) {
        val data = packages.values.map { it.data }
        val ldpc = LDPCCode(maxPackets = packages.size, parityPackets = spare)
        val decoded = ldpc.decode(data, listOf(2))
//        Log.d("decoded", "decoded = ${decoded.size}")
    }

    private fun createPackages(files: List<File>): Map<Float, StardustFilePackage> {
        var packageIndex = 0
        var packageData = 0
        val bittelFileList = mutableMapOf<Float, StardustFilePackage>()

        for ((fileIndex, file) in files.withIndex()) { // Iterate with index
            val fileBytes = file.readBytes() // Read file as byte array
            Timber.tag("FileUpload").d("fileBytes : ${fileBytes.size}")

            var offset = 0
            while (offset < fileBytes.size) {
                val end = minOf(offset + FILE_CHUNK_SIZE, fileBytes.size) // Calculate end of the chunk
                val chunk = fileBytes.copyOfRange(offset, end) // Create the chunk

                packageData += chunk.size
                val isLast = (offset + FILE_CHUNK_SIZE >= fileBytes.size) && (fileIndex == files.lastIndex) // Check if last package

                bittelFileList[packageIndex.toFloat()] = StardustFilePackage(
                    current = packageIndex,
                    data = chunk,
                    isLast = isLast // Set last package flag
                )

                Timber.tag("FileUpload").d("chunk : ${chunk.size}, isLast: $isLast")

                offset += FILE_CHUNK_SIZE
                packageIndex++
            }
        }

        Timber.tag("FileUpload").d("Total chunkSize : $packageData")
        Timber.tag("FileUpload").d("Total packages created : ${bittelFileList.size}")

        return bittelFileList
    }

    private fun sendPackage (stardustFilePackage: StardustFilePackage) {
        DataManager.getClientConnection(context).let {
            val radio = CarriersUtils.getRadioToSend(functionalityType =  if(data.fileType == FileType.File)
                FunctionalityType.FILE else FunctionalityType.IMAGE, carrier = data.stardustAPIPackage.carrier
            )  ?: return
            this.sendInterval = if(radio.first.type == StardustConfigurationParser.StardustTypeFunctionality.ST) {
                300
            } else {
                900
            }
            val fileStartMessage = StardustPackageUtils.getStardustPackage(
                context = context,
                source = data.stardustAPIPackage.source,
                destenation = data.stardustAPIPackage.destination,
                stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
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


    private fun finishSending() {
        isSendingInProgress.value = false
        sendingPercentage.value = 0
        removeSendTimer()
        current.value = 0f
        packagesSent = 0
        Handler(Looper.getMainLooper()).postDelayed({ isComplete.value = false }, 3000)
        isComplete.value = true
        onFileStatusChange?.finishSending(data)
        sendInterval = 900
    }

    interface OnFileStatusChange {
        fun startSending(data: FileUtils.FileTransferData.Send) {}
        fun finishSending(data: FileUtils.FileTransferData.Send) {}
        fun stopSending(data: FileUtils.FileTransferData.Send) {}
        fun updateStep (data: FileUtils.FileTransferData.Send, percentage : Int) {}
    }

    companion object {

        private const val FILE_CHUNK_SIZE = 60
        fun calculateNumOfPackages(files: List<File>, spare: Int) : Int {
            var length = 0
            val chunkSize = FILE_CHUNK_SIZE
            for (file in files) {
                length = length + (file.length().div(chunkSize)).toInt()
                if(file.length().mod(chunkSize) != 0) {
                    length ++
                }
            }
            return length + spare
        }

        fun calculateSendTime(numOfPackages: Int, functionalityType: FunctionalityType): String {
            val radio: Pair<Carrier?, StardustControlByte.StardustDeliveryType?>? =
                getRadioToSend(null, functionalityType)

            val totalTime =
                if (radio?.first != null &&
                    radio.first?.component2() == StardustConfigurationParser.StardustTypeFunctionality.ST
                ) 0.3 else 1.3

            val totalSeconds = numOfPackages * totalTime // Total time in seconds as a Double
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

        fun calculateAddedPackages (numOfPackages: Int) : Int{
            val factor = SharedPreferencesUtil.getResilience(DataManager.context)
            return packageNumToAdd(numOfPackages, factor.value)
        }

        private fun packageNumToAdd(packageNum: Int, factor: Int): Int {
            require(factor in listOf(20, 60, 120)) { "Factor must be one of: 20, 60, or 120" }
            require(packageNum > 0) { "packageNum must be > 0" }

            // Step 1: raw percentage
            var percent = 10 + factor / kotlin.math.sqrt(packageNum.toDouble())

            // Step 2: clamp between 5% and 100%
            percent = percent.coerceIn(5.0, 100.0)

            // Step 3: calculate packages to add
            var toAdd = kotlin.math.ceil(packageNum * (percent / 100)).toInt()

            // Step 4: enforce minimum 2 packages
            toAdd = maxOf(2, toAdd)

            return toAdd
        }
    }
}

enum class Resilience (val value : Int) {
    Low (20),
    Medium (60),
    High (120),
}
