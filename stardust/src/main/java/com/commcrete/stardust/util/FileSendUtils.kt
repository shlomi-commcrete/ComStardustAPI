package com.commcrete.stardust.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.MutableLiveData
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartPackage
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.StardustPackageParser
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
    private val sendInterval : Long = 1300

    private var packagesSent = 0
    private var dest : String? = null
    private var onFileStatusChange : OnFileStatusChange? = null

    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = Runnable {
        val mPackage = mutablePackagesMap[current.value]
        if(mPackage != null){
            sendPackage(mPackage, dest)
        }
        current.value = current.value?.plus(1)
        updateStep(mutablePackagesMap.size, )
        resetSendTimer()
    }

    fun sendFile (stardustAPIPackage: StardustAPIPackage, file: File, fileStartParser: StardustFileStartParser.FileTypeEnum, onFileStatusChange: OnFileStatusChange) {
        this.onFileStatusChange = onFileStatusChange
        isSendingInProgress.value = true
        val fileList = listOf(file)
        val numOfPackages = calculateNumOfPackages(fileList)
        this.onFileStatusChange?.startSending()
        dest = stardustAPIPackage.destination
        createStartPackage(fileStartParser,
            numOfPackages, dest)
        mutablePackagesMap.clear()
        mutablePackagesMap.putAll(createPackages(fileList))
        resetSendTimer()
        saveLocalMessages(
            stardustAPIPackage.destination,stardustAPIPackage.source, fileStartParser,
            fileLocation = fileList[0].absolutePath)
    }

    private fun updateStep (numOfPackages: Int) {
        sendingPercentage.value = ((current.value?.toDouble()?.div(numOfPackages))?.times(100))?.toInt()
        sendingPercentage.value?.let { this.onFileStatusChange?.updateStep(it) }
        if(sendingPercentage.value == 100) {
            finishSending()
        }
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
        dest: String?
    ){
        if(dest == null) {
            return
        }
        val fileStart =  StardustFileStartPackage(type = type.type, total = totalPackages)
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
                it.addMessageToQueue(fileStartMessage)
            }
        }
    }

    private fun createPackages (files: List<File>) : Map<Float, StardustFilePackage> {
        var packageIndex = 0
        var packageData = 0
        val chunkSize = 135
        val bittelFileList = mutableMapOf<Float, StardustFilePackage>()
        for (file in files) {
            val fileBytes = file.readBytes() // Read the file as a byte array
            Timber.tag("FileUpload").d("fileBytes : ${fileBytes.size}")
            var offset = 0
            while (offset < fileBytes.size) {
                val end = minOf(offset + chunkSize, fileBytes.size) // Calculate end of the chunk
                val chunk = fileBytes.copyOfRange(offset, end) // Create the chunk
                Timber.tag("FileUpload").d("chunk : ${chunk.size}")
                packageData = packageData.plus(chunk.size)
                bittelFileList.put(packageIndex.toFloat(), StardustFilePackage(current = packageIndex, data = chunk))
                offset += chunkSize // Move to the next chunk
                packageIndex++
            }
        }
        Timber.tag("FileUpload").d("chunkSize : $packageData")
        Timber.tag("FileUpload").d("bittelFileList : ${bittelFileList.size}}")
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
            SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let { appId ->
                val fileStartMessage = StardustPackageUtils.getStardustPackage(
                    source = appId , destenation = dest, stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                    data = stardustFilePackage.toArrayInt())
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

    private fun calculateNumOfPackages(files : List<File>) : Int {
        var length = 0
        val chunkSize = 135
        for (file in files) {
            length = length + (file.length().div(chunkSize)).toInt()
            if(file.length().mod(chunkSize) != 0) {
                length ++
            }
        }
        return length
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
    }

    interface OnFileStatusChange {
        fun startSending () {}
        fun finishSending () {}
        fun stopSending () {}
        fun updateStep (percentage : Int) {}
    }
}