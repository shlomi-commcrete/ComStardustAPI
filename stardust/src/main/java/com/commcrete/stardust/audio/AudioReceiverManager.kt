package com.commcrete.stardust.audio

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.audio.RecorderUtils

object AudioReceiverManager {

    data class PlayerInfo(
        val audioTrack: AudioTrack?,
        val codeType: RecorderUtils.CODE_TYPE,
        val source: String,
        val pendingData: MutableList<StardustPackage> = mutableListOf()
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
            Handler(Looper.getMainLooper()).postDelayed({
                AudioPlayerGenerator.stopAudio(audioTrack)
                saveToFile()            // Remove from manager
                removePlayer(this)
            }, 880)
        }

        private fun saveToFile () {

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
        val playerInfo = getPlayerInfo(from, codeType)
        playerInfo?.let { info ->
            info.resetTimer()
            if(codeType == RecorderUtils.CODE_TYPE.AI) {
                val hasOtherAI = hasOtherAIPlayer(from, playerInfo)
                if (hasOtherAI ) {
                    info.pendingData += stardustPackage
                } else {
                    AIDecoder().decode(stardustPackage, onPcmReady = { pcmData ->
                        AudioPlayerGenerator.appendAudio(info.audioTrack, pcmData)
                    })
                }
            } else {
                AudioPlayerGenerator.appendAudio(info.audioTrack, Codec2Decoder().decode(stardustPackage))
            }
            isFinish(info, stardustPackage)
        }
    }

    private fun getPlayerInfo(source: String, codeType: RecorderUtils.CODE_TYPE): PlayerInfo? {
        return playerList.firstOrNull {
            it.source == source && it.codeType == codeType
        }
            ?: AudioPlayerGenerator.generateAudioPlayer(codeType)?.let { audioTrack ->
                val newPlayer = PlayerInfo(
                    audioTrack = audioTrack,
                    codeType = codeType,
                    source = source,
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