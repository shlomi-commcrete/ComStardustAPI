package com.commcrete.aiaudio.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRouter
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.context
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.audio.BleMediaConnector
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.File

/**
 * Simplest possible streaming PCM16 mono player.
 * Feed it ShortArray frames (any size). It buffers and writes them to an AudioTrack.
 * If sample rate changes, the track is recreated automatically.
 */
object PcmStreamPlayer : BleMediaConnector() {
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null

    // Small bounded buffer; if producer is faster, oldest frames are dropped to avoid OOM.
    private var frameChannel = Channel<ShortArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private const val bufferingDelay = 0.5f // seconds of audio to buffer

    private var measureLastFrameTime = 0L
    private var measureTotalSilenceTime = 0L
    private var measureCounterSilence = 0L
    private var measureMinTime = 0L
    private var measureMaxTime = 0L

    private val frameBuffer = mutableListOf<ShortArray>()

    private var legacyTrack: AudioTrack? = null
    private var legacyEnhancer: LoudnessEnhancer? = null
    private var legacyEqualizer: Equalizer? = null
    private const val LEGACY_SAMPLE_RATE = 8000
    private const val LEGACY_MIN_WRITE_SIZE = 40
    private const val PLAYBACK_TRACE_TAG = "PTTPlaybackTrace"

    private var isFileInit = false
    private var destination : String = ""
    private var source : String = ""
    private var fileToWrite : File? = null
    private var ts = ""
    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = Runnable {
        Scopes.getMainCoroutine().launch {
            Log.d("PcmStreamPlayer", "Stopping file write due to inactivity.")
            val file = fileToWrite
            Log.d("PcmStreamPlayer", "File to write: ${file?.absolutePath}")
            file?.let {
                Log.d("PcmStreamPlayer", "Saving frames to WAV file: ${it.absolutePath}")
                saveFramesToWav(it)
            }

            isFileInit = false
            fileToWrite = null
            ts = ""
            destination = ""
            isRecoded = false
            isFirst = true
            startRecored = 0L

        }
    }
    @Synchronized
    private fun ensureTrack(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate) return
        releaseInternal()
        currentSampleRate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Calculate buffer size for buffering
        val samplesForBuffering = (sampleRate * bufferingDelay).toInt()
        val bytesForBuffering = samplesForBuffering * 2 // 2 bytes per sample (16-bit)

        val bufferSize = maxOf(minBuf, bytesForBuffering)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        }
        startPlaybackLoop()
    }

    private fun startPlaybackLoop() {
        audioTrack?.play()
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while(isActive) {

                val frame = frameChannel.tryReceive().getOrNull()
                if (frame != null) {
                    measureTime()

                    frameBuffer.add(frame)
//                    saveFramesToWav()

                    val track = audioTrack ?: break
                    var offset = 0
                    val total = frame.size
                    while (offset < total) {
                        val written = track.write(frame, offset, total - offset)
                        if (written <= 0) break else offset += written
                    }
                }

                delay(1)
            }
        }
    }

    @SuppressLint("NewApi")
    fun ensureLegacyTrack(bufferSizeInBytes: Int, speedFactor: Float) {
        try {
            if (legacyTrack == null) {
                legacyTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate((LEGACY_SAMPLE_RATE * speedFactor).toInt())
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSizeInBytes)
                    .build()
                syncBleDevice(DataManager.context)
                Log.d(PLAYBACK_TRACE_TAG, "ensureLegacyTrack created track=${legacyTrack?.hashCode()} buffer=$bufferSizeInBytes")
            }

            legacyTrack?.audioSessionId?.let {
                legacyEnhancer?.release()
                legacyEnhancer = LoudnessEnhancer(it)
                val audioPct = 5.4
                val gainmB = Math.round(Math.log10(audioPct) * 2000).toInt()
                legacyEnhancer?.setTargetGain(gainmB)
                legacyEnhancer?.setEnabled(true)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                legacyTrack?.play()
            }, 150)
        } catch (e: Exception) {
            Log.e(PLAYBACK_TRACE_TAG, "ensureLegacyTrack failed", e)
        }
    }

    fun playLegacyStream(audioData: ByteArray, bufferSizeInBytes: Int, receivedPkgs: Int, playFromSdk: Boolean) {
        val track = legacyTrack
        Log.d(
            PLAYBACK_TRACE_TAG,
            "playLegacyStream track=${track?.hashCode()} dataSize=${audioData.size} buffer=$bufferSizeInBytes receivedPkgs=$receivedPkgs flag=$playFromSdk"
        )
        if (track == null) {
            Log.d(PLAYBACK_TRACE_TAG, "playLegacyStream skipped because legacy track is null")
            return
        }

        try {
            track.notificationMarkerPosition = bufferSizeInBytes / 2
            scope.launch {
                if (playFromSdk) {
                    synchronized(track) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try {
                                track.play()
                            } catch (e: IllegalStateException) {
                                Log.e("AudioTrack", "Failed to start playback: ${e.message}")
                                return@synchronized
                            }
                        }

                        if (audioData.isNotEmpty() && audioData.size >= LEGACY_MIN_WRITE_SIZE) {
                            try {
                                val bytesWritten = track.write(audioData, 0, audioData.size)
                                Log.d(
                                    PLAYBACK_TRACE_TAG,
                                    "playLegacyStream write track=${track.hashCode()} bytesWritten=$bytesWritten playState=${track.playState}"
                                )
                                if (bytesWritten < 0) {
                                    Log.e("AudioTrack", "AudioTrack write failed with code $bytesWritten")
                                }
                            } catch (e: IllegalStateException) {
                                Log.e("AudioTrack", "AudioTrack write failed: ${e.message}")
                            }
                        }
                    }
                } else {
                    Log.d(PLAYBACK_TRACE_TAG, "playLegacyStream skipped write because isPlayPttFromSdk=false track=${track.hashCode()}")
                }
            }
        } catch (e: IllegalStateException) {
            ensureLegacyTrack(bufferSizeInBytes, 1.0f)
            track.flush()
        }
    }

    fun releaseLegacyTrack() {
        try {
            legacyTrack?.flush()
            legacyTrack?.release()
            legacyEnhancer?.release()
            legacyEqualizer?.release()
        } catch (e: Exception) {
            Log.e(PLAYBACK_TRACE_TAG, "releaseLegacyTrack failed", e)
        } finally {
            legacyTrack = null
            legacyEnhancer = null
            legacyEqualizer = null
        }
    }

    fun writeMinimumSilenceAndPlay(byteArray: ByteArray, onPlaybackComplete: () -> Unit) {
        val tempTrack = getTempTrack()
        Log.d(
            PLAYBACK_TRACE_TAG,
            "writeMinimumSilenceAndPlay size=${byteArray.size} tempTrack=${tempTrack.hashCode()}"
        )
        tempTrack.play()
        tempTrack.write(byteArray, 0, byteArray.size)

        Handler(Looper.getMainLooper()).postDelayed({
            tempTrack.flush()
            tempTrack.release()
            onPlaybackComplete()
        }, 880)
    }

    @SuppressLint("NewApi")
    private fun getTempTrack(): AudioTrack {
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(LEGACY_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(642)
            .build()
        audioTrack.audioSessionId.let {
            legacyEnhancer?.release()
            legacyEnhancer = LoudnessEnhancer(it)
            val audioPct = 5.4
            val gainmB = Math.round(Math.log10(audioPct) * 2000).toInt()
            legacyEnhancer?.setTargetGain(gainmB)
            legacyEnhancer?.setEnabled(true)
        }
        return audioTrack
    }

    @SuppressLint("NewApi")
    private fun syncBleDevice(context: Context) {
        val audioManager = DataManager.context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager, AudioManager.GET_DEVICES_OUTPUTS, context)
        bleDevice?.let {
            legacyTrack?.setPreferredDevice(it)
            audioManager.startBluetoothSco()
            audioManager.setBluetoothScoOn(true)
            if (it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                try {
                    routeAudioToMediaRouter(context)
                } catch (e: Exception) {
                    Log.e(PLAYBACK_TRACE_TAG, "syncBleDevice routeAudioToMediaRouter failed", e)
                }
            }
        }
    }

    private fun routeAudioToMediaRouter(context: Context) {
        val mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
    }

    private fun measureTime() {
        val currentTime = System.currentTimeMillis()

        // Calculate silence duration
        if (measureLastFrameTime > 0) {
            val actualGap = currentTime - measureLastFrameTime
            if (actualGap < 950) { // 50ms tolerance
                measureTotalSilenceTime += actualGap
                measureCounterSilence++
                if (measureMinTime == 0L || actualGap < measureMinTime) measureMinTime = actualGap
                if (actualGap > measureMaxTime) measureMaxTime = actualGap
                Log.d("PcmStreamPlayer", "actualGap: $actualGap, average: ${measureTotalSilenceTime / measureCounterSilence}, minTime: $measureMinTime, maxTime: $measureMaxTime")
            } else if (actualGap < 1200) { // 1.2 seconds
                Log.d("PcmStreamPlayer", "Frame dropped")
            }
        }

        measureLastFrameTime = currentTime
    }

    /** Enqueue a PCM16 mono frame. Recreates track if sample rate has changed. */
    fun enqueue(frame: ShortArray, sampleRate: Int, from : String, source: String?) {
        resetTimer()
        setTs()
        Scopes.getDefaultCoroutine().launch {
            source?.let {
                Log.d("PcmStreamPlayer", "initPttInputFile called with from: $from, source: $source")
                if(!isFileInit){
                    Log.d("PcmStreamPlayer", "Initializing PTT input file...")
                    initPttInputFile(DataManager.context, from, source, null)
                }
            }
        }
        ensureTrack(sampleRate)
        frameChannel.trySend(frame)
    }

    /** Stop and release resources. */
    fun stop() { releaseInternal() }

    @Synchronized
    private fun releaseInternal() {
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.run {
            try { stop() } catch (_: Exception) {}
            try { flush() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioTrack = null
        currentSampleRate = -1
        frameChannel.close()
        frameChannel = Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        measureLastFrameTime = 0L
        measureTotalSilenceTime = 0L
        measureCounterSilence = 0L
        measureMinTime = 0L
        measureMaxTime = 0L
        isRecoded = false
        isFirst = true
    }

    private var isFirst = true;
    private var isRecoded = false;
    private var startRecored = 0L
    private fun saveFramesToWav(file: File) {
        Log.d("PcmStreamPlayer", "saveFramesToWav called.")
        Log.d("PcmStreamPlayer", "Number of buffered frames: ${frameBuffer.size}")
        Log.d("PcmStreamPlayer", "isRecoded: $isRecoded")
        if (frameBuffer.isEmpty() || isRecoded) return

        if (isFirst) {
            Log.d("PcmStreamPlayer", "Starting recording timer.")
            isFirst = false
            startRecored = System.currentTimeMillis()
        }

        Scopes.getDefaultCoroutine().launch {
            delay(1500)
            if (System.currentTimeMillis() - startRecored > 1500) {
                Log.d("PcmStreamPlayer", "Saving buffered frames to WAV file after 1.5 seconds.")
//             Save buffered frames to WAV file
                val sampleArray = frameBuffer.flatMap { it.asIterable() }.toShortArray()
                WavHelper.createWavFile(sampleArray, currentSampleRate, file)
                frameBuffer.clear()
                isRecoded = false
            }
        }
    }

    private fun checkIfBufferLow(track: AudioTrack) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - measureLastFrameTime > 1100) {
            return
        }

        // Get current playback state
        val playState = track.playState
        val bufferSize = track.bufferSizeInFrames
        val headPosition = track.playbackHeadPosition

        // Check if buffer is getting low
        val bufferedSamples = bufferSize - headPosition
        val isBufferLow = bufferedSamples < (currentSampleRate * 0.05) // Less than 50ms buffered

        if (isBufferLow) {
            Log.w("PcmStreamPlayer", "Buffer running low: $bufferedSamples samples remaining, playState: $playState, head position: $headPosition, buffer size: $bufferSize")
        }
    }


    private fun updateAudioReceived(chatId: String, senderId: String, isAudioReceived : Boolean){
        if(!DataManager.getSavePTTFilesRequired(context) || chatId.isEmpty()) { return }

        Scopes.getDefaultCoroutine().launch {
            val repo = DataManager.getAppRepo(context)
            repo.updateAudioReceived(chatId, isAudioReceived)
            val chatItem = repo.getChatByDeviceId(chatId)
            chatItem?.let {
                chatItem.message = Message(senderID = senderId, text = "Ptt Received", seen = true)
                repo.addChat(it)
                repo.updateNumOfUnseenMessages(chatId, chatItem.numOfUnseenMessages + 1)
            }
        }
    }

    private fun initPttInputFile(
        context: Context,
        destinations: String,
        source: String,
        snifferContacts: List<ChatContact>?
    ): File? {
        if (snifferContacts != null) {
            return PlayerUtils.initPttSnifferFile(context, destinations, snifferContacts)
        }

        val destination = destinations.trim().replace("[\"", "").replace("\"]", "")
        val packageToPass = StardustAPIPackage(source, destination)
        this.destination = destinations
        val realSource = packageToPass.getRealSourceId()
        updateAudioReceived(source, realSource, true)

        val dir = File(context.filesDir, source)

        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e("PcmStreamPlayer", "Failed to create directory: ${dir.absolutePath}")
                return null
            }
        }

        val file = fileToWrite ?: File(dir, "$ts-$source.pcm")

        if (!file.exists()) {
            file.createNewFile()
            fileToWrite = file
            isFileInit = true

            CoroutineScope(Dispatchers.IO).launch {
                val userName = UsersUtils.getUserName(realSource)
                DataManager.getAppRepo(DataManager.context).saveMessage(
                    context = context,
                    isPTT = true,
                    messageItem = MessageItem(
                        senderID = realSource,
                        epochTimeMs = ts.toLong(),
                        senderName = userName,
                        chatId = source,
                        text = "",
                        fileLocation = file.absolutePath,
                        isAudio = true,
                        audioType = RecorderUtils.CODE_TYPE.AI.id
                    )
                )
            }

            DataManager.getCallbacks()?.startedReceivingPTT(packageToPass, file)
        }

        return file
    }

    private fun setTs(){
        if(ts.isEmpty()){
            ts = (System.currentTimeMillis()).toString()
        }
    }
    private fun resetTimer(){
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 2000)
    }
}
