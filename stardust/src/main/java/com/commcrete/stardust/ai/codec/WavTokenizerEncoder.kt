package com.commcrete.aiaudio.codecs

import android.content.Context
import android.util.Log
import org.pytorch.*
import java.io.File
import java.io.FileOutputStream
import kotlin.time.measureTime

class WavTokenizerEncoder(context: Context, pluginContext: Context) {
    private val TAG = "WavTokenizerEncoder"
    private val SAMPLE_RATE = 24000
    private val EXPECTED_SAMPLES = 12000 // 0.5 seconds at
    private val TOKENS_PER_SECOND = 40  // 40 tokens for 1 second of audio
    private val SAMPLES_PER_TOKEN = SAMPLE_RATE / TOKENS_PER_SECOND // 600 samples per token

    private val module: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Log.d(TAG, "Loading WavTokenizerEncoder model")
        val modelAssetName = "wav_to_codes_large_android.ptl"
        LiteModuleLoader.load(assetFilePath(context, pluginContext, modelAssetName))
    }
    init {
        // If your model is in assets: just put the asset name here.
    }

    fun encode(audioSamples: ShortArray): LongArray {
        if (audioSamples.size > EXPECTED_SAMPLES) {
            throw IllegalArgumentException("Expected not more than 12,000 samples, got ${audioSamples.size}")
        }
        Log.d(TAG, "Encoding ${audioSamples.size} audio samples")

        // Convert ShortArray to FloatArray in range [-1.0, 1.0]
        val floatSamples = shortArrayToFloatArray(audioSamples)

        Log.d(TAG, "First 5 float samples before padding: ${floatSamples.take(5)}")
        // Ensure exactly 12,000 samples by padding with zeros or trimming
        val paddedSamples = padOrTrim(floatSamples)
        Log.d(TAG, "First 5 float samples after padding: ${paddedSamples.take(5)}")
        // Create samples to input tensor
        val inputTensor = Tensor.fromBlob(paddedSamples, longArrayOf(1, paddedSamples.size.toLong()))
        Log.d(TAG, "Input tensor shape: ${inputTensor.shape().joinToString(",")}")
        // Run the model, the result are the tokens
        var moduleOutputTensor: Tensor?
        val duration = measureTime {
            moduleOutputTensor = module.forward(IValue.from(inputTensor)).toTensor()
        }
        Log.d(TAG, "Encoding took $duration")

        val outputData = moduleOutputTensor!!.dataAsLongArray
        val tokens = trimToExpectedTokens(outputData, audioSamples.size)

        return tokens
    }

    fun shortArrayToFloatArray(shortArray: ShortArray): FloatArray {
        return FloatArray(shortArray.size) { i ->
            shortArray[i] / 32768f
        }
    }

    fun trimToExpectedTokens(outputData: LongArray, audioSamplesSize: Int): LongArray {
        val expectedTokens = audioSamplesSize / SAMPLES_PER_TOKEN
        return if (outputData.size > expectedTokens) {
            Log.d(TAG, "Trimming output from ${outputData.size} tokens to $expectedTokens tokens")
            outputData.dropLast(outputData.size - expectedTokens).toLongArray()
        } else {
            outputData
        }
    }

    private fun padOrTrim(audio: FloatArray): FloatArray {
        return when {
            audio.size == EXPECTED_SAMPLES -> audio
            audio.size > EXPECTED_SAMPLES -> audio.sliceArray(0 until EXPECTED_SAMPLES)
            else -> FloatArray(EXPECTED_SAMPLES).also { System.arraycopy(audio, 0, it, 0, audio.size) }
        }
    }

    private fun assetFilePath(context: Context, pluginContext: Context, assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        if(!outFile.exists()) {
            outFile.createNewFile()
        }
        pluginContext.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Model file path: ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    fun initModule() {
        Log.d(TAG, "WavTokenizerEncoder module initialized")
        module
        Log.d(TAG, "WavTokenizerEncoder model loaded successfully")
    }
}
