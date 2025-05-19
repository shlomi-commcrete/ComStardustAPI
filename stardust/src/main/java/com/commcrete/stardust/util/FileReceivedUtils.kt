package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartPackage
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.model.StardustPackage
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object FileReceivedUtils {

    private val fileReceivedDataList : MutableList<FileReceivedData> = mutableListOf()
    private val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())

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

    private fun getData (bittelFilePackage: StardustFilePackage, bittelPackage: StardustPackage) {

        for (fileStart in fileReceivedDataList) {
            if(fileStart.bittelPackage != null &&
                fileStart.bittelPackage.getSourceAsString() == bittelPackage.getSourceAsString() &&
                fileStart.bittelPackage.getDestAsString() == bittelPackage.getDestAsString() &&
                fileStart.bittelPackage.stardustControlByte.stardustDeliveryType ==
                bittelPackage.stardustControlByte.stardustDeliveryType) {
                fileStart.dataList.add(bittelFilePackage)
//                Log.d("fileReceived", "dataList.put : ${bittelFilePackage.current}")
                fileStart.updateProgress ()
                fileStart.resetReceiveTimer()
            }
        }
    }

    fun getInitFile (bittelFileStartPackage: StardustFileStartPackage, bittelPackage: StardustPackage) {
        getInit(bittelFileStartPackage, bittelPackage)
    }

    fun getFile (bittelFilePackage: StardustFilePackage, bittelPackage: StardustPackage) {
        getData(bittelFilePackage, bittelPackage)
    }
    private fun saveToMessages (bittelPackage: StardustPackage, file: File, fileType: Int?) {
        Scopes.getDefaultCoroutine().launch {
            val type = if(fileType == 0) "File Received" else "Image Received"
            val isFile = (fileType == 0)
            val isImage = (fileType == 1)
            val userName = UsersUtils.getUserName(bittelPackage.getSourceAsString())
            val messageItem = MessageItem(senderID = bittelPackage.getSourceAsString(),
                epochTimeMs = System.currentTimeMillis(), senderName = userName ,
                chatId = bittelPackage.getSourceAsString(), text = type, fileLocation = file.absolutePath,
                isFile = isFile, isImage = isImage)
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
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
                        if(chat.isGroup){
                            whoSent = bittelPackage.getDestAsString()
                            val sender = chatsRepo.getChatByBittelID(whoSent)
                            sender?.let {
                                displayName = it.name
                            }
                        }else {
                            whoSent = appIdArray[0]
                        }
                        if(appIdArray.isNotEmpty()){
                            chat.message = Message(senderID = whoSent, text = type,
                                seen = true)
                            chatsRepo.addChat(chat)
                            messagesRepository.saveFileMessage( messageItem )
                            UsersUtils.saveMessageToDatabase(appIdArray[0], messageItem)
                            val numOfUnread = chat.numOfUnseenMessages
                            chatsRepo.updateNumOfUnseenMessages(bittelPackage.getSourceAsString(), numOfUnread+1)
                            Scopes.getMainCoroutine().launch {
                                UsersUtils.messageReceived.value = messageItem
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
        val lostPackagesIndex : MutableList<Int> = mutableListOf()
    ) {
        private val sendInterval : Long = 3000
        private val handler : Handler = Handler(Looper.getMainLooper())
        private val runnable : Runnable = Runnable {
            dataStart = null
            dataList.clear()
            Scopes.getMainCoroutine().launch {
                DataManager.getCallbacks()?.receiveFileStatus(index, 0)
            }
            removeFromFileReceivedList()
        }

        fun resetReceiveTimer() {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(
                runnable,
                sendInterval * calculateDelay()
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

        fun updateProgress () {
            if(dataStart != null ) {
//                Log.d("fileReceived", "index : $index")
//                Log.d("fileReceived", "data start total : ${dataStart?.total}")
//                Log.d("fileReceived", "dataList.size : ${dataList.size}")
//                Log.d("fileReceived", "data current : ${dataList.lastOrNull()?.current}")
                Scopes.getMainCoroutine().launch {
                    dataStart?.let {
                        receivingPercentage = ((dataList.size.toDouble() / it.total) * 100).toInt()
                        DataManager.getCallbacks()?.receiveFileStatus(index, receivingPercentage)
                    }
                }
                dataStart?.let {
                    if(it.total == dataList.size) {
                        bittelPackage?.let { saveFile(it, dataStart?.type) }
                        Scopes.getMainCoroutine().launch {
                            isReceivingInProgress = false
                            DataManager.getCallbacks()?.receiveFileStatus(index, 0)
                        }
                        handler.postDelayed( {removeFromFileReceivedList()}, 300)
                    }
                }
            }
        }

        private fun calculateDelay () : Int {
            dataStart?.spare?.let {
                return it - lostPackagesIndex.size
            }
            return 0
        }

        private fun saveFile (bittelPackage: StardustPackage, fileType: Int?) {
            val context = DataManager.context
            // Get the destination directory
            val destDir = File("${context.filesDir}/${bittelPackage.getSourceAsString()}/files")

            // Ensure the directory exists
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            val type = if(fileType == 0) ".txt" else ".jpg"
            // Create the target file with a timestamp
            val ts = System.currentTimeMillis()
            val targetFile = File(destDir, "$ts$type")

            try {
                // Step 1: Create a temporary file for the concatenated data
                val tempOutputFile = File.createTempFile("output_temp", null, context.cacheDir)

                // Write concatenated data to the temporary file

                if(fileType == 0) {
                    FileOutputStream(tempOutputFile).use { outputStream ->
                        for (packageData in dataList.sortedBy { it.current }) {
                            outputStream.write(packageData.data)
                        }
                    }
                    // Step 2: Decompress the temporary file into the target file
                    FileSendUtils.decompressTextFile(tempOutputFile, targetFile)
                    // Clean up: Delete the temporary file
                    tempOutputFile.delete()

                } else {
                    FileOutputStream(targetFile).use { outputStream ->
                        for (packageData in dataList.sortedBy { it.current }) {
                            outputStream.write(packageData.data)
                        }
                    }
                }

                println("File saved successfully at: ${targetFile.absolutePath}")
                saveToMessages(bittelPackage, targetFile, fileType)
                dataStart = null
                dataList.clear()
                removeReceiveTimer()
                if(fileType == 0) {
                    DataManager.getCallbacks()?.receiveFile(StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString(),), targetFile)
                } else {
                    DataManager.getCallbacks()?.receiveImage(StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString(),), targetFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("Error saving file: ${e.message}")
                dataStart = null
                dataList.clear()
            }
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
    }
}