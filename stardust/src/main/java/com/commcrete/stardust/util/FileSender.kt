package com.commcrete.stardust.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.CarriersUtils.getRadioToSend
import com.commcrete.stardust.util.FileUtils.FileType
import com.commcrete.stardust.util.FileUtils.decompressTextFile
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.math.ceil

class FileSender(val context: Context, val data: FileUtils.FileTransferData.Send) {

    // Simple vars suffice — these are private and never observed externally
    private var isSendingInProgress = false
    private var sendingPercentage = 0
    private var isComplete = false
    private val mutablePackagesMap: MutableMap<Float, StardustFilePackage> = mutableMapOf()
    private var current = 0f
    private var sendInterval: Long = 900
    private var packagesSent = 0
    private var onFileStatusChange: OnFileStatusChange? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    var randomMisses: MutableSet<Int> = mutableSetOf()

    private val runnable: Runnable = Runnable {
        mutablePackagesMap[current]?.let { sendPackage(it) }
        current += 1f
        resetSendTimer()
        updateStep(mutablePackagesMap.size)
    }

    fun sendFile(onFileStatusChange: OnFileStatusChange) {
        this.onFileStatusChange = onFileStatusChange
        isSendingInProgress = true
        val fileList = listOf(data.file)
        val numOfPackages = calculateNumOfPackages(fileList, data.stardustAPIPackage.spare)
        this.onFileStatusChange?.startSending(data)
        Scopes.getDefaultCoroutine().launch {
            var packages = createPackages(fileList)

            if (data.stardustAPIPackage.spare > 0) {
                val dataWithSpare = createSparePackages(packages, data.stardustAPIPackage.spare)
                packages = dataWithSpare.first
                createStartPackage(totalPackages = numOfPackages, spareData = dataWithSpare.second)
            } else {
                createStartPackage(totalPackages = numOfPackages, spareData = 0)
            }
            getRandomMisses(data.stardustAPIPackage.spare, numOfPackages)
            mutablePackagesMap.clear()
            mutablePackagesMap.putAll(packages)
            resetSendTimer()
        }
        saveLocalMessages()
    }

    fun stopSendingPackages() {
        removeSendTimer()
        isSendingInProgress = false
        sendingPercentage = 0
        current = 0f
        packagesSent = 0
        isComplete = false
        onFileStatusChange?.stopSending(data)
        sendInterval = 900
    }

    private fun updateStep(numOfPackages: Int) {
        sendingPercentage = ((current.toDouble() / numOfPackages) * 100).toInt()
        onFileStatusChange?.updateStep(data, sendingPercentage)
        if (sendingPercentage >= 100) {
            finishSending()
        }
    }

    private fun getRandomMisses(spare: Int, numOfPackages: Int): List<Int> {
        randomMisses = (0 until numOfPackages).shuffled().take(spare).toMutableSet()
        Timber.d("randomMisses: $randomMisses")
        return randomMisses.toList()
    }

    private fun saveLocalMessages() {
        val chatID = data.stardustAPIPackage.destination
        val userId = data.stardustAPIPackage.source

        Scopes.getDefaultCoroutine().launch {
            val text = when (data.fileType) {
                FileType.File -> "File Sent"
                FileType.Image -> "Image Sent"
            } + ": ${data.file.name}"

            val destDir = File("${context.filesDir}/$chatID/files").also { it.mkdirs() }
            val destFile = File(destDir, "${data.file.nameWithoutExtension}.${data.file.extension}")

            try {
                when (data.fileType) {
                    FileType.Image -> data.file.inputStream().use { input ->
                        destFile.outputStream().use { input.copyTo(it) }
                    }
                    FileType.File -> decompressTextFile(data.file, destFile)
                }

                val chatsRepo = DataManager.getChatsRepo(context)
                val messageItem = MessageItem(
                    senderID = userId,
                    text = text,
                    epochTimeMs = System.currentTimeMillis(),
                    chatId = chatID,
                    isImage = data.fileType == FileType.Image,
                    isFile = data.fileType == FileType.File,
                    fileLocation = destFile.absolutePath
                )
                val chatItem = chatsRepo.getChatByBittelID(chatID)
                chatItem?.message = Message(senderID = userId, text = text, seen = false)
                DataManager.getAppRepo(context).saveMessage(context, messageItem)
                chatItem?.let { chatsRepo.addChat(it) }

            } catch (e: Exception) {
                Timber.e(e, "Error saving file locally: ${data.file.name}")
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

    private fun createStartPackage(totalPackages: Int, spareData: Int) {
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
        val reed = ReedSolomon(totalDataPackets = packages.size, totalParityPackets = spare)
        val dataList = packages.map { it.value.data }.toMutableList()

        var paddingAdded = 0
        if (dataList.isNotEmpty()) {
            val lastIndex = dataList.lastIndex
            val originalSize = dataList[lastIndex].size
            if (originalSize < FILE_CHUNK_SIZE) {
                paddingAdded = FILE_CHUNK_SIZE - originalSize
                dataList[lastIndex] = dataList[lastIndex].copyOf(FILE_CHUNK_SIZE)
            }
        }

        val newArray = reed.encode(dataList)
        val bittelFileList = newArray.withIndex().associate { (index, data) ->
            index.toFloat() to StardustFilePackage(
                current = index,
                data = data,
                isLast = index == newArray.lastIndex
            )
        }

        return bittelFileList to paddingAdded
    }

    private fun createPackages(files: List<File>): Map<Float, StardustFilePackage> {
        var packageIndex = 0
        val bittelFileList = mutableMapOf<Float, StardustFilePackage>()

        for ((fileIndex, file) in files.withIndex()) {
            val fileBytes = file.readBytes()
            Timber.tag("FileUpload").d("fileBytes: ${fileBytes.size}")

            var offset = 0
            while (offset < fileBytes.size) {
                val chunk = fileBytes.copyOfRange(offset, minOf(offset + FILE_CHUNK_SIZE, fileBytes.size))
                val isLast = (offset + FILE_CHUNK_SIZE >= fileBytes.size) && (fileIndex == files.lastIndex)

                bittelFileList[packageIndex.toFloat()] = StardustFilePackage(
                    current = packageIndex,
                    data = chunk,
                    isLast = isLast
                )
                Timber.tag("FileUpload").d("chunk: ${chunk.size}, isLast: $isLast")

                offset += FILE_CHUNK_SIZE
                packageIndex++
            }
        }

        Timber.tag("FileUpload").d("Total packages created: ${bittelFileList.size}")
        return bittelFileList
    }

    private fun sendPackage(stardustFilePackage: StardustFilePackage) {
        DataManager.getClientConnection(context).let {
            val functionalityType = when (data.fileType) {
                FileType.File -> FunctionalityType.FILE
                else -> FunctionalityType.IMAGE
            }
            val radio = getRadioToSend(
                functionalityType = functionalityType,
                carrier = data.stardustAPIPackage.carrier
            ) ?: return
            sendInterval = if (radio.first.type == StardustConfigurationParser.StardustTypeFunctionality.ST) 300L else 900L
            val fileStartMessage = StardustPackageUtils.getStardustPackage(
                context = context,
                source = data.stardustAPIPackage.source,
                destenation = data.stardustAPIPackage.destination,
                stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                data = stardustFilePackage.toArrayInt()
            )
            fileStartMessage.stardustControlByte.stardustDeliveryType = radio.second
            if (stardustFilePackage.isLast) {
                fileStartMessage.stardustControlByte.stardustPartType = StardustControlByte.StardustPartType.LAST
            }
            packagesSent++
            Timber.tag("FileUpload").d("send: $packagesSent")
            it.addMessageToQueue(fileStartMessage)
        }
    }

    private fun resetSendTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, sendInterval)
    }

    private fun removeSendTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
    }

    private fun finishSending() {
        isSendingInProgress = false
        sendingPercentage = 0
        removeSendTimer()
        current = 0f
        packagesSent = 0
        isComplete = true
        // Reuse existing handler — do NOT create a new Handler instance here
        handler.postDelayed({ isComplete = false }, 3000)
        onFileStatusChange?.finishSending(data)
        sendInterval = 900
    }

    interface OnFileStatusChange {
        fun startSending(data: FileUtils.FileTransferData.Send) {}
        fun finishSending(data: FileUtils.FileTransferData.Send) {}
        fun stopSending(data: FileUtils.FileTransferData.Send) {}
        fun updateStep(data: FileUtils.FileTransferData.Send, percentage: Int) {}
    }

    companion object {

        private const val FILE_CHUNK_SIZE = 60

        fun calculateNumOfPackages(files: List<File>, spare: Int): Int {
            return files.sumOf { ceil(it.length().toDouble() / FILE_CHUNK_SIZE).toInt() } + spare
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
            var toAdd = ceil(packageNum * (percent / 100)).toInt()

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
