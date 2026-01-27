package com.commcrete.stardust.audio

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.commcrete.aiaudio.media.WavHelper
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
import com.commcrete.stardust.util.UsersUtils.mRegisterUser
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

object AudioReceiverManager {

    val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())

    data class PlayerInfo(
        val audioTrack: AudioTrack?,
        val codeType: RecorderUtils.CODE_TYPE,
        val source: String,
        val destination: String,
        val pendingData: MutableList<StardustPackage> = mutableListOf(),
        val codecFrameBuffer : MutableList<ByteArray> = mutableListOf(),
        val AIFrameBuffer : MutableList<ShortArray> = mutableListOf(),
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
            AudioPlayerGenerator.stopAudio(audioTrack)
            Scopes.getDefaultCoroutine().launch {
                try {
                    handlePendingData()          // suspend: waits sequentially per packet
                    saveToFile(source, destination) // after pending data is added to buffer + wav written
                } finally {
                    // 4) always remove player at the end (even if decode/save throws)
                    removePlayer(this@PlayerInfo)
                }
            }
        }

        private fun saveToFile (source: String, destination : String) {
            if(!DataManager.getSavePTTFilesRequired(DataManager.context) || destination.isEmpty()) {
                return
            }
            val file = getFile(source, destination)
            writeToFile(file)
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

        private fun writeToFile (file: File) {
            when(codeType) {
                RecorderUtils.CODE_TYPE.CODEC2 -> writeCodec2ToFile(file)
                RecorderUtils.CODE_TYPE.AI     -> writeAIToFile(file)
            }
        }

        private fun writeCodec2ToFile(file: File) {
            val sampleArray = codecFrameBuffer.flatMap { it.asIterable() }.toByteArray()
            val sampleRate = 8000
            WavHelper.createWavFile(sampleArray, sampleRate, file)
        }

        private fun writeAIToFile(file: File) {
            val sampleArray = AIFrameBuffer.flatMap { it.asIterable() }.toShortArray()
            val sampleRate = 24000
            WavHelper.createWavFile(sampleArray, sampleRate, file)
        }

        private fun saveChatAndMessage (file: File, source: String, destination : String) {
            val context = DataManager.context

            val sentAsUserInGroup = GroupsUtils.isGroup(source) && (destination != mRegisterUser?.appId)
            val realSource = if (sentAsUserInGroup) destination else source
            PlayerUtils.updateAudioReceived(source, realSource,true)
            DataManager.getCallbacks()?.startedReceivingPTT(StardustAPIPackage(realSource, destination), file)
            Scopes.getDefaultCoroutine().launch {
                val userName = UsersUtils.getUserName(destination)
                messagesRepository.savePttMessage(
                    context = context,
                    MessageItem(senderID = destination,
                        epochTimeMs = PlayerUtils.ts.toLong(), senderName = userName ,
                        chatId = realSource, text = "", fileLocation = file.absolutePath,
                        isAudio = true)
                )
            }
        }

        private suspend fun handlePendingData () {
            for (pending in pendingData) {
                val pcmData = decodeAwait(pending)   // ⏳ waits for callback
                AIFrameBuffer.add(0, pcmData)          // prepend

            }
        }

        private suspend fun decodeAwait(pending: StardustPackage): ShortArray =
            suspendCancellableCoroutine { cont ->
                AIDecoder().decode(pending, onPcmReady = { pcmData ->
                    if (cont.isActive) cont.resume(pcmData)
                })
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

        val sentAsUserInGroup = GroupsUtils.isGroup(source) && (stardustPackage.getDestAsString() != mRegisterUser?.appId)
        val from = if (sentAsUserInGroup) {
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
                        info.AIFrameBuffer.add(pcmData)
                    })
                }
            } else {
                val decoded = Codec2Decoder().decode(stardustPackage)
                AudioPlayerGenerator.appendAudio(info.audioTrack, decoded)
                info.codecFrameBuffer.add(decoded)
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