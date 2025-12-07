package com.commcrete.stardust.util.audio

import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ai.codec.PttSendManager
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.example.chunkrecorder.AudioRecorderAI
import com.google.android.gms.common.api.Scope
import com.ustadmobile.codec2.Codec2
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File


object RecorderUtils {

    var file : File? = null
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

    @RequiresPermission(RECORD_AUDIO)
    fun onRecord(start: Boolean, destination : String, carrier: Carrier?, codeType: CODE_TYPE? = CODE_TYPE.CODEC2) = if (start) {
        Log.d("AudioRecorder", "onRecord $start")
        if(codeType == CODE_TYPE.CODEC2) {
            //Works with computer codec2
//            wavRecorder?.kill()
            Scopes.getMainCoroutine().launch {
                canRecord.value = false
            }
            wavRecorder = WavRecorder(DataManager.context, pttInterface)
            DataManager.getSource().let {
                file = createFile(DataManager.fileLocation, destination, it)
            }
            wavRecorder?.startRecording(file?.absolutePath?:"", destination, carrier)
        } else {
            Scopes.getMainCoroutine().launch {
                canRecord.value = false
            }
            Log.d("AudioRecorder", "AI Enhanced Recording Started")
            PttSendManager.init(DataManager.context,  pttInterface)
            Log.d("AudioRecorder", "PttSendManager.init")
            DataManager.getSource().let {
                file = createFile(DataManager.fileLocation, destination, it)
            }
            Log.d("AudioRecorder", "File Created")
            PttSendManager.restart()
            file?.let {
                aiRecorder = AudioRecorderAI(
                    context = DataManager.context,
                    chunkDurationMs = 500,
                    filesDirProvider = { it},
                )
                Log.d("AudioRecorder", "AudioRecorderAI Created")
                Log.d("AudioRecorder", "start ${aiRecorder}")
                aiRecorder?.onChunkReady = { pcmArray, chunkIndex ->
                    PttSendManager.addNewFrame(pcmArray, it, carrier, destination)
                }

                aiRecorder?.onPartialFinalChunk = { pcmArray, chunkIndex ->
                    PttSendManager.addNewFrame(pcmArray, it, carrier, destination)
                }

                aiRecorder?.onError = { throwable ->
                    Log.d("AudioRecorder", "error ${throwable}")
                    aiRecorder?.stop()
                }
                aiRecorder?.onStateChanged = { recording ->
                    Log.d(LOG_TAG, "Recording state changed: $recording")
                    if(!recording) {
                        Scopes.getDefaultCoroutine().launch {
                            delay(3000)
                            PttSendManager.finish()
                        }
                    }
                }
                Log.d("AudioRecorder", "AudioRecorderAI Start")
                aiRecorder?.start()
            }
        }


    } else {
        Log.d("AudioRecorder", "onRecord $start")
        if(codeType == CODE_TYPE.CODEC2) {
            wavRecorder?.stopRecording(retry = 0,destination, file?.absolutePath?:"", DataManager.context, carrier)
            Scopes.getDefaultCoroutine().launch {
                delay (50)
                wavRecorder = null
            }
        } else {
            Log.d("AudioRecorder", "stop AI")
            aiRecorder?.stop()
            Scopes.getDefaultCoroutine().launch {
                delay (50)
                aiRecorder = null
            }

        }
            Scopes.getMainCoroutine().launch {
                delay(300)
                canRecord.value = true
            }


    }

    enum class CodecValues(val mode : Int,val sampleRate: Int, val charNumOutput : Int){
        MODE700(Codec2.CODEC2_MODE_700C , 4400 , 4 ),
        MODE2400(Codec2.CODEC2_MODE_2400 , 6000 , 8),
        MODE1600(Codec2.CODEC2_MODE_1600 , 6000 , 8),
        MODE3200(Codec2.CODEC2_MODE_3200 , 8000 , 8)
    }
    private fun createFile(context: String, chatID: String, userId: String) : File?{
        try{
            ts = System.currentTimeMillis()
            val directory = File("$context/$chatID")
            val newFile = File("$context/$chatID/$ts-$userId.pcm")
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

    enum class CODE_TYPE (val id : Int, val codecName: String){
        AI(1, "AI Enhanced"), CODEC2(0, "Default")
    }
}