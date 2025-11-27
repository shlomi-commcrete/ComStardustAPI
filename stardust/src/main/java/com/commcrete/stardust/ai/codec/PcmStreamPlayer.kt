package com.commcrete.aiaudio.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.audio.PlayerUtils
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.audio.WavRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.io.File
import kotlin.text.compareTo
import kotlin.times

/**
 * Simplest possible streaming PCM16 mono player.
 * Feed it ShortArray frames (any size). It buffers and writes them to an AudioTrack.
 * If sample rate changes, the track is recreated automatically.
 */
object PcmStreamPlayer {
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
        if(chatId.isEmpty()) {
            return
        }
        Scopes.getDefaultCoroutine().launch {
            PlayerUtils.chatsRepository.updateAudioReceived(chatId, isAudioReceived)
            val chatItem = PlayerUtils.chatsRepository.getChatByBittelID(chatId)
            chatItem?.let {
                chatItem.message = Message(senderID = senderId, text = "Ptt Received",
                    seen = true)
                PlayerUtils.chatsRepository.addChat(it)
            }
        }
    }

    private suspend fun initPttInputFile(context: Context, destinations: String, source: String
                                         , snifferContacts: List<ChatContact>?) : File? {
        Log.d("PcmStreamPlayer", "initPttInputFile called with destinations: $destinations, source: $source")
        if(snifferContacts != null) {
            return PlayerUtils.initPttSnifferFile(context, destinations, snifferContacts)
        }
        val destination = destinations.trim().replace("[\"", "").replace("\"]", "")
        this.destination = destinations
        val realDest = if   (GroupsUtils.isGroup(source)) source else destination
        updateAudioReceived(realDest, destination, true)
        val directory = if(fileToWrite !=null) fileToWrite else File("${context.filesDir}/$destination")
        val file = if(fileToWrite !=null) fileToWrite else File("${context.filesDir}/$destination/${ts}-$source.pcm")
        if(directory!=null){
            if(!directory.exists()){
                directory.mkdir()
            }
            if (file != null) {
                if(!file.exists()){
                    file.createNewFile()
                    fileToWrite = file
                    Scopes.getDefaultCoroutine().launch {
                        val userName = UsersUtils.getUserName(destination)
                        try {
                            Timber.tag("savePTT").d("ts : ${ts.toLong()}")
                        }catch (e :Exception){
                            e.printStackTrace()
                        }
                        PlayerUtils.messagesRepository.savePttMessage(
                            MessageItem(senderID = destination,
                                epochTimeMs = ts.toLong(), senderName = userName ,
                                chatId = realDest, text = "", fileLocation = file.absolutePath,
                                isAudio = true, audioType = RecorderUtils.CODE_TYPE.AI.id)
                        )
                    }
                }
                isFileInit = true
            }
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
