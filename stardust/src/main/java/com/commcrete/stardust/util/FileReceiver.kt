package com.commcrete.stardust.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.FileUtils.decompressTextFile
import com.commcrete.stardust.util.FileUtils.trimUntilUnderscore
import com.commcrete.stardust.util.UsersUtils.mRegisterUser
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class FileReceiver(
    val context: Context,
    val firstPackage: StardustFileStartPackage,
    val stardustPackage: StardustPackage,
    val onLastPackageReceived: (key: String) -> Unit) {

    private val textLogger = TextLogger(context)
    val dataList: MutableList<StardustFilePackage> = mutableListOf()
    var isReceivingInProgress: Boolean = false
    var receivingPercentage: Int = 0
    val lostPackagesIndex: MutableSet<Int> = mutableSetOf()

    var data = FileUtils.FileTransferData.Receive(
        id = getUniqueKey(stardustPackage),
        senderID = getRealSenderID(sourceID = stardustPackage.getSourceAsString(), destinationID = stardustPackage.getDestAsString()),
        chatID = stardustPackage.getSourceAsString(),
        fileName = firstPackage.fileName,
        fileEnding = firstPackage.fileEnding,
        fileType = firstPackage.fileType,
        deliveryChannel = stardustPackage.stardustControlByte.stardustDeliveryType,
        numOfPackages = firstPackage.total,
    )
    
    init {
        // Load chat names asynchronously in the background (non-blocking)
        Scopes.getDefaultCoroutine().launch {
            try {
                val chatsRepo = DataManager.getChatsRepo(context)
                val loadedChatName = chatsRepo.getChatName(data.chatID) ?: data.chatID
                val loadedSenderName = if(data.chatID != data.realSenderName) {
                    chatsRepo.getChatName(data.senderID) ?: data.senderID
                } else loadedChatName
                
                // Switch to main thread only for quick data update
                Scopes.getMainCoroutine().launch {
                    data = data.copy(
                        chatName = loadedChatName,
                        realSenderName = loadedSenderName
                    )
                }
            } catch (e: Exception) {
                Log.e("FileReceiver", "Error loading chat names", e)
                // Names remain as IDs if loading fails
            }
        }
    }

    private val sendInterval : Long = 1800
    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = Runnable {
//            val totalPackages = dataStart?.total
//            val missing = lostPackagesIndex.count()
        //val text = "t:$totalPackages, m:$missing"
        //textLogger.logText(text)
        if(checkIfHaveEnough()) {
            saveFile()
            Scopes.getMainCoroutine().launch {
                isReceivingInProgress = false
                DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = 100)
            }
        }
        Scopes.getMainCoroutine().launch {
            DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = 0)
        }
        dataList.clear()
    }

    fun addDataPackage(filePackage: StardustFilePackage) {
        dataList.add(filePackage)
        Log.d("FileReceivedUtils", "dataList.put : ${filePackage.current}")
        updateProgress()
        resetReceiveTimer()
    }

    fun resetReceiveTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        val timesDelay = calculateDelay()
        Log.d("FileReceivedUtils","timesDelay : $timesDelay" )
        handler.postDelayed(
            runnable,
            sendInterval * timesDelay
        )
    }

    fun removeReceiveTimer() {
        try {
            onLastPackageReceived.invoke(data.id)
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        } catch (e : Exception) {
            e.printStackTrace()
        }
    }

    fun updateProgress () {
        checkMissingPackages()
        Scopes.getMainCoroutine().launch {
            receivingPercentage = ((dataList.size.toDouble() / firstPackage.total) * 100).toInt()
            DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = receivingPercentage)
            Log.d("FileReceivedUtils","timesDelay : $receivingPercentage" )
        }
        checkData()
    }

    private fun checkData() {
        if(lostPackagesIndex.size > (firstPackage.total - firstPackage.spare) ) {
            updateFailure(FileFailure.MISSING)
        }
        if(checkIfMissingMain()) {
            saveFile()
            Scopes.getMainCoroutine().launch {
                isReceivingInProgress = false
                DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = 0)
            }
        } else if(firstPackage.total == dataList.last().current + 1) {
            saveFile()
            Scopes.getMainCoroutine().launch {
                isReceivingInProgress = false
                DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = 100)
            }
        }
    }

    private fun calculateDelay(): Int {
        val spareDelay = firstPackage?.spare?.let {
            it - lostPackagesIndex.size
        }

        val totalDelay = firstPackage?.total?.let {
            it - dataList.last().current + 1
        }

        return maxOf(1, listOfNotNull(spareDelay, totalDelay).minOrNull() ?: 0)
    }

    private fun updateFailure (failure: FileFailure) {
        val totalPackages = firstPackage?.total
        val missing = lostPackagesIndex.count()
//            val text = "t:$totalPackages, m:$missing"
//            textLogger.logText(text)
        DataManager.getCallbacks()?.receiveFailure(data = data, failure = failure)
    }

    private fun saveFile () {
        removeReceiveTimer()
//        val totalPackages = firstPackage?.total
//        val missing = lostPackagesIndex.count()
//            val text = "t:$totalPackages, m:$missing"
//            textLogger.logText(text)
// Get the destination directory
        val destDir = File("${context.filesDir}/${data.chatID}/files")
// Ensure the directory exists
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val name = data.fileName
        val ending = data.fileEnding
        val type = if(data.fileType == FileUtils.FileType.File) ".$ending" else ".jpg"
// Create the target file with a timestamp
        val ts = System.currentTimeMillis()
        val completeFileName = "$ts"+ "_"+"$name$type"
        val targetFile = File(destDir, "$completeFileName")
        try {

            if(data.fileType == FileUtils.FileType.File) {
                // Step 1: Create a temporary file for the concatenated data
                val tempOutputFile = File.createTempFile("output_temp", null)
                // Write concatenated data to the temporary file

                //After i get all the packages.
                FileOutputStream(tempOutputFile).use { outputStream ->
                    writeDataToFile(outputStream)
                }
                // Step 2: Decompress the temporary file into the target file
                decompressTextFile(tempOutputFile, targetFile)
                // Clean up: Delete the temporary file
                tempOutputFile.delete()
            } else {
                FileOutputStream(targetFile).use { outputStream ->
                    writeDataToFile(outputStream)
                }
            }
            println("File saved successfully at: ${targetFile.absolutePath}")
            saveToMessages(targetFile)
            if(data.fileType == FileUtils.FileType.File) {
                DataManager.getCallbacks()?.receiveFile(data = data, file = targetFile)
            } else {
                DataManager.getCallbacks()?.receiveImage(data = data, file = targetFile)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            println("Error saving file: ${e.message}")
        }
    }

    private fun writeDataToFile(outputStream: FileOutputStream) {
        val sortedList = dataList.sortedBy { it.current }
        if(firstPackage?.spare == 0 || lostPackagesIndex.isEmpty()) {
            for (packageData in sortedList) {
                outputStream.write(packageData.data)
            }
        } else {
            val total = firstPackage?.total ?: 0
            val parityPackets = firstPackage?.spare ?: 0
            val receivedWithNulls: List<Packet?> = (0 until total).map { index ->
                if (lostPackagesIndex.contains(index)) {
                    null
                } else {
                    // Find the packet with current == index
                    sortedList.find { it.current == index }?.data
                }
            }
            val reedSolomonAuto = ReedSolomon (totalDataPackets = total - parityPackets , totalParityPackets = parityPackets)
            val decodedReed = reedSolomonAuto.decode(receivedPackets = receivedWithNulls,
                missingIndices = lostPackagesIndex.toIntArray() ).toMutableList()

//                val ldpc = LDPCCode(maxPackets = total - parityPackets,parityPackets = parityPackets)
//                val decoded = ldpc.decode(
//                    received = receivedWithNulls,
//                    lostIndices = lostPackagesIndex.toList()
//                ).toMutableList()

//                for (packageData in decoded) {
//                    Log.d("decoded" , "decoded 2: ${packageData.toHexString()}")
//                }
            firstPackage?.let {
                val spare = it.spareData
                val lastIndex = decodedReed.lastIndex
                decodedReed[lastIndex] = decodedReed[lastIndex].copyOfRange(0, decodedReed[lastIndex].size - spare)
            }

            for (packageData in decodedReed) {
//                    Log.d("decoded" , "decoded 2: ${packageData.toHexString()}")
                outputStream.write(packageData)
            }
        }
    }

    private fun checkMissingPackages() {
        if (dataList.isEmpty()) return

        // Step 1: Get all present indices
        val presentIndices = dataList.map { it.current }.toSet()

        // Step 2: Determine the max current index (assuming linear and sequential from 0)
        val maxIndex = dataList.maxOf { it.current }

        // Step 3: Compare expected vs present
        for (i in 0..maxIndex) {
            if (i !in presentIndices) {
                lostPackagesIndex.add(i)
            }
        }

        // Optional: Print or log the result
        println("Missing packages: $lostPackagesIndex")
        Log.d("checkData","Missing packages: $lostPackagesIndex" )
    }

    private fun checkIfMissingMain(): Boolean {
        val mainCount = firstPackage?.total ?: return false
        val spare = firstPackage?.spare ?: 0

        // Check if any missing package index is in the main range
        return ((mainCount - spare) == dataList.size ) && lostPackagesIndex.isEmpty()
    }

    private fun checkIfHaveEnough(): Boolean {
        val mainCount = firstPackage?.total ?: return false
        val spare = firstPackage?.spare ?: 0
        return (dataList.size  >= (mainCount - spare))
    }

    private fun saveToMessages (file: File) {
        Scopes.getDefaultCoroutine().launch {
            val mFileName = trimUntilUnderscore(file.name)
            val type = (if(data.fileType == FileUtils.FileType.File) "File Received" else "Image Received") + ": $mFileName"
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
            var chatItem = chatsRepo.getChatByBittelID(data.chatID)
            if(chatItem == null) {
                chatItem = UsersUtils.createNewBittelUserSender(chatsRepo, stardustPackage)
            }
            chatItem.let { chat ->
                val chatContact = chat.user
                chatContact?.let { contact ->
                    contact.appId?.let { appIdArray ->
                        val displayName = chatsRepo.getChatByBittelID(data.senderID)?.name ?: data.senderID

                        if(appIdArray.isNotEmpty()) {
                            chat.message = Message(senderID = data.senderID, text = type, seen = true)
                            chatsRepo.addChat(chat)
                            val messageItem = MessageItem(
                                senderID = data.senderID,
                                epochTimeMs = System.currentTimeMillis(),
                                senderName = displayName,
                                chatId = data.chatID,
                                text = type,
                                fileLocation = file.absolutePath,
                                isFile = data.fileType == FileUtils.FileType.File, isImage = data.fileType == FileUtils.FileType.Image)
                            DataManager.getMessagesRepo(context).saveMessage( context = context, messageItem )
                            UsersUtils.saveMessageToDatabase(context, appIdArray[0], messageItem)
                            val numOfUnread = chat.numOfUnseenMessages
                            chatsRepo.updateNumOfUnseenMessages(data.chatID, numOfUnread + 1)
                            Scopes.getMainCoroutine().launch {
                                UsersUtils.messageReceived.value = messageItem
                                PlayerUtils.playNotificationSound (context)
                            }
                        }
                    }
                }
            }
        }
    }

    fun getRealSenderID(sourceID: String, destinationID: String): String {
        val userAppId = mRegisterUser?.appId
        return if(GroupsUtils.isGroup(sourceID) && userAppId != null && (!destinationID.equals( userAppId, ignoreCase = true))) destinationID else sourceID
    }

    enum class FileFailure {
        MISSING,
        ERROR
    }

    companion object {
        fun getUniqueKey(filePackage: StardustPackage): String {
            return "${filePackage.getSourceAsString()}_${filePackage.getDestAsString()}_${filePackage.stardustControlByte.stardustDeliveryType}".hashCode().toString()
        }
    }
}
fun Packet.toHexString(): String =
    joinToString(" ") { "%02X".format(it) }
