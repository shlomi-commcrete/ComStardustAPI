package com.commcrete.stardust.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.UsersUtils.mRegisterUser
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object FileReceivedUtils {

    private val fileReceivedDataList : MutableList<FileReceivedData> = mutableListOf()
    private val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())
    private val textLogger = TextLogger(DataManager.context)
    private fun getInit (bittelFileStartPackage: StardustFileStartPackage, bittelPackage: StardustPackage) {
        var haveFileStart = false
        for (fileStart in fileReceivedDataList) {
            if(fileStart.dataStart != null && fileStart.dataStart == bittelFileStartPackage) {
                haveFileStart = true
            }
        }
        if(!haveFileStart) {
            fileReceivedDataList.add(FileReceivedData(index = bittelPackage.stardustControlByte.stardustDeliveryType.value, dataStart = bittelFileStartPackage, isReceivingInProgress = true, bittelPackage = bittelPackage,
                receivingPercentage = 0))
        }
    }

    private fun getData (context: Context, bittelFilePackage: StardustFilePackage, bittelPackage: StardustPackage) {

        for (fileStart in fileReceivedDataList) {
            if(fileStart.bittelPackage != null &&
                fileStart.bittelPackage.getSourceAsString() == bittelPackage.getSourceAsString() &&
                fileStart.bittelPackage.getDestAsString() == bittelPackage.getDestAsString() &&
                fileStart.bittelPackage.stardustControlByte.stardustDeliveryType ==
                bittelPackage.stardustControlByte.stardustDeliveryType) {
                fileStart.dataList.add(bittelFilePackage)
                Log.d("FileReceivedUtils", "dataList.put : ${bittelFilePackage.current}")
                fileStart.updateProgress (context)
                fileStart.resetReceiveTimer()
            }
        }
    }

    fun getInitFile (bittelFileStartPackage: StardustFileStartPackage, bittelPackage: StardustPackage) {
        getInit(bittelFileStartPackage, bittelPackage)
    }

    fun getFile (context: Context, bittelFilePackage: StardustFilePackage, bittelPackage: StardustPackage) {
        getData(context, bittelFilePackage, bittelPackage)
    }
    private fun saveToMessages (context: Context, bittelPackage: StardustPackage, file: File, fileType: Int?, fileName : String) {
        Scopes.getDefaultCoroutine().launch {
            val mFileName = trimUntilUnderscore(fileName)
            val type = (if(fileType == 0) "File Received" else "Image Received") + ": $mFileName"
            val isFile = (fileType == 0)
            val isImage = (fileType == 1)
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
            var chatItem = chatsRepo.getChatByBittelID(bittelPackage.getSourceAsString())
            if(chatItem == null) {
                chatItem = UsersUtils.createNewBittelUserSender(chatsRepo, bittelPackage)
            }
            chatItem.let { chat ->
                val chatContact = chat.user
                chatContact?.let { contact ->
                    contact.appId?.let { appIdArray ->
                        var whoSent = ""
                        var displayName = contact.displayName

                        val srcID = bittelPackage.getSourceAsString()

                        if(GroupsUtils.isGroup(srcID) && (bittelPackage.getDestAsString() != mRegisterUser.value?.appId)){
                            whoSent = bittelPackage.getDestAsString()
                            chatsRepo.getChatByBittelID(whoSent)?.let {
                                displayName = it.name
                            }
                        } else {
                            whoSent = bittelPackage.getSourceAsString()
                        }

                        if(appIdArray.isNotEmpty()) {
                            chat.message = Message(senderID = whoSent, text = type,
                                seen = true)
                            chatsRepo.addChat(chat)
                            val messageItem = MessageItem(senderID = whoSent,
                                epochTimeMs = System.currentTimeMillis(), senderName = displayName ,
                                chatId = bittelPackage.getSourceAsString(), text = type, fileLocation = file.absolutePath,
                                isFile = isFile, isImage = isImage)
                            messagesRepository.saveFileMessage( messageItem )
                            UsersUtils.saveMessageToDatabase(context, appIdArray[0], messageItem)
                            val numOfUnread = chat.numOfUnseenMessages
                            chatsRepo.updateNumOfUnseenMessages(bittelPackage.getSourceAsString(), numOfUnread+1)
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

    data class FileReceivedData (
        val index : Int = 0,
        val dataList: MutableList<StardustFilePackage> = mutableListOf(),
        var dataStart : StardustFileStartPackage? = null,
        var isReceivingInProgress : Boolean = false,
        var receivingPercentage : Int = 0,
        val bittelPackage: StardustPackage? = null,
        val lostPackagesIndex : MutableSet<Int> = mutableSetOf()
    ) {
        private val sendInterval : Long = 1800
        private val handler : Handler = Handler(Looper.getMainLooper())
        private val runnable : Runnable = Runnable {
            val totalPackages = dataStart?.total
            val missing = lostPackagesIndex.count()
            val text = "t:$totalPackages, m:$missing"
            //textLogger.logText(text)
            if(checkIfHaveEnough()) {
                bittelPackage?.let { saveFile(DataManager.context, it, dataStart?.type) }
                Scopes.getMainCoroutine().launch {
                    isReceivingInProgress = false
                    DataManager.getCallbacks()?.receiveFileStatus(index, 0, bittelPackage?.getSourceAsString() ?: "", bittelPackage?.getDestAsString() ?: "", dataStart?.fileName ?: "", dataStart?.fileEnding ?: "", dataStart?.fileType ?: FileUtils.FileType.UNKNOWN)
                }
                handler.postDelayed( {removeFromFileReceivedList()}, 300)
            } else {

            }
            Scopes.getMainCoroutine().launch {
                DataManager.getCallbacks()?.receiveFileStatus(index, 0, bittelPackage?.getSourceAsString() ?: "", bittelPackage?.getDestAsString() ?: "", dataStart?.fileName ?: "", dataStart?.fileEnding ?: "", dataStart?.fileType ?: FileUtils.FileType.UNKNOWN)
            }
            removeFromFileReceivedList()
            dataStart = null
            dataList.clear()
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
                handler.removeCallbacks(runnable)
                handler.removeCallbacksAndMessages(null)
            }catch (e : Exception) {
                e.printStackTrace()
            }
        }

        fun updateProgress (context: Context) {
            checkMissingPackages()
            if(dataStart != null ) {
                Scopes.getMainCoroutine().launch {
                    dataStart?.let {
                        receivingPercentage = ((dataList.size.toDouble() / it.total) * 100).toInt()
                        DataManager.getCallbacks()?.receiveFileStatus(index, receivingPercentage, bittelPackage?.getSourceAsString() ?: "", bittelPackage?.getDestAsString() ?: "", dataStart?.fileName ?: "", dataStart?.fileEnding ?: "", dataStart?.fileType ?: FileUtils.FileType.UNKNOWN)
                        Log.d("FileReceivedUtils","timesDelay : $receivingPercentage" )
                    }
                }
                checkData(context)
            }
        }

        private fun checkData (context: Context) {
            dataStart?.let {
                if(lostPackagesIndex.size > (it.total-it.spare) ) {
                    updateFailure(FileFailure.MISSING)
                }
                if(checkIfMissingMain()) {
                    bittelPackage?.let { bittelPackage -> saveFile(context, bittelPackage, dataStart?.type) }
                    Scopes.getMainCoroutine().launch {
                        isReceivingInProgress = false
                        DataManager.getCallbacks()?.receiveFileStatus(index, 0, bittelPackage?.getSourceAsString() ?: "", bittelPackage?.getDestAsString() ?: "", dataStart?.fileName ?: "", dataStart?.fileEnding ?: "", dataStart?.fileType ?: FileUtils.FileType.UNKNOWN)
                    }
                    handler.postDelayed( {removeFromFileReceivedList()}, 300)
                } else if(it.total == dataList.last().current + 1) {
                        bittelPackage?.let { bittelPackage ->saveFile(context, bittelPackage, dataStart?.type) }
                        Scopes.getMainCoroutine().launch {
                            isReceivingInProgress = false
                            DataManager.getCallbacks()?.receiveFileStatus(index, 100, bittelPackage?.getSourceAsString() ?: "", bittelPackage?.getDestAsString() ?: "", dataStart?.fileName ?: "", dataStart?.fileEnding ?: "", dataStart?.fileType ?: FileUtils.FileType.UNKNOWN)
                        }
                        handler.postDelayed( {removeFromFileReceivedList()}, 300)
                    }
                else {}
            }
        }
        private fun calculateDelay(): Int {
            val spareDelay = dataStart?.spare?.let {
                it - lostPackagesIndex.size
            }

            val totalDelay = dataStart?.total?.let {
                it - dataList.last().current + 1
            }

            return maxOf(1, listOfNotNull(spareDelay, totalDelay).minOrNull() ?: 0)
        }

        private fun updateFailure (failure: FileFailure) {
            val totalPackages = dataStart?.total
            val missing = lostPackagesIndex.count()
            val text = "t:$totalPackages, m:$missing"
            textLogger.logText(text)
            DataManager.getCallbacks()?.receiveFailure(failure, dataStart)
        }

        private fun saveFile (context: Context, bittelPackage: StardustPackage, fileType: Int?) {
            val totalPackages = dataStart?.total
            val missing = lostPackagesIndex.count()
            val text = "t:$totalPackages, m:$missing"
            textLogger.logText(text)
// Get the destination directory
            val destDir = File("${context.filesDir}/${bittelPackage.getSourceAsString()}/files")
// Ensure the directory exists
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            val name = dataStart?.fileName
            val ending = dataStart?.fileEnding
            val type = if(fileType == 0) ".$ending" else ".jpg"
// Create the target file with a timestamp
            val ts = System.currentTimeMillis()
            val completeFileName = "$ts"+ "_"+"$name$type"
            val targetFile = File(destDir, "$completeFileName")
            try {

                if(fileType == 0) {
                    // Step 1: Create a temporary file for the concatenated data
                    val tempOutputFile = File.createTempFile("output_temp", null)
// Write concatenated data to the temporary file

//After i get all the packages.
                    FileOutputStream(tempOutputFile).use { outputStream ->
                        writeDataToFile(outputStream)
                    }
// Step 2: Decompress the temporary file into the target file
                    FileSendUtils.decompressTextFile(tempOutputFile, targetFile)
// Clean up: Delete the temporary file
                    tempOutputFile.delete()
                } else {
                    FileOutputStream(targetFile).use { outputStream ->
                        writeDataToFile(outputStream)
                    }
                }
                println("File saved successfully at: ${targetFile.absolutePath}")
                saveToMessages(context, bittelPackage, targetFile, fileType, completeFileName)
                val packageData = StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString())
                if(fileType == 0) {
                    DataManager.getCallbacks()?.receiveFile(packageData, targetFile)
                } else {
                    DataManager.getCallbacks()?.receiveImage(packageData, targetFile)
                }
                dataStart = null
                dataList.clear()
                removeReceiveTimer()

            } catch (e: Exception) {
                e.printStackTrace()
                println("Error saving file: ${e.message}")
                dataStart = null
                dataList.clear()
            }
        }

        private fun writeDataToFile(outputStream: FileOutputStream) {
            val sortedList = dataList.sortedBy { it.current }
            if(dataStart?.spare == 0 || lostPackagesIndex.isEmpty()) {
                for (packageData in sortedList) {
                    outputStream.write(packageData.data)
                }
            } else {
                val total = dataStart?.total ?: 0
                val parityPackets = dataStart?.spare ?: 0
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
                dataStart?.let {
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
            val mainCount = dataStart?.total ?: return false
            val spare = dataStart?.spare ?: 0

            // Check if any missing package index is in the main range
            return ((mainCount - spare) == dataList.size ) && lostPackagesIndex.isEmpty()
        }

        private fun checkIfHaveEnough(): Boolean {
            val mainCount = dataStart?.total ?: return false
            val spare = dataStart?.spare ?: 0
            return (dataList.size  >= (mainCount - spare))
        }


        private fun handleAck (bittelPackage: StardustPackage) {

        }

        private fun removeFromFileReceivedList() {
            try {
                fileReceivedDataList.remove(this)
//                Log.d("FileReceivedUtils", "Removed object from fileReceivedDataList")
            }catch (e : Exception) {
                e.printStackTrace()
            }
        }

        enum class FileFailure {
            MISSING,
            ERROR
        }
    }
}

fun trimUntilUnderscore(input: String): String {
    return input.substringAfter("_")
}

fun Packet.toHexString(): String =
    joinToString(" ") { "%02X".format(it) }
