package com.commcrete.stardust.ai.codec

import android.content.Context
import android.util.Log
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.codecs.WavTokenizerEncoder
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AIModuleInitializer {
    lateinit var wavTokenizerEncoder: WavTokenizerEncoder
    lateinit var wavTokenizerDecoder: WavTokenizerDecoder
    var aiEnabled = false
    private val TAG = "AIModuleInitializer"


    fun initModules (context: Context) {
        Scopes.getDefaultCoroutine().launch {
            init(context)
            delay(1000)
            initModules()
            delay(1000)
            PttSendManager.init(context)
            delay(1000)
            PttReceiveManager.init()
            delay(2000)
//            val outFile = File(context.cacheDir, "ptt_test_israel_number1.wav")
//            AIDecoderTest.sendAssetWavToPTTAs500msFrames(
//                context,
//                outputFile = outFile
//            )

//            AIDecoderTest.testTokens(context, "Vocal__tokens.txt")

        }
    }

    fun initModules () {
        Scopes.getDefaultCoroutine().launch {
            if(::wavTokenizerEncoder.isInitialized) {
                wavTokenizerEncoder.initModule()
            }
            delay(1000)
            if(::wavTokenizerDecoder.isInitialized) {
                wavTokenizerDecoder.initModule()
            }
        }
    }

    fun init(context: Context) {
        aiEnabled = PyTorchInitGate.isPrimaryInitializer(context)
        if (!aiEnabled) {
            Log.d(TAG, "AI Codec not enabled for this process.")
            // IMPORTANT: do NOT instantiate or reference any org.pytorch.* here
            return
        }
        Scopes.getDefaultCoroutine().launch {

            if(!::wavTokenizerEncoder.isInitialized) {
                wavTokenizerEncoder = WavTokenizerEncoder(context, context)
            }
            delay(1000)
            if(!::wavTokenizerDecoder.isInitialized) {
                wavTokenizerDecoder = WavTokenizerDecoder(context, context)
            }
        }
    }
}