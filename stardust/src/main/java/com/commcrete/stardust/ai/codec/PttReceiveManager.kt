package com.commcrete.stardust.ai.codec

import android.content.Context
import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.media.PcmStreamPlayer
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.context
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.AiPlayerUtils
import com.commcrete.stardust.util.audio.PTTCodecDecoderSession
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.audio.WavRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

object PttReceiveManager {
    private const val TAG = "PttManager"
    private const val BUFFERING_TIME_MS = 500L

    private var coroutineScope = CoroutineScope(Dispatchers.Default)
    private var decodingJob: Job? = null
    private var toDecodeQueue = Channel<AIDecodeData>(Channel.UNLIMITED)

    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder

    // ── AI inactivity timer ───────────────────────────────────────────────────────────────────────
    private val aiHandler = Handler(Looper.getMainLooper())
    private val aiTimeoutRunnable = Runnable {
        onAiReceiveTimeout()
        clearDecodeContext()
        PcmStreamPlayer.stop()
        AiPlayerUtils.onAiStreamReleased()
    }

    // ── Legacy Codec2 inactivity timer ────────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val pttScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runnable: Runnable = Runnable {
        Scopes.getMainCoroutine().launch {
            CoroutineScope(Dispatchers.IO).launch {
                val file = PTTCodecDecoderSession.fileToWrite
                file?.let {
                    val byteArray = PTTCodecDecoderSession.byteArrayOutputStream.toByteArray().copyOf()
                    PTTCodecDecoderSession.writePTTReceivedData(byteArray, it)
                }
                PTTCodecDecoderSession.fileToWrite = null
                PTTCodecDecoderSession.byteArrayOutputStream.reset()
                PTTCodecDecoderSession.numOfPackagesRecieved = 0
            }
            Handler(Looper.getMainLooper()).postDelayed({ PTTCodecDecoderSession.ts = "" }, 500)
            PTTCodecDecoderSession.ts = ""
            PTTCodecDecoderSession.isFileInit = false
            PcmStreamPlayer.releaseLegacyTrack()
            if (PTTCodecDecoderSession.numOfPackagesRecieved == 1) {
                PcmStreamPlayer.writeMinimumSilenceAndPlay(
                    PTTCodecDecoderSession.byteArrayOutputStream.toByteArray().copyOf()
                ) {
                    Timber.tag("WavRecorder.TAG_PTT_DEBUG").d("bufferSizeInFrames")
                }
                PTTCodecDecoderSession.byteArrayOutputStream.reset()
            }
            StardustPackageUtils.packageLiveData.value = null
            PTTCodecDecoderSession.mCodec2Decoder.rawAudioOutBytesBuffer.clear()
            Timber.tag(WavRecorder.TAG_PTT_DEBUG).d("rawAudioOutBytesBuffer.clear() runnable")
        }
    }

    // Variables to track last unpack and timestamp
    private var lastUnpack: List<Long>? = null
    private var lastDecodedSamples: ShortArray? = null
    private var lastUnpackTime: Long = 0L

    // ── Init ──────────────────────────────────────────────────────────────────────────────────────
    fun init() {
        startDecodingJob()
    }

    // ── Data classes ──────────────────────────────────────────────────────────────────────────────
    data class AIDecodeData(
        val data: ByteArray,
        val from: String = "",
        val source: String = "",
        val modelType: WavTokenizerDecoder.ModelType = WavTokenizerDecoder.ModelType.General,
        val onPcmReady: ((ShortArray) -> Unit)? = null
    )

    // ── Public API ────────────────────────────────────────────────────────────────────────────────
    fun addNewData(
        data: ByteArray,
        from: String,
        source: String? = null,
        modelType: WavTokenizerDecoder.ModelType? = null,
        onPcmReady: ((ShortArray) -> Unit)? = null
    ) {
        addNewData(
            AIDecodeData(
                data = data,
                from = from,
                source = source.orEmpty(),
                modelType = modelType ?: WavTokenizerDecoder.ModelType.General,
                onPcmReady = onPcmReady
            )
        )
    }

    fun addNewData(aiDecodeData: AIDecodeData) {
        toDecodeQueue.trySend(aiDecodeData)
    }

    /** Entry point for legacy Codec2 PTT packets. */
    fun handlePTTPackage(bittelPackage: StardustPackage) {
        getPackageByFrames(bittelPackage, bittelPackage.getDestAsString())
    }

    /** Entry point for AI PTT packets. */
    fun handleIncomingAIPttPackage(bittelPackage: StardustPackage) {
        coroutineScope.launch {
            val source = bittelPackage.getSourceAsString()
            if (source.isEmpty()) return@launch

            val destination = bittelPackage.getDestAsString()

            val packageToPass = StardustAPIPackage(source, destination)
            val realSource = packageToPass.getRealSourceId()
            val data = bittelPackage.data ?: return@launch
            val byteArray = AiPlayerUtils.intArrayToByteArray(data.toMutableList())

            Timber.tag(TAG).d("Received PTT AI data size: ${byteArray.size}")
            if (byteArray.size <= 1) return@launch

            val selectedModel = AiPlayerUtils.getModelFromValue(byteArray[0].toInt())
                ?: WavTokenizerDecoder.ModelType.General

            addNewData(
                AIDecodeData(
                    data = byteArray,
                    from = realSource,
                    source = source,
                    modelType = selectedModel
                )
            )
            DataManager.getCallbacks()?.receivePTT(packageToPass, byteArray)
        }
    }

    // ── Legacy Codec2 playback pipeline ───────────────────────────────────────────────────────────
    private fun getPackageByFrames(bittelPackage: StardustPackage, receiverID: String) {
        bittelPackage.data?.let { dataArray ->
            val byteArray = PTTCodecDecoderSession.intArrayToByteArray(dataArray.toMutableList())
            testPlayPackage(byteArray, bittelPackage.getSourceAsString(), receiverID)
        }
    }

    private fun testPlayPackage(byteArray: ByteArray, source: String, receiverID: String) {
        PcmStreamPlayer.ensureLegacyTrack(
            (14080 * PTTCodecDecoderSession.bufferSizeMulti).toInt(),
            PTTCodecDecoderSession.speedFactor
        )
        val bytes = PTTCodecDecoderSession.splitByteArray(byteArray, 7)
        val bytesListToPlay = mutableListOf<ByteArray>()
        for (mByte in bytes) {
            val decodedBytes = PTTCodecDecoderSession.handleBittelAudioMessage(mByte)
            if (!mByte.contentEquals(PTTCodecDecoderSession.embpyByte)) {
                bytesListToPlay.add(decodedBytes)
            }
        }
        val combined = PTTCodecDecoderSession.combine(bytesListToPlay)
        playAudio(context, combined, receiverID, source)
    }

    private fun playAudio(
        context: Context,
        pttAudio: ByteArray,
        receiverID: String,
        senderID: String,
        decoderType: RecorderUtils.CODE_TYPE
    ) {
        playPTT(pttAudio, pttAudio.size, senderID, receiverID)
        resetTimer()

        // TODO: Refactor CodecPlayerUtils to not rely on static state for ts and file initialization, as this can lead to issues if multiple PTT messages are received in quick succession. Consider using a more robust session management approach.
        PTTCodecDecoderSession.setTs()

        pttScope.launch {
            runCatching {
                if (!PTTCodecDecoderSession.isFileInit) {
                    val parsedDestination = receiverID.trim()
                        .replace("[\"", "").replace("\"]", "")
                    val packageToPass = StardustAPIPackage(senderID, parsedDestination)
                    PTTCodecDecoderSession.initPttInputFile(context, packageToPass, RecorderUtils.CODE_TYPE.CODEC2)
                }
                PTTCodecDecoderSession.byteArrayOutputStream.write(pttAudio)
            }.onFailure { it.printStackTrace() }
        }
    }

    private fun playPTT(audioStream: ByteArray, size: Int, source: String, destination: String) {
        PcmStreamPlayer.playLegacyStream(
            audioData = audioStream,
            bufferSizeInBytes = size,
            receivedPkgs = PTTCodecDecoderSession.numOfPackagesRecieved,
            playFromSdk = true
        )
        DataManager.getCallbacks()?.receivePTT(StardustAPIPackage(source, destination), audioStream)
    }

    private fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 2000)
    }

    // ── AI decode loop ────────────────────────────────────────────────────────────────────────────
    private fun startDecodingJob() {
        if (decodingJob?.isActive == true) return
        decodingJob = coroutineScope.launch {
            while (isActive) {
                try {
                    val aiDecodeData = toDecodeQueue.receiveCatching().getOrNull() ?: continue
                    Timber.tag(TAG).d("Data received of size: ${aiDecodeData.data.size}")
                    handleTokenizerChunk(aiDecodeData)
                } catch (e: MediaCodec.CodecException) {
                    Timber.tag(TAG).e(e, "Codec exception during decoding: ${e.diagnosticInfo}")
                    break
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in decoding loop: ${e.message}")
                    break
                }
            }
            Timber.tag(TAG).d("Decoding job finished.")
        }
    }

    private suspend fun handleTokenizerChunk(aiDecodeData: AIDecodeData) {
        val decodedData = aiDecodeData.data.sliceArray(1 until aiDecodeData.data.size)
        val unpack = BitPacking12.unpack12(decodedData)

        val currentTime = System.currentTimeMillis()
        val previousUnpack = lastUnpack?.takeIf { currentTime - lastUnpackTime < 2000 }
        val previousSample = lastDecodedSamples?.takeIf { currentTime - lastUnpackTime < 2000 }

        val finalPcmData = wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, aiDecodeData.modelType)
        Timber.tag(TAG).d("Decoded tokenizer unpack size ${unpack.size}, PCM data: ${finalPcmData.size} samples")

        lastUnpack = unpack
        lastDecodedSamples = finalPcmData
        lastUnpackTime = currentTime

        if (previousUnpack == null) delay(BUFFERING_TIME_MS)

        resetAiTimer()
        AiPlayerUtils.onAiFrameEnqueued(aiDecodeData.from, aiDecodeData.source)
        AiPlayerUtils.onAiFrameDecoded(finalPcmData, AI_SAMPLE_RATE)
        aiDecodeData.onPcmReady?.invoke(finalPcmData)
        AiPlayerUtils.enqueue(finalPcmData, AI_SAMPLE_RATE)
    }

    suspend fun handleTokenizerChunkForTest(unpack: List<Long>) {
        val currentTime = System.currentTimeMillis()
        val previousUnpack = lastUnpack?.takeIf { currentTime - lastUnpackTime < 2000 }
        val previousSample = lastDecodedSamples?.takeIf { currentTime - lastUnpackTime < 2000 }
        val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)

        val finalPcmData = wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, modelTypeSelected)

        lastUnpack = unpack
        lastDecodedSamples = finalPcmData
        lastUnpackTime = currentTime

        if (previousUnpack == null) delay(BUFFERING_TIME_MS)

        AiPlayerUtils.enqueue(finalPcmData, AI_SAMPLE_RATE)
    }

    private fun onAiReceiveTimeout() {
        AiPlayerUtils.finalizeAiReceiveSessionOnTimeout()
    }

    private fun resetAiTimer() {
        aiHandler.removeCallbacks(aiTimeoutRunnable)
        aiHandler.removeCallbacksAndMessages(null)
        aiHandler.postDelayed(aiTimeoutRunnable, AI_INACTIVITY_TIMEOUT_MS)
    }

    private fun clearDecodeContext() {
        lastUnpack = null
        lastDecodedSamples = null
        lastUnpackTime = 0L
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }
}