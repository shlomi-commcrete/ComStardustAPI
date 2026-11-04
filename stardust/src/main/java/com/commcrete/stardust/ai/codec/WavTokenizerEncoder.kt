package com.commcrete.stardust.ai.codec

import com.commcrete.stardust.util.LogOnce
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.StardustLog
import com.commcrete.stardust.room.StardustStorage
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.pytorch.*
import java.io.File
import java.io.FileOutputStream
import kotlin.time.measureTime

class WavTokenizerEncoder() {
    private val TAG = "WavTokenizerEncoder"
    private val SAMPLE_RATE = 24000
    private val EXPECTED_SAMPLES = 12000 // 0.5 seconds at
    private val TOKENS_PER_SECOND = 40  // 40 tokens for 1 second of audio
    private val SAMPLES_PER_TOKEN = SAMPLE_RATE / TOKENS_PER_SECOND // 600 samples per token

    /** Per-chunk failure lines log once per recording; [startLogRun] summarises and resets them. */
    private val logOnce = LogOnce()

    /** The one [logOnce] key whose first line is an e; the other is w. */
    private val KEY_ENCODE_FAILED = "encode failed"

    /**
     * Starts a new log run where a recording starts: summarises the per-chunk failures repeated since
     * the last run, each at its first line's level, then forgets them. Recordings share this encoder.
     */
    internal fun startLogRun() {
        for ((key, repeats) in logOnce.reset()) {
            if (key == KEY_ENCODE_FAILED) StardustLog.e(TAG, "$key x$repeats more")
            else StardustLog.w(LogType.PTT, TAG, "$key x$repeats more")
        }
    }

    private val module: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StardustLog.d(LogType.PTT, TAG, "Loading WavTokenizerEncoder model")
        val modelAssetName = "wav_to_codes_large_android.ptl"
        LiteModuleLoader.load(assetFilePath(modelAssetName))
    }





//    private val moduleEnglish: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
//        Log.d(TAG, "Loading WavTokenizerEncoder model")
//        val modelAssetName = "wav_to_codes_large_android.ptl"
//        LiteModuleLoader.load(assetFilePath(context, pluginContext, modelAssetName))
//    }
    init {
        // If your model is in assets: just put the asset name here.
    }
//
//    fun encode(audioSamples: ShortArray): LongArray {
//        if (audioSamples.size > EXPECTED_SAMPLES) {
//            throw IllegalArgumentException("Expected not more than 12,000 samples, got ${audioSamples.size}")
//        }
//        Log.d(TAG, "Encoding ${audioSamples.size} audio samples")
//        val selectedModule = getSelectedModule()
//
//        // Convert ShortArray to FloatArray in range [-1.0, 1.0]
//        val floatSamples = shortArrayToFloatArray(audioSamples)
//
//        Log.d(TAG, "First 5 float samples before padding: ${floatSamples.take(5)}")
//        // Ensure exactly 12,000 samples by padding with zeros or trimming
//        val paddedSamples = padOrTrim(floatSamples)
//        Log.d(TAG, "First 5 float samples after padding: ${paddedSamples.take(5)}")
//        // Create samples to input tensor
//        val inputTensor = Tensor.fromBlob(paddedSamples, longArrayOf(1, paddedSamples.size.toLong()))
//        Log.d(TAG, "Input tensor shape: ${inputTensor.shape().joinToString(",")}")
//        // Run the model, the result are the tokens
//        var moduleOutputTensor: Tensor?
//        val duration = measureTime {
//            moduleOutputTensor = selectedModule.forward(IValue.from(inputTensor)).toTensor()
//        }
//        Log.d(TAG, "Encoding took $duration")
//
//        val outputData = moduleOutputTensor!!.dataAsLongArray
//        val tokens = trimToExpectedTokens(outputData, audioSamples.size)
//
//        return tokens
//    }

    fun encode(audioSamples: ShortArray): LongArray {
        val safeInput = when {
            audioSamples.size > EXPECTED_SAMPLES -> {
                // Logs once per recording; summarised at the next recording's startLogRun().
                if (logOnce.first("encode input larger than expected")) {
                    StardustLog.w(LogType.PTT, TAG, "encode input larger than expected (${audioSamples.size} > $EXPECTED_SAMPLES); trimming")
                }
                audioSamples.copyOf(EXPECTED_SAMPLES)
            }
            else -> audioSamples
        }

        return runCatching {
            // Per-chunk: keep at d.
            StardustLog.d(LogType.PTT, TAG) { "Encoding ${safeInput.size} audio samples" }
            val selectedModule = getSelectedModule()

            // Convert ShortArray to FloatArray in range [-1.0, 1.0]
            val floatSamples = shortArrayToFloatArray(safeInput)

            // Ensure exactly 12,000 samples by padding with zeros or trimming
            val paddedSamples = padOrTrim(floatSamples)
            val inputTensor = Tensor.fromBlob(paddedSamples, longArrayOf(1, paddedSamples.size.toLong()))
            // Per-chunk: keep at d.
            StardustLog.d(LogType.PTT, TAG) { "Input tensor shape: ${inputTensor.shape().joinToString(",")}" }

            val moduleOutputTensor: Tensor
            val duration = measureTime {
                moduleOutputTensor = selectedModule.forward(IValue.from(inputTensor)).toTensor()
            }
            // Per-chunk: keep at d.
            StardustLog.d(LogType.PTT, TAG) { "Encoding took $duration" }

            val outputData = moduleOutputTensor.dataAsLongArray
            val tokens = trimToExpectedTokens(outputData, safeInput.size)
            if (tokens.isNotEmpty()) tokens else fallbackTokens(safeInput.size)
        }.getOrElse { t ->
            // Logs once per recording; summarised at the next recording's startLogRun().
            if (logOnce.first(KEY_ENCODE_FAILED)) {
                StardustLog.e(TAG, "encode failed; returning fallback tokens", t)
            }
            fallbackTokens(safeInput.size)
        }
    }

    /**
     * Fallback token sequence used when encode fails. Values are 12-bit-safe
     * (0..4095), deterministic and sized to preserve approximate timing.
     */
    private fun fallbackTokens(audioSamplesSize: Int): LongArray {
        val expected = (audioSamplesSize / SAMPLES_PER_TOKEN)
            .coerceAtLeast(1)
            .coerceAtMost(EXPECTED_SAMPLES / SAMPLES_PER_TOKEN)
        return LongArray(expected) { 0L }
    }

    fun shortArrayToFloatArray(shortArray: ShortArray): FloatArray {
        return FloatArray(shortArray.size) { i ->
            shortArray[i] / 32768f
        }
    }

    fun trimToExpectedTokens(outputData: LongArray, audioSamplesSize: Int): LongArray {
        val expectedTokens = audioSamplesSize / SAMPLES_PER_TOKEN
        return if (outputData.size > expectedTokens) {
            // Per-chunk: keep at d.
            StardustLog.d(LogType.PTT, TAG) { "Trimming output from ${outputData.size} tokens to $expectedTokens tokens" }
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

    private fun assetFilePath(assetName: String): String {
        val outFile = File(StardustStorage.internalDir("models"), assetName)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        if(!outFile.exists()) {
            outFile.createNewFile()
        }
        DataManager.pluginContext?.assets?.open(assetName)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        StardustLog.d(LogType.PTT, TAG, "Model file path: ${outFile.absolutePath}")
        return outFile.absolutePath
    }


    fun getSelectedModule(): Module {
//        val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(context)
//        return if (modelTypeSelected == WavTokenizerDecoder.ModelType.English) {
//            moduleEnglish
//        } else {
//            Log.d(TAG, "Model WavTokenizerDecoder.ModelType.General selected, using 500ms module")
//            module
//        }
        return module
    }

    fun isWindowModel(): Boolean {
        val modelTypeSelected = SharedPreferencesUtil.getAudioModelType()
        return modelTypeSelected == WavTokenizerDecoder.ModelType.English
    }

    fun initModule(): Job = initJob

    /**
     * Cached, single-shot init job. The first call to [initModule] triggers
     * the lazy → launches one coroutine that touches [module] (which is
     * itself `by lazy(SYNCHRONIZED)` so the underlying PyTorch model is
     * loaded exactly once). Every subsequent call returns the same [Job],
     * so `.join()` from multiple sites is safe and only waits for the
     * single load to finish.
     */
    private val initJob: Job by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Scopes.getDefaultCoroutine().launch {
            StardustLog.d(LogType.PTT, TAG, "WavTokenizerEncoder module initializing")
            module
            StardustLog.i(LogType.PTT, TAG, "WavTokenizerEncoder model loaded successfully")
        }
    }
}
