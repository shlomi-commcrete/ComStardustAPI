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

    val dataList: MutableList<StardustFilePackage> = mutableListOf()
    var lastReportedProgress: Int = 0
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

    private val receivingInterval : Long = 1800
    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = Runnable {
        checkData()
    }

    fun addDataPackage(filePackage: StardustFilePackage) {
        dataList.add(filePackage)
        updateProgress()
        resetReceiveTimer()
    }

    fun resetReceiveTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        val timesDelay = calculateDelay()
        handler.postDelayed(
            runnable,
            receivingInterval * timesDelay
        )
    }

    fun removeReceiveTimer() {
        try {
            onLastPackageReceived.invoke(data.id)
        } catch (e: Exception) {
            Log.e("FileReceiver", "Error invoking callback", e)
        } finally {
            // Always clean up handler callbacks, even if callback fails
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }
    }

    fun updateProgress() {
        checkMissingPackages()
        Scopes.getMainCoroutine().launch {
            val newProgress = ((dataList.size.toDouble() / firstPackage.total) * 100).toInt()

            // Only update if progress has actually changed
            if (newProgress != lastReportedProgress) {
                lastReportedProgress = newProgress
                // Handle completion
                if (newProgress >= 100) {
                    removeReceiveTimer()
                    checkData()
                } else {
                    DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = newProgress)
                }
            }
        }
    }

    private fun checkData() {
        // Check if we have all main packages or reached the last package
        val isComplete = hasMainPackages() || 
                         (dataList.isNotEmpty() && firstPackage.total == dataList.last().current + 1)

        if (isComplete) {
            saveFile()
            notifyTransferComplete()
        }
        else {
            updateFailure(FileFailure.MISSING)
        }
    }

    private fun notifyTransferComplete() {
        Scopes.getMainCoroutine().launch {
            try {
                DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = 100)
            } catch (e: Exception) {
                Log.e("FileReceiver", "Error notifying completion", e)
            } finally {
                removeReceiveTimer()
                dataList.clear()
            }
        }
    }

    private fun calculateDelay(): Int {
        val spareDelay = firstPackage.spare - lostPackagesIndex.size

        val totalDelay = firstPackage.total - dataList.last().current + 1

        return maxOf(1, listOfNotNull(spareDelay, totalDelay).minOrNull() ?: 0)
    }

    private fun updateFailure(failure: FileFailure) {
        Scopes.getMainCoroutine().launch {
            try {
                DataManager.getCallbacks()?.receiveFailure(data = data, failure = failure)
            } catch (e: Exception) {
                Log.e("FileReceiver", "Error notifying failure", e)
            } finally {
                removeReceiveTimer()
            }
        }
    }

    private fun saveFile () {
        removeReceiveTimer()
        val destDir = File("${context.filesDir}/${data.chatID}/files")
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val name = data.fileName
        val ending = data.fileEnding
        val type = if(data.fileType == FileUtils.FileType.File) ".$ending" else ".jpg"
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
            Log.d("FileReceiver", "File saved successfully: ${targetFile.absolutePath}")
            saveToMessages(targetFile)
            when (data.fileType) {
                FileUtils.FileType.File -> DataManager.getCallbacks()?.receiveFile(data = data, file = targetFile)
                FileUtils.FileType.Image -> DataManager.getCallbacks()?.receiveImage(data = data, file = targetFile)
                else -> Log.w("FileReceiver", "Unknown file type: ${data.fileType}")
            }
        } catch (e: Exception) {
            Log.e("FileReceiver", "Error saving file: ${data.fileName}", e)
            updateFailure(FileFailure.ERROR)
        }
    }

    private fun writeDataToFile(outputStream: FileOutputStream) {
        val sortedList = dataList.sortedBy { it.current }
        if(firstPackage.spare == 0 || lostPackagesIndex.isEmpty()) {
            for (packageData in sortedList) {
                outputStream.write(packageData.data)
            }
        } else {
            val total = firstPackage.total
            val parityPackets = firstPackage.spare
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

            firstPackage.let {
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

        val presentIndices = dataList.map { it.current }.toSet()
        val maxIndex = dataList.maxOf { it.current }

        for (i in 0..maxIndex) {
            if (i !in presentIndices) {
                lostPackagesIndex.add(i)
            }
        }

        if (lostPackagesIndex.isNotEmpty()) {
            Log.d("FileReceiver", "Missing packages: $lostPackagesIndex")
        }
    }

    private fun hasMainPackages(): Boolean {
        val mainCount = firstPackage.total
        val spare = firstPackage.spare
        // Check if any missing package index is in the main range
        return ((mainCount - spare) == dataList.size) && lostPackagesIndex.isEmpty()
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
