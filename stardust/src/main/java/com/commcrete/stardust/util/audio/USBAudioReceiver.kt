package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.startPTT
import com.commcrete.stardust.util.DataManager.stopPTT
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.SharedPreferencesUtil.getAppUser
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File


object ButtonListener {

    private var currentFile: File? = null
    private var isClicked = false
    private var receivedAlready = false
    val isPlayPTT : CountingLiveData<Boolean> = CountingLiveData(false)
    var mediaSession: MediaSessionCompat? = null

    class CountingLiveData<T>(initialValue: T? = null) : MutableLiveData<T>(initialValue) {

        private var observerCount = 0

        fun getObserverCount(): Int {
            return observerCount
        }

        override fun onActive() {
            super.onActive()
            observerCount++
        }

        override fun onInactive() {
            super.onInactive()
            if (observerCount > 0) observerCount--
        }
    }

    fun updateMediaClick () {
        if(!this.receivedAlready) {
            this.isClicked = !this.isClicked
            this.receivedAlready = true
//            notifyData()
        }else {
            this.receivedAlready = false
        }
    }

    fun notifyData(isClicked: Boolean, context: Context) {
        // Update LiveData on main thread
        Scopes.getMainCoroutine().launch {
            isPlayPTT.value = isClicked
        }

        // Start or stop PTT recording on background thread
        Scopes.getDefaultCoroutine().launch {
            val uid = SharedPreferencesUtil.getLastUser(context)
            if (uid.isEmpty()) return@launch

            if (isClicked) {
                // Start recording
                currentFile = startPttRecord(context, uid)
                Timber.tag("isPlayPTT").d("startRecording")
            } else {
                // Stop recording
                currentFile?.let { dismissPttRecording(context, uid, it) }
                Timber.tag("isPlayPTT").d("finishRecording")
                currentFile = null
            }
        }
    }

    fun getCurrentFile(): File? = currentFile

    @SuppressLint("MissingPermission")
    fun dismissPttRecording (context: Context, chatID: String, file: File?) {
        val registerUser = getAppUser(context)
        registerUser?.appId?.let {
            stopPTT(context, StardustAPIPackage(it, chatID, false, null), SharedPreferencesUtil.getCodecType(DataManager.context), file)
        }
    }

    @SuppressLint("MissingPermission")
    fun startPttRecord(context: Context, chatID : String): File? {
        val registerUser = getAppUser(context)
        return registerUser?.appId?.let {
            startPTT(context,StardustAPIPackage(it, chatID, false, null), SharedPreferencesUtil.getCodecType(DataManager.context))
        }
    }

    fun setupMediaSession(context: Context) {
        // Initialize MediaSessionCompat
        mediaSession = MediaSessionCompat(context, "MediaButtonReceiver")

        mediaSession?.let {
            // Enable callbacks for media buttons
            it.setMediaButtonReceiver(null)

            // Set a callback for handling media button events
            it.setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                // Handle play/pause button press
                                Timber.d("Play/Pause button pressed")
                                return true
                            }
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                // Handle volume up button press
                                Timber.d("Volume Up button pressed")
                                return true
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                // Handle volume down button press
                                Timber.d("Volume Down button pressed")
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            // Activate the session
            it.isActive = true
            requestAudioFocus(context)
        }
    }

    private fun requestAudioFocus(context: Context) {
        val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
        val result = audioManager.requestAudioFocus(
            { focusChange -> /* Handle focus change */ },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.d("Audio focus granted")
        }
    }

}

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
            Timber.tag("MediaButtonReceiver").d("Headset button pressed")
//            Toast.makeText(context, "Headset button pressed", Toast.LENGTH_SHORT).show()
//            ButtonListener.updateMediaClick()
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { Timber.tag("MediaButtonReceiver").d("${keyEvent.keyCode}") }
                    KeyEvent.KEYCODE_VOLUME_UP -> {Timber.tag("MediaButtonReceiver").d("KEYCODE_VOLUME_UP")}
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {Timber.tag("MediaButtonReceiver").d("KEYCODE_VOLUME_DOWN")}
                }
            }
        }
    }


}