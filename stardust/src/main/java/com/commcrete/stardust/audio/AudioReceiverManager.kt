package com.commcrete.stardust.audio

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.launch
import java.io.File

object AudioReceiverManager {

    val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())

    data class PlayerInfo(
        val audioTrack: AudioTrack?,
        val codeType: RecorderUtils.CODE_TYPE,
        val source: String,
        val destination: String,
        val pendingData: MutableList<StardustPackage> = mutableListOf(),
        val frameBuffer : MutableList<Any> = mutableListOf(),
        val fileToSave: File? = null
    ) {
        private val timeout: Long = if (codeType == RecorderUtils.CODE_TYPE.CODEC2) 1200 else 800
        private val handler: Handler = Handler(Looper.getMainLooper())
        private val runnable: Runnable = Runnable {
            onFinish()
        }

        fun resetTimer() {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(runnable, timeout)
        }

        fun removeTimer() {
            try {
                handler.removeCallbacks(runnable)
                handler.removeCallbacksAndMessages(null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fun onFinish() {
            removeTimer()
            saveToFile(source, destination)
            AudioPlayerGenerator.stopAudio(audioTrack)
            removePlayer(this)
        }

        private fun saveToFile (source: String, destination : String) {
            if(!DataManager.getSavePTTFilesRequired(DataManager.context) || destination.isEmpty()) {
                return
            }
            val file = getFile(source, destination)
            saveChatAndMessage(file, source, destination)
        }

        private fun getFile (source: String, destination : String) : File {
            val context = DataManager.context
            val ts = System.currentTimeMillis()
            val directory = File("${context.filesDir}/$destination")
            val file = File("${context.filesDir}/$destination/${ts}-$source.pcm")
            if (!directory.exists()) {
                directory.mkdir()
                if(!file.exists()) {
                    file.createNewFile()
                }
            }
            return file
        }

        private fun saveChatAndMessage (file: File, source: String, destination : String) {
            val context = DataManager.context
            val realDest = if   (GroupsUtils.isGroup(source)) source else destination
            PlayerUtils.updateAudioReceived(destination, true)
            DataManager.getCallbacks()?.startedReceivingPTT(StardustAPIPackage(realDest, destination), file)
            Scopes.getDefaultCoroutine().launch {
                val userName = UsersUtils.getUserName(destination)
                messagesRepository.savePttMessage(
                    context = context,
                    MessageItem(senderID = destination,
                        epochTimeMs = PlayerUtils.ts.toLong(), senderName = userName ,
                        chatId = realDest, text = "", fileLocation = file.absolutePath,
                        isAudio = true)
                )
            }
        }

    }

    var playerList: MutableList<PlayerInfo> = mutableListOf()

    private fun handlePTTMessage(stardustPackage: StardustPackage) {
        handlePTTPackage(stardustPackage, RecorderUtils.CODE_TYPE.CODEC2)
    }

    private fun handlePTTAIMessage(stardustPackage: StardustPackage) {
        handlePTTPackage(stardustPackage, RecorderUtils.CODE_TYPE.AI)
    }

    private fun handlePTTPackage(
        stardustPackage: StardustPackage,
        codeType: RecorderUtils.CODE_TYPE
    ) {
        val source = stardustPackage.getSourceAsString()
        if (source.isEmpty()) return

        val from = if (GroupsUtils.isGroup(source)) {
            stardustPackage.getDestAsString()
        } else {
            source
        }
        val playerInfo = getPlayerInfo(from,stardustPackage.getSourceAsString(), codeType )
        playerInfo?.let { info ->
            info.resetTimer()
            if(codeType == RecorderUtils.CODE_TYPE.AI) {
                val hasOtherAI = hasOtherAIPlayer(from, playerInfo)
                if (hasOtherAI ) {
                    info.pendingData += stardustPackage
                } else {
                    AIDecoder().decode(stardustPackage, onPcmReady = { pcmData ->
                        AudioPlayerGenerator.appendAudio(info.audioTrack, pcmData)
                        info.frameBuffer.add(pcmData)
                    })
                }
            } else {
                val decoded = Codec2Decoder().decode(stardustPackage)
                AudioPlayerGenerator.appendAudio(info.audioTrack, decoded)
                info.frameBuffer.add(decoded)
            }
        }
    }

    private fun getPlayerInfo(source: String,destination : String ,codeType: RecorderUtils.CODE_TYPE): PlayerInfo? {
        return playerList.firstOrNull {
            it.source == source && it.codeType == codeType
        }
            ?: AudioPlayerGenerator.generateAudioPlayer(codeType)?.let { audioTrack ->
                val newPlayer = PlayerInfo(
                    audioTrack = audioTrack,
                    codeType = codeType,
                    source = source,
                    destination = destination,
                    pendingData = mutableListOf()
                )
                playerList.add(newPlayer)
                newPlayer
            }
    }

    private fun hasOtherAIPlayer(
        source: String,
        exclude: PlayerInfo?
    ): Boolean {
        return playerList.any {
            it.source == source &&
                    it.codeType == RecorderUtils.CODE_TYPE.AI &&
                    it !== exclude
        }
    }

    fun removePlayer(player: PlayerInfo) {
        player.removeTimer()
        playerList.remove(player)
    }

    private fun isFinish(info: PlayerInfo, stardustPackage: StardustPackage) {
        if(stardustPackage.stardustControlByte.stardustPartType == StardustControlByte.StardustPartType.LAST) {
            info.onFinish()
        }
    }
}