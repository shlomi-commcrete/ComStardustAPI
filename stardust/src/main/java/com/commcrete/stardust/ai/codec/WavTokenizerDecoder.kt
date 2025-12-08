package com.commcrete.aiaudio.codecs

import android.content.Context
import android.util.Log
import com.commcrete.stardust.util.SharedPreferencesUtil
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.measureTime


class WavTokenizerDecoder(val context: Context, pluginContext: Context) {
    private val TAG = "WavTokenizerDecoder"

    private var index = 0
    private var cutTokens = 0

    private var loop = 0
    val outFile = File(context.cacheDir, "decoded_data.txt")
    val listEnergy = mutableListOf<String>()
    private val module: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Log.d(TAG, "Loading WavTokenizerDecoder model")
        val modelAssetName = "codes_to_wav_large_android.ptl"
        LiteModuleLoader.load(assetFilePath(context, pluginContext, modelAssetName))
    }

    private val moduleEnglish: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Log.d(TAG, "Loading WavTokenizerEncoder model")
        val modelAssetName = "wav_to_codes_large_android.ptl"
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

        val decodeType = SharedPreferencesUtil.getAudioDecodeType(context)
        val combinedData = if (previousTokens != null) {
            previousTokens + data
        } else {
            data
        }

        // Add 3 fake values at the end
        val fixedData = combinedData + listOf(0, 0, 0, 0)

        val codes = convertLongListToTensor(fixedData)

        var output: Tensor?
        val duration = measureTime {
            output = module.forward(IValue.from(codes)).toTensor()
        }
        //        Log.d(TAG, "Decoding took $duration")

        // Remove the last 2400 samples
        var audioData = output!!.dataAsFloatArray.dropLast(2400).toFloatArray()

        //get index
        // to cut after
        //slice previous tokens

//        val tensor = Tensor
        // Return only the new part after alignment
        if(decodeType == DecodeMode.Smart || decodeType == DecodeMode.Combined) {
            handleSmart(previousTokens, audioData, fixedData, output)
        }

        // Apply audio normalization if we have previous data
        // 🔹 Apply crossfade with previousSamples to remove "ticks"

        var unalignedAudio: ShortArray
        if(decodeType == DecodeMode.Aligned || decodeType == DecodeMode.Combined) {
            val alignedAudioData = if (previousSamples != null) {
                fixAudioAlignment2(shortArrayToFloatArray(previousSamples), audioData)
            } else {
                audioData
            }
            unalignedAudio = floatArrayToPcm(alignedAudioData)
        } else {
            unalignedAudio = floatArrayToPcm(audioData)
        }




        Log.d(TAG, "decode get size ${data.size } return ${unalignedAudio.size}")
        loop ++
        return unalignedAudio
    }

    fun shortArrayToFloatArray(input: ShortArray): FloatArray {
        return FloatArray(input.size) { i ->
            input[i] / 32767f   // normalize to -1.0..1.0
        }
    }

    fun handleSmart(
        previousTokens: List<Long>?,
        audioData: FloatArray,
        fixedData: List<Long>,
        output: Tensor?
    ): FloatArray {

        var trimmed = audioData   // <-- local mutable copy

        if (previousTokens != null) {
            Log.d(TAG, "last Index $index")
            val cutFrom = (20 - cutTokens) * 600
            Log.d(TAG, "cutFrom $cutFrom")

            if (cutFrom in 0 until trimmed.size) {
                trimmed = trimmed.sliceArray(cutFrom until trimmed.size)
                Log.d(TAG, "Trimmed head → new size = ${trimmed.size}")
            } else {
                Log.w(TAG, "cutFrom out of range → no trimming")
            }
        }

        Log.d(TAG, "audioData.size ${trimmed.size}")

        val bestBoundaryToken = findLowestEnergyTokenBoundary(trimmed)

        saveDecodedData(output!!.dataAsFloatArray, fixedData)

        index = bestBoundaryToken
        val totalTokens = trimmed.size / 600
        cutTokens = (totalTokens - index)
        Log.d(TAG, "cutTokens $cutTokens")

        // Trim AFTER boundary
        val cutoff = index * 600
        if (cutoff in 1 until trimmed.size) {
            trimmed = trimmed.sliceArray(0 until cutoff)
            Log.d(TAG, "Trimmed audioData to boundary → new size = ${trimmed.size}")
        }

        return trimmed
    }

    fun fixAudioAlignment2(
        previousSamples: FloatArray,
        currentChunk: FloatArray
    ): FloatArray {
        val numSamples = 400
        val lastIndex = previousSamples.size - 1

        for (i in 0 until numSamples) {
            val diff =
                (previousSamples[lastIndex - i] - currentChunk[i]) *
                        ((numSamples - i).toFloat() / numSamples.toFloat())

            currentChunk[i] += diff
        }

        return currentChunk
    }

    private fun floatArrayToTensor(audio: FloatArray): Tensor {
        val shape = longArrayOf(1, audio.size.toLong())   // [batch, samples]
        return Tensor.fromBlob(audio, shape)
    }

    fun findLowestEnergyTokenBoundary(
        chunk: FloatArray,
        numTokensToCheck: Int = 4,
        samplesPerToken: Int = 600
    ): Int {
        listEnergy.clear()
        val totalSamples = chunk.size

        // If 1D, treat as batch=1
        val samples = chunk

        val totalTokens = totalSamples / samplesPerToken
        var tokensToCheck = minOf(numTokensToCheck, totalTokens - 1)

        if (tokensToCheck < 1) {
            Log.d(TAG, "Warning: Not enough tokens to check boundaries")
            return totalTokens - 1
        }

//        Log.d(TAG, "\nAnalyzing $tokensToCheck token boundaries:")
        Log.d(TAG, "Total tokens in chunk: $totalTokens")
//        Log.d(TAG, "Checking last $tokensToCheck tokens\n")

        val energies = mutableListOf<Float>()
        val tokenIndices = mutableListOf<Int>()

        for (i in 0 until tokensToCheck) {

            // Token index from the end
            val tokenIdx = totalTokens - tokensToCheck + i

            val boundarySample = tokenIdx * samplesPerToken
            val startSample = boundarySample - 100
            val endSample = boundarySample + 100

            // Extract window safely
            val safeStart = maxOf(startSample, 0)
            val safeEnd = minOf(endSample, totalSamples)

            var energy = 0f
            for (s in safeStart until safeEnd) {
                val v = samples[s]
                energy += abs(v)
            }

            energies.add(energy)
            tokenIndices.add(tokenIdx)

            val text = "Token %3d boundary (samples %6d to %6d): Energy = %.6f"
                .format(tokenIdx, startSample, endSample, energy)
            Log.d(TAG,
                text
            )
            listEnergy.add(text)
        }

        // Find lowest-energy token
        val minIndex = energies.indexOf(energies.minOrNull()!!)
        val bestToken = tokenIndices[minIndex]

        Log.d(TAG, "\n✓ Lowest energy found at token $bestToken with energy %.6f".format(energies[minIndex]))

        return bestToken
    }

    fun crossfadePcmReturnOnlyNext(
        prev: ShortArray,
        next: ShortArray,
        fadeMs: Int,
        sampleRate: Int
    ): ShortArray {
        val fadeSamples = (sampleRate * fadeMs / 1000.0).toInt()
        val fadeLen = minOf(fadeSamples, prev.size, next.size)
        if (fadeLen <= 0) return next

        val mixed = ShortArray(fadeLen)
        val startPrev = prev.size - fadeLen

        for (i in 0 until fadeLen) {
            val prevSample = prev[startPrev + i].toInt()
            val nextSample = next[i].toInt()

            val fadeOut = (fadeLen - i).toFloat() / fadeLen
            val fadeIn = 1f - fadeOut

            val mixedSample = (prevSample * fadeOut + nextSample * fadeIn)
                .toInt().coerceIn(-32768, 32767)
            mixed[i] = mixedSample.toShort()
        }

        // result = blended head + remainder of next
        val tailNext = next.copyOfRange(fadeLen, next.size)
        val result = ShortArray(mixed.size + tailNext.size)
        System.arraycopy(mixed, 0, result, 0, mixed.size)
        System.arraycopy(tailNext, 0, result, mixed.size, tailNext.size)

        return result
    }

    fun crossfadePcm(prev: ShortArray, next: ShortArray, fadeMs: Int, sampleRate: Int): ShortArray {
        val fadeSamples = (sampleRate * fadeMs / 1000.0).toInt()
        val fadeLen = minOf(fadeSamples, prev.size, next.size)
        if (fadeLen <= 0) return prev + next

        val mixed = ShortArray(fadeLen)
        val startPrev = prev.size - fadeLen

        for (i in 0 until fadeLen) {
            val prevSample = prev[startPrev + i].toInt()
            val nextSample = next[i].toInt()

            val fadeOut = (fadeLen - i).toFloat() / fadeLen
            val fadeIn = 1f - fadeOut

            val mixedSample = (prevSample * fadeOut + nextSample * fadeIn)
                .toInt().coerceIn(-32768, 32767)
            mixed[i] = mixedSample.toShort()
        }

        // combine: prev (without its tail) + mixed + rest of next
        val tailNext = next.copyOfRange(fadeLen, next.size)
        val merged = ShortArray(prev.size - fadeLen + mixed.size + tailNext.size)
        var idx = 0
        System.arraycopy(prev, 0, merged, idx, prev.size - fadeLen)
        idx += prev.size - fadeLen
        System.arraycopy(mixed, 0, merged, idx, mixed.size)
        idx += mixed.size
        System.arraycopy(tailNext, 0, merged, idx, tailNext.size)

        return merged
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
        val out = ShortArray(floatArray.size)
        for (i in floatArray.indices) {
            val v = floatArray[i].coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().toShort()
        }
        return out
    }

    private fun saveDecodedData (byteArray: FloatArray, fixedData: List<Long>, ) {
        try {
            FileOutputStream(outFile, true).use { fos ->
                // Write each int16 value on its own line
                val sb = StringBuilder()
                sb.append("\n").append("Loop number $loop").append("\n").append("\n").append("\n").append("------------------------").append("\n")
                sb.append("\n").append("Tokens $loop").append("\n").append("\n").append("\n").append("------------------------").append("\n")
                for (sample in fixedData) {
                    sb.append(sample.toInt()).append("\n")
                }
                sb.append("\n").append("Energy $loop").append("\n").append("\n").append("\n").append("------------------------").append("\n")
                for (energy in listEnergy) {
                    sb.append(energy).append("\n")
                }
                sb.append("\n").append("Data $loop").append("\n").append("\n").append("\n").append("------------------------").append("\n")
                for (sample in byteArray) {
                    sb.append(sample).append("\n")
                }
                fos.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            Log.e("WavHelper", "Finished writing RAW TXT file to ${outFile.absolutePath}")
        } catch (e: IOException) {
            Log.e("WavHelper", "Error writing raw txt file", e)
        }

    }

    enum class DecodeMode {
        Aligned,
        Smart,
        Combined
    }

    enum class ModelType {
        General,
        English
    }

    fun initModule() {
        Log.d(TAG, "WavTokenizerDecoder module initialized")
        module
//        moduleEnglish
        Log.d(TAG, "WavTokenizerDecoder model loaded successfully")
    }
}