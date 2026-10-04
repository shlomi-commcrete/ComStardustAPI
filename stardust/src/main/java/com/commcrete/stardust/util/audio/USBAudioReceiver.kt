package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.R
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.startPTT
import com.commcrete.stardust.util.DataManager.stopPTT
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors


object ButtonListener {

    private const val TAG = "PttButton"

    private val buttonDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

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

    /** Who a hardware-button press talks to. Captured on press and reused on release. */
    private data class PttTarget(val chatId: String, val pttPackage: StardustAPIPackage)

    /** Non-null while a recording started by the hardware button is open. */
    @Volatile
    private var activeTarget: PttTarget? = null

    /**
     * Resolves who a button press talks to, in order:
     * 1. [DataManager]'s chatId/destination — set by the last [startPTT] or by the host through
     *    [DataManager.setPttTarget].
     * 2. The first group that has a chat: its chatId, with the group id as destination.
     * Returns null when neither exists. The sender is always the registered user.
     */
    private suspend fun resolveTarget(): PttTarget? {
        val senderId = RegisteredUserUtils.currentUserFlow.value?.appId?.takeIf { it.isNotBlank() }
            ?: return null.also { Log.w(TAG, "no registered user") }

        var chatId = DataManager.getChatId()
        var receiverId = DataManager.getDestination()
        if (chatId.isBlank() || receiverId.isBlank()) {
            val group = firstGroupChat() ?: return null
            chatId = group.first
            receiverId = group.second
            Log.d(TAG, "no PTT target set — falling back to group $receiverId (chat $chatId)")
        }
        return PttTarget(chatId, StardustAPIPackage(senderId = senderId, receiverId = receiverId, chatId = chatId, isLast = false))
    }

    /** (chatId, groupId) of the first group that resolves to a chat, or null. */
    private suspend fun firstGroupChat(): Pair<String, String>? {
        val repo = DataManager.getAppRepo()
        for (groupId in repo.getAllGroupIds()) {
            val chatId = runCatching { repo.getChatIdForReceivedPackage(groupId, groupId) }.getOrNull()
            if (!chatId.isNullOrBlank()) return chatId to groupId
        }
        return null
    }

    /**
     * How long CTS must stay OFF before a release counts. A shorter OFF blip — contact bounce, or a
     * flicker while the button is held — is absorbed instead of ending the transmission and starting
     * a new one. Presses are not delayed.
     */
    private const val RELEASE_DEBOUNCE_MS = 150L

    /** The release waiting out [RELEASE_DEBOUNCE_MS]. Only touched on [buttonDispatcher]. */
    private var pendingRelease: Job? = null

    fun notifyData(isClicked: Boolean) {
        Log.d(TAG, "CTS ${if (isClicked) "PRESSED" else "RELEASED"}")

        // Everything runs on one background thread, so a quick tap's release can never overtake its
        // press (separate Dispatchers.IO launches carry no ordering), and pendingRelease needs no lock.
        val scope = CoroutineScope(buttonDispatcher)
        scope.launch {
            if (isClicked) {
                val bounced = pendingRelease?.isActive == true
                pendingRelease?.cancel()
                pendingRelease = null
                if (bounced && activeTarget != null) {
                    Log.d(TAG, "release cancelled — CTS came back within ${RELEASE_DEBOUNCE_MS}ms, still transmitting")
                    return@launch
                }
                onPress()
            } else {
                pendingRelease?.cancel()
                pendingRelease = scope.launch {
                    delay(RELEASE_DEBOUNCE_MS)
                    pendingRelease = null
                    onRelease()
                }
            }
        }
    }

    private suspend fun onPress() {
        if (activeTarget != null) {
            Log.w(TAG, "press ignored — a button recording is already open")
            return
        }
        if (RecorderUtils.isRecordingInProgress()) {
            // e.g. on-screen PTT is held: RecorderUtils would reject the start anyway.
            Log.w(TAG, "press ignored — another recording is in progress")
            publishPlayPtt(false)
            return
        }
        val target = resolveTarget()
        if (target == null) {
            // Nowhere to send: no target set and no group chat. The stop beep tells the user the
            // press did nothing.
            Log.w(TAG, "press ignored — no PTT target and no group chat to fall back to")
            runCatching { SoundPlayer.play(DataManager.appContext, R.raw.ptt_finished_beep) }
            publishPlayPtt(false)
            return
        }
        // The v2 pipeline returns null here by design, so whether a recording started is read from
        // RecorderUtils' in-progress flag, never from currentFile.
        currentFile = runCatching { startPttRecord(target.chatId, target.pttPackage) }
            .onFailure { Log.e(TAG, "startRecording threw", it) }
            .getOrNull()
        if (!RecorderUtils.isRecordingInProgress()) {
            Log.w(TAG, "recording did not start chatId=${target.chatId} to=${target.pttPackage.receiverId}")
            currentFile = null
            publishPlayPtt(false)
            return
        }
        activeTarget = target
        publishPlayPtt(true)
        Log.d(TAG, "startRecording chatId=${target.chatId} to=${target.pttPackage.receiverId} file=${currentFile != null}")
    }

    private fun onRelease() {
        publishPlayPtt(false)
        val target = activeTarget
        if (target == null) {
            Log.d(TAG, "release ignored — no button recording is open")
            return
        }
        dismissPttRecording(target.chatId, target.pttPackage.copy(isLast = true), currentFile)
        Log.d(TAG, "stopRecording chatId=${target.chatId} to=${target.pttPackage.receiverId}")
        activeTarget = null
        currentFile = null
    }

    /** True only while a button-started recording is open. */
    private fun publishPlayPtt(isPlaying: Boolean) {
        Scopes.getMainCoroutine().launch { isPlayPTT.value = isPlaying }
    }

    fun getCurrentFile(): File? = currentFile

    @SuppressLint("MissingPermission")
    fun dismissPttRecording(chatId: String, pttPackage: StardustAPIPackage, file: File?) {
        stopPTT(chatId = chatId, stardustAPIPackage = pttPackage, codeType = SharedPreferencesUtil.getCodecType(), file = file)
    }

    @SuppressLint("MissingPermission")
    fun startPttRecord(chatId: String,  pttPackage : StardustAPIPackage): File? {
        return startPTT(chatId, pttPackage, SharedPreferencesUtil.getCodecType())
    }

    fun setupMediaSession() {
        // Initialize MediaSessionCompat
        mediaSession = MediaSessionCompat(DataManager.appContext, "MediaButtonReceiver")

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
            requestAudioFocus()
        }
    }

    private fun requestAudioFocus() {
        val audioManager = DataManager.appContext.getSystemService(AUDIO_SERVICE) as AudioManager
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