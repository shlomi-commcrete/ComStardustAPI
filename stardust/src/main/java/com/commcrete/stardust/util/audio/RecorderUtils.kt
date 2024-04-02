package com.commcrete.stardust.util.audio

import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import androidx.annotation.RequiresPermission
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.ustadmobile.codec2.Codec2
import java.io.File


object RecorderUtils {

    var file : File? = null
    var ts : Long = 0
    private val LOG_TAG = "AudioRecordTest"

    private var pttInterface : PttInterface? = null
    private var wavRecorder = WavRecorder(DataManager.context)


    fun init(pttInterface : PttInterface){
        RecorderUtils.pttInterface = pttInterface
    }

    fun onPTTTest(){
        wavRecorder = WavRecorder(DataManager.context, null)
        wavRecorder.sendAudioTest(DataManager.context)
    }

    @RequiresPermission(RECORD_AUDIO)
    fun onRecord(start: Boolean, destination : String) = if (start) {

        //Works with computer codec2
        wavRecorder = WavRecorder(DataManager.context, pttInterface)
        DataManager.getSource().let {
            file = createFile(DataManager.context, destination, it)
        }
//        createFileUncoded(App.application, destinations[0])
        file?.absolutePath?.let {
            wavRecorder.startRecording(it, destination)
        }
    } else {

        //Works with computer codec2
        file?.let {
            wavRecorder.stopRecording(destination, it.absolutePath, DataManager.context)
//            wavFilerecorder?.stopRecording()
        }

    }
    enum class CodecValues(val mode : Int,val sampleRate: Int, val charNumOutput : Int){
        MODE700(Codec2.CODEC2_MODE_700C , 4400 , 4 ),
        MODE2400(Codec2.CODEC2_MODE_2400 , 6000 , 8),
        MODE1600(Codec2.CODEC2_MODE_1600 , 6000 , 8),
        MODE3200(Codec2.CODEC2_MODE_3200 , 8000 , 8)
    }
    private fun createFile(context: Context, chatID: String, userId: String) : File{
        ts = System.currentTimeMillis()
        val directory = File("${context.filesDir}/$chatID")
        val newFile = File("${context.filesDir}/$chatID/$ts-$userId.pcm")
        if(!directory.exists()){
            directory.mkdir()
        }
        if(!newFile.exists()){
            newFile.createNewFile()

        }
        return newFile
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
}