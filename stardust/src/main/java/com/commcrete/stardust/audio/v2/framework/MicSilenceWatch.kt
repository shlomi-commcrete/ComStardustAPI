package com.commcrete.stardust.audio.v2.framework

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.PttRecordingError

/**
 * Framework ring — tells when Android is feeding one recording silence instead of audio, and why.
 *
 * Android mutes a recording rather than failing it: the record keeps RECORDING and delivers zeros
 * (AudioRecordingConfiguration.isClientSilenced). Without this, a muted PTT is announced STARTED and SENT
 * while nothing audible goes out.
 *
 * API 29+ only — isClientSilenced does not exist below; there every call here is a no-op.
 *
 * @param backgroundCanMute false for a process that holds a microphone foreground service, where
 *   "not on screen" never mutes, so a mute there is never reported as [PttRecordingError.MIC_MUTED_IN_BACKGROUND].
 */
internal class MicSilenceWatch(
    private val context: Context,
    private val backgroundCanMute: Boolean,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var callback: AudioManager.AudioRecordingCallback? = null

    /** Why [sessionId] is muted right now, or null when it is not (or this API level cannot tell). */
    fun currentCause(sessionId: Int): PttRecordingError? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val config = audioManager?.activeRecordingConfigurations?.firstOrNull { it.clientAudioSessionId == sessionId }
        return if (config?.isClientSilenced == true) cause() else null
    }

    /**
     * Calls [onMuted] once if [sessionId] is muted later. Delivered on the main thread; [onMuted] must
     * only hand off (flag + stop), never block.
     */
    fun watch(sessionId: Int, onMuted: (PttRecordingError) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = audioManager ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            private var reported = false
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (reported) return
                val muted = configs.firstOrNull { it.clientAudioSessionId == sessionId }?.isClientSilenced == true
                if (!muted) return
                reported = true
                onMuted(cause())
            }
        }
        callback = cb
        am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
    }

    /** Idempotent. */
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { audioManager?.unregisterAudioRecordingCallback(cb) }
    }

    private fun cause(): PttRecordingError =
        causeOf(audioManager?.mode ?: AudioManager.MODE_NORMAL, isOnScreen(), backgroundCanMute)

    companion object {
        /**
         * Best explanation Android allows. A call is checked first because it mutes even an on-screen app;
         * the SDK never sets the audio mode itself, so a call mode here is another app's call.
         */
        fun causeOf(audioMode: Int, onScreen: Boolean, backgroundCanMute: Boolean): PttRecordingError {
            // MODE_CALL_SCREENING is 4 (API 30); compared by value so this stays callable below API 30.
            val inCall = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION ||
                audioMode == MODE_CALL_SCREENING
            return when {
                inCall -> PttRecordingError.MIC_TAKEN_BY_CALL
                backgroundCanMute && !onScreen -> PttRecordingError.MIC_MUTED_IN_BACKGROUND
                else -> PttRecordingError.MIC_ACCESS_BLOCKED
            }
        }

        private const val MODE_CALL_SCREENING = 4
    }

    private fun isOnScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
