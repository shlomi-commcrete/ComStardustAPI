package com.commcrete.aiaudio.codecs

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import kotlin.time.measureTime
import kotlin.math.max
import kotlin.math.min

class WavTokenizerDecoder(context: Context, pluginContext: Context) {
    private val TAG = "WavTokenizerDecoder"

    private val module: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Log.d(TAG, "Loading WavTokenizerDecoder model")
        val modelAssetName = "codes_to_wav_large_android.ptl"
        LiteModuleLoader.load(assetFilePath(context, pluginContext, modelAssetName))
    }
    init {
        // If your model is in assets: just put the asset name here.
    }

    // Decode the list of Long tokens into PCM ShortArray
    // If previousData is provided, it will be used for audio normalization (take the last
    // samples from previous decode to align the new audio)
    // also add 3 fake tokens at the end to help the model
    fun decode(data: List<Long>, previousTokens: List<Long>? = null, previousSamples: ShortArray? = null) : ShortArray {
        // Combine previous data with current data if previous data exists
        val combinedData = if (previousTokens != null) {
            previousTokens + data
        } else {
            data
        }

        // Add 3 fake values at the end
        val fixedData = combinedData + listOf(0, 0, 0)

        val codes = convertLongListToTensor(fixedData)

        var output: Tensor?
        val duration = measureTime {
            output = module.forward(IValue.from(codes)).toTensor()
        }
        Log.d(TAG, "Decoding took $duration")

        // Remove the last 1800 samples
        var audioData = output!!.dataAsFloatArray.dropLast(1800).toFloatArray()

        // Return only the new part after alignment
        if (previousTokens != null) {
            val sliceIndexStart = previousTokens.size * 600
            audioData = audioData.sliceArray(sliceIndexStart until audioData.size)
        }

        val unalignedAudio = floatArrayToPcm(audioData)

        // Apply audio normalization if we have previous data
        val alignedAudioData = if (previousSamples != null) {
            applyFades(unalignedAudio)
        } else {
            unalignedAudio
        }

        Log.d(TAG, "decode get size ${data.size } return ${alignedAudioData.size}")
        return alignedAudioData
    }

    private fun alignAudio(currentSamples: ShortArray, previousSamples: ShortArray): ShortArray {
        if (currentSamples.size <= 20) return currentSamples

        var normalizedData = currentSamples.copyOf()
        val numSamples = 20

        // Get the reference value at sample 12000
        val referenceValue = previousSamples[previousSamples.size - 1]

        // First adjustment: align sample at index 12001 with reference
        val firstDiff = (referenceValue - normalizedData[0]) * ((numSamples - 1).toFloat() / numSamples)
        normalizedData[0] = (normalizedData[0] + firstDiff.toInt().toShort()).toShort()

        // Progressive adjustment for the next 19 samples (12002 to 12020)
        for (i in 0 until numSamples - 1) {
            val nextIndex = i + 1
            val diff = (normalizedData[i] - normalizedData[nextIndex]) *
                       ((numSamples - i - 1).toFloat() / numSamples)
            normalizedData[nextIndex] = (normalizedData[nextIndex] + diff.toInt().toShort()).toShort()
        }

        return normalizedData
    }


    /**
     * Applies linear fade-in and fade-out to 16-bit PCM samples (ShortArray).
     *
     * @param samples      Audio buffer of 16-bit PCM values (−32768..32767)
     * @param fadeSamples  Number of samples for fade-in/out (default: 500)
     * @return The same ShortArray instance after applying fades
     */
    fun applyFades(samples: ShortArray, fadeSamples: Int = 500): ShortArray {
        if (samples.isEmpty() || fadeSamples <= 0) return samples

        val n = samples.size
        val f = min(fadeSamples, n)
        val denom = max(1, f - 1) // prevent divide by zero

        // --- Fade-in: from 0 → 1 ---
        for (i in 0 until f) {
            val gain = i.toFloat() / denom
            val faded = (samples[i] * gain).toInt()
            samples[i] = faded.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // --- Fade-out: from 1 → 0 ---
        val start = n - f
        for (i in 0 until f) {
            val gain = 1f - (i.toFloat() / denom)
            val idx = start + i
            val faded = (samples[idx] * gain).toInt()
            samples[idx] = faded.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return samples
    }


    private fun convertLongListToTensor(data: List<Long>): Tensor {
        require(data.isNotEmpty()) { "Input list must not be empty (PyTorch cannot create a tensor with a zero-sized last dimension in many models)." }
        val arr: LongArray = data.toLongArray()  // primitive array
        val shape = longArrayOf(1, 1, arr.size.toLong())  // [batch, channel, length] example
        return Tensor.fromBlob(arr, shape)
    }

    private fun assetFilePath(context: Context, pluginContext: Context, assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        if(!outFile.exists()) {
            outFile.createNewFile()
        }
        pluginContext.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile.absolutePath
    }

    private fun floatArrayToPcm(floatArray: FloatArray): ShortArray {
        val shortArray = ShortArray(floatArray.size)
        for (i in floatArray.indices) {
            // Clamp and convert to 16-bit
            val clampedValue = floatArray[i].coerceIn(-1.0f, 1.0f)
            shortArray[i] = (clampedValue * Short.MAX_VALUE + 0.5f).toInt().toShort()
        }
        return shortArray
    }

    fun initModule() {
        Log.d(TAG, "WavTokenizerDecoder module initialized")
        module
        Log.d(TAG, "WavTokenizerDecoder model loaded successfully")
    }
}