package com.commcrete.stardust.audio

import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.audio.players.GenerateAudioPlayer
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.launch

object AudioPlayerGenerator {

    fun generateAudioPlayer(codeType: RecorderUtils.CODE_TYPE) : AudioTrack? {
        return if(codeType == RecorderUtils.CODE_TYPE.CODEC2) {
            GenerateAudioPlayer().generateAudioPlayer()
        } else {
            GenerateAudioPlayer().generateAudioPlayerAI()
        }
    }

    fun playAudio(audioTrack: AudioTrack?) {
        Handler(Looper.getMainLooper()).postDelayed( {
            audioTrack?.play()
        }, 150)
    }

    fun appendAudio(audioTrack: AudioTrack?, audioData: ByteArray) {
        Scopes.getDefaultCoroutine().launch{
            audioTrack?.let {
                if(DataManager.isPlayPttFromSdk) {
                    synchronized(it) {
                        it.write(audioData, 0, audioData.size)
                    }
                }
            }
        }
    }

    fun appendAudio(audioTrack: AudioTrack?, audioData: ShortArray) {
        Scopes.getDefaultCoroutine().launch{
            audioTrack?.let {
                if(DataManager.isPlayPttFromSdk) {
                    synchronized(it) {
                        it.write(audioData, 0, audioData.size)
                    }
                }
            }
        }
    }

    fun stopAudio (audioTrack: AudioTrack?) {
        try {
            audioTrack?.apply {
                pause()
                flush()
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}