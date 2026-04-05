package com.commcrete.stardust.util.audio

import android.content.Context
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.context
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.UsersUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File


abstract class PttReceiveSession {

    val messagesRepository: MessagesRepository
        get() = DataManager.getMessagesRepo(context)
    val chatsRepository: ChatsRepository
        get() = DataManager.getChatsRepo(context)
    var fileToWrite: File? = null

    val timestamp: Long = System.currentTimeMillis()


    // ── Byte array utilities ──────────────────────────────────────────────────────────────────────
    fun intArrayToByteArray(intArray: MutableList<Int>): ByteArray {
        val byteArray = ByteArray(intArray.size)
        for (i in intArray.indices) {
            byteArray[i] = intArray[i].toByte()
        }
        return byteArray
    }

    fun createPttPcmFile(
        context: Context,
        source: String
    ): File? {
        val dir = File(context.filesDir, source)
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.tag("PttReceiveSession").e("Failed to create directory: ${dir.absolutePath}")
            return null
        }
        val file = fileToWrite ?: File(dir, "$timestamp-$source.pcm")
        if (!file.exists()) file.createNewFile()
        return file
    }


    private fun savePttMessage(
        context: Context,
        packageToPass: StardustAPIPackage,
        file: File,
        ts: String,
        audioTypeId: Int? = null
    ) {
        Scopes.getDefaultCoroutine().launch {
            val realSource = packageToPass.getRealSourceId()
            val userName = UsersUtils.getUserName(realSource)
            try {
                Timber.tag("PttReceiveSession").d("ts: ${ts.toLong()}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            messagesRepository.saveMessage(
                context = context,
                isPTT = true,
                messageItem = MessageItem(
                    senderID = realSource,
                    epochTimeMs = ts.toLong(),
                    senderName = userName,
                    chatId = packageToPass.source,
                    text = "",
                    fileLocation = file.absolutePath,
                    isAudio = true,
                    audioType = audioTypeId ?: 0
                )
            )
            DataManager.getCallbacks()?.startedReceivingPTT(packageToPass, file)
        }
    }


    // ── Persistence ───────────────────────────────────────────────────────────────────────────────
    fun saveAudioReceivedMessage(
        chatId: String,
        senderID: String,
        isAudioReceived: Boolean,
        file: File,
        audioTypeId: Int
    ) {
        if (!DataManager.getSavePTTFilesRequired(context) || chatId.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            chatsRepository.updateAudioReceived(chatId, isAudioReceived)
            val chatItem = chatsRepository.getChatByBittelID(chatId)
                ?: UsersUtils.createNewBittelUserPTTSender(chatsRepository, chatId)
            val userName = UsersUtils.getUserName(senderID)
            chatItem.let {
                it.message = Message(
                    senderID = senderID,
                    text = if (isAudioReceived) "Ptt Received" else "Receiving PTT from $userName…",
                    seen = true
                )
                chatsRepository.addChat(it)
                chatsRepository.updateNumOfUnseenMessages(chatId, it.numOfUnseenMessages + 1)
            }
            if (isAudioReceived) {
                savePttMessage(
                    context = context,
                    packageToPass = StardustAPIPackage(chatId, senderID),
                    file = file,
                    ts = timestamp.toString(),
                    audioTypeId = audioTypeId
                )
            }
        }
    }

    fun initPttInputFile(context: Context, packageToPass: StardustAPIPackage, audioType: RecorderUtils.CODE_TYPE): File? {
        if(fileToWrite != null) {
            Timber.tag("PttReceiveSession").d("File already initialized: ${fileToWrite?.absolutePath}")
            return fileToWrite
        }
        val file = createPttPcmFile(context, packageToPass.source) ?: return null

        fileToWrite = file

        saveAudioReceivedMessage(
            chatId = packageToPass.source,
            senderID = packageToPass.getRealSourceId(),
            isAudioReceived = false,
            file = file,
            audioTypeId = audioType.id)

        return file
    }
}



