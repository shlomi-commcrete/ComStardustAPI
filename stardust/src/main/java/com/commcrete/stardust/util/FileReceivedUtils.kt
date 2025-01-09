package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.bittell.util.bittel_package.model.StardustFileStartPackage
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

    val isReceivingInProgress : MutableLiveData<Boolean> = MutableLiveData(false)
    val receivingPercentage : MutableLiveData<Int> = MutableLiveData(0)

    val dataList :MutableMap<Int,StardustFilePackage> = mutableMapOf()
    var dataStart : StardustFileStartPackage? = null
    private val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())
    private val sendInterval : Long = 3000
    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = Runnable {
        dataStart = null
        dataList.clear()
        Scopes.getMainCoroutine().launch {
            isReceivingInProgress.value = false
        }
    }
    fun getInitFile (bittelFileStartPackage: StardustFileStartPackage, bittelPackage: StardustPackage) {
        dataStart = bittelFileStartPackage
        Scopes.getMainCoroutine().launch {
            isReceivingInProgress.value = true
        }
    }

    fun getFile (bittelFilePackage: StardustFilePackage, bittelPackage: StardustPackage) {
        dataList.put(bittelFilePackage.current, bittelFilePackage)
        resetReceiveTimer()
        if(dataStart != null ) {
            Scopes.getMainCoroutine().launch {
                receivingPercentage.value = ((dataList.size.toDouble().div(dataStart!!.total)).times(100)).toInt()
            }
            if(dataStart!!.total == dataList.size) {
                saveFile(bittelPackage, dataStart?.type)
                Scopes.getMainCoroutine().launch {
                    isReceivingInProgress.value = false
                }
            }
        }
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
                    for (key in dataList.keys.sorted()) { // Ensure the data is written in order
                        val data = dataList[key]?.data
                        if (data != null) {
                            outputStream.write(data) // Write raw bytes to the file
                        }
                    }
                }
                // Step 2: Decompress the temporary file into the target file
                FileSendUtils.decompressTextFile(tempOutputFile, targetFile)
                // Clean up: Delete the temporary file
                tempOutputFile.delete()

            } else {
                FileOutputStream(targetFile).use { outputStream ->
                    for (key in dataList.keys.sorted()) { // Ensure the data is written in order
                        val data = dataList[key]?.data
                        if (data != null) {
                            outputStream.write(data) // Write raw bytes to the file
                        }
                    }
                }
            }

            println("File saved successfully at: ${targetFile.absolutePath}")
            saveToMessages(bittelPackage, targetFile, fileType)
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

    private fun resetReceiveTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, sendInterval)
    }

    private fun removeReceiveTimer() {
        try {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    private fun handleAck (bittelPackage: StardustPackage) {

    }
}