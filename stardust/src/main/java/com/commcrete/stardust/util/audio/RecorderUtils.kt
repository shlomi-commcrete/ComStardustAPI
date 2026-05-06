package com.commcrete.stardust.util.audio

import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.Scopes
import com.example.chunkrecorder.AudioRecorderAI
import com.ustadmobile.codec2.Codec2
import com.commcrete.stardust.room.new_db.message.EncoderType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File


object RecorderUtils {

    //var file : File? = null
    var ts : Long = 0
    private val LOG_TAG = "AudioRecordTest"

    private var pttInterface : PttInterface? = null
    private var wavRecorder : WavRecorder? = WavRecorder(DataManager.context)
    private var aiRecorder : AudioRecorderAI? = null

    val canRecord : MutableLiveData<Boolean> = MutableLiveData(true)


    fun init(pttInterface : PttInterface){
        RecorderUtils.pttInterface = pttInterface
    }

    fun onPTTTest(){
        wavRecorder = WavRecorder(DataManager.context, null)
        wavRecorder?.sendAudioTest(DataManager.context)
    }


    // ----------------------------------------
    // Start Recording
    // ----------------------------------------
    @RequiresPermission(RECORD_AUDIO)
    fun startRecording(
        chatId: String,
        receiverId: String,
        carrier: Carrier?,
        codeType: AudioEncoderType?
    ): File? {
        Log.d("AudioRecorder", "Start recording")

        Scopes.getMainCoroutine().launch { canRecord.value = false }

        return if (codeType == AudioEncoderType.CODEC2) {
            startCodec2Recording(receiverId, carrier)
        } else {
            startAIRecording(chatId, receiverId, carrier)
        }
    }

    private fun startCodec2Recording(destination: String, carrier: Carrier?): File? {
        wavRecorder = WavRecorder(DataManager.context, pttInterface)
        wavRecorder ?: return null
        val file: File? = if (!DataManager.getSavePTTFilesRequired(DataManager.context)) {
            FileUtils.withTempFile(
                context = DataManager.context,
                prefix = destination,
                suffix = DataManager.getSource()
            ) { tempFile ->
                wavRecorder?.startRecording(tempFile, carrier)
            }
        } else {
            createFile(DataManager.fileLocation, destination, DataManager.getSource())?.also {
                wavRecorder?.startRecording(it, carrier)
            }
        }
        return file
    }

    private fun startAIRecording(chatId: String, receiverId: String, carrier: Carrier?): File? {
        Log.d("AudioRecorder", "NAE Recording Started")
        PttSendManager.init(DataManager.context, pttInterface)

        // Determine which file to use
        val file: File? = if (!DataManager.getSavePTTFilesRequired(DataManager.context)) {
            // Use temporary file
            FileUtils.withTempFile(
                context = DataManager.context,
                prefix = receiverId,
                suffix = DataManager.getSource()
            ) { tempFile ->
                setupAIRecorder(tempFile, receiverId, chatId, carrier)
            }
        } else {
            // Use persistent file
            val persistentFile = createFile(DataManager.fileLocation, chatId, receiverId)
            persistentFile?.let { setupAIRecorder(it, receiverId, chatId, carrier) }
            persistentFile
        }

        return file
    }

    private fun setupAIRecorder(file: File, receiverId: String, chatId: String, carrier: Carrier?) {
        PttSendManager.restart()

        aiRecorder = AudioRecorderAI(
            context = DataManager.context,
            chunkDurationMs = 500,
            filesDirProvider = { file }
        ).apply {
            onChunkReady = { pcmArray, _ ->
                PttSendManager.addNewFrame(pcmArray, file, carrier, receiverId = receiverId, chatId = chatId)
            }
            onPartialFinalChunk = { pcmArray, _ ->
                PttSendManager.addNewFrame(pcmArray, file, carrier, receiverId = receiverId, chatId = chatId)
            }
            onError = { throwable ->
                Log.d("AudioRecorder", "error $throwable")
                stop()
            }
            onStateChanged = { recording ->
                Log.d(LOG_TAG, "Recording state changed: $recording")
                if (!recording) finishAIRecording(DataManager.context)
            }
            start()
        }

        Log.d("AudioRecorder", "AudioRecorderAI started")
    }
    private fun finishAIRecording(context: Context) {
        Scopes.getDefaultCoroutine().launch {
            delay(3000)
            PttSendManager.finish(context)
        }
    }

    // ----------------------------------------
    // Stop Recording
    // ----------------------------------------
    fun stopRecording(
        receiverId: String,
        carrier: Carrier?,
        codeType: AudioEncoderType,
        file: File
    ) {
        Log.d("AudioRecorder", "Stop recording")

        if (codeType == AudioEncoderType.CODEC2) stopCodec2Recording(receiverId, carrier, file)
        else stopAIRecording()

        Scopes.getMainCoroutine().launch {
            delay(300)
            canRecord.value = true
        }
    }

    private fun stopCodec2Recording(receiverID: String, carrier: Carrier?, file: File) {
        wavRecorder?.run {
            stopRecording(
                context = context,
                retry = 0,
                receiverId = receiverID,
                path = file.absolutePath,
                carrier = carrier,
                chatId = TODO()
            )
            Scopes.getDefaultCoroutine().launch {
                delay(50)
                wavRecorder = null
            }
        }
    }

    private fun stopAIRecording() {
        Log.d("AudioRecorder", "Stop AI Recording")
        aiRecorder?.stop()
        Scopes.getDefaultCoroutine().launch {
            delay(50)
            aiRecorder = null
        }
    }

    enum class CodecValues(val mode : Int,val sampleRate: Int, val charNumOutput : Int){
        MODE700(Codec2.CODEC2_MODE_700C , 4400 , 4 ),
        MODE2400(Codec2.CODEC2_MODE_2400 , 6000 , 8),
        MODE1600(Codec2.CODEC2_MODE_1600 , 6000 , 8),
        MODE3200(Codec2.CODEC2_MODE_3200 , 8000 , 8)
    }
    private fun createFile(fileDir: String, chatID: String, receiverId: String) : File?{
        try{
            ts = System.currentTimeMillis()
            val directory = File("$fileDir/$chatID")
            val newFile = File("$fileDir/$chatID/$ts-$receiverId.pcm")
            if(!directory.exists()){
                directory.mkdir()
            }
            if(!newFile.exists()){
                newFile.createNewFile()

            }
            return newFile
        }catch (e : Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun createFileWav(context: Context, chatID: String, userId: String) : File{
        ts = System.currentTimeMillis()
        val directory = File("${context.filesDir}/$chatID")
        val newFile = File("${context.filesDir}/$chatID/$ts-$userId.wav")
        if(!directory.exists()){
            directory.mkdir()
        }
        if(!newFile.exists()){
            newFile.createNewFile()

        }
        return newFile
    }

    enum class AudioEncoderType(
        val id: Int,
        val title: String,
    ) {
        AI( 1, "Neural Audio Encoder (NAE)"),
        CODEC2( 0, "Classic Codec Encoder");

        fun toEncoderType(): EncoderType = when(this) {
            AI -> EncoderType.AI
            CODEC2 -> EncoderType.CODEC2
        }

        companion object {
            fun fromId(id: Int): AudioEncoderType? =
                entries.firstOrNull { it.id == id }

            fun fromIdOrDefault(id: Int, default: AudioEncoderType = CODEC2): AudioEncoderType =
                fromId(id) ?: default

            fun fromEncoderType(type: EncoderType): AudioEncoderType =
                when(type) {
                    EncoderType.CODEC2 -> CODEC2
                    EncoderType.AI -> AI
                }

        }
    }
}