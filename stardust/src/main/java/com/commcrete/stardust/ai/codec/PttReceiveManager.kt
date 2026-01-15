package com.commcrete.stardust.ai.codec

import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.media.PcmStreamPlayer
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PttReceiveManager {
    private const val TAG = "PttManager"
    private const val BUFFERING_TIME_MS = 500L
    private var coroutineScope = CoroutineScope(Dispatchers.Default) // Scope for decoding and frame dropping
    private var decodingJob: Job? = null
    private var toDecodeQueue = Channel<ByteArray>(Channel.UNLIMITED) // Equivalent to m_packet_queue using a Channel

    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder

    // Variables to track last unpack and timestamp
    private var lastUnpack: List<Long>? = null
    private var lastDecodedSamples: ShortArray? = null
    private var lastUnpackTime: Long = 0L
    private var from = ""
    private var source : String? = ""
    private var selectedModel : WavTokenizerDecoder.ModelType = WavTokenizerDecoder.ModelType.General
    private var aiDecodeData: AIDecodeData? = null
    fun init() {
        startDecodingJob()
    }

    data class AIDecodeData (
        val data: ByteArray,
        val from: String = "",
        val source: String = "",
        val modelType: WavTokenizerDecoder.ModelType = WavTokenizerDecoder.ModelType.General,
        val onPcmReady: ((ShortArray) -> Unit)? = null
    )

    fun addNewData(data: ByteArray, from : String, source : String? = null, modelType: WavTokenizerDecoder.ModelType? = null,
                   onPcmReady: ((ShortArray) -> Unit)? = null) {
        selectedModel = modelType ?: WavTokenizerDecoder.ModelType.General
        this.from = from
        this.source = source
        toDecodeQueue.trySend(data)

    }

    fun addNewData(aiDecodeData: AIDecodeData) {
        this.aiDecodeData = aiDecodeData
        toDecodeQueue.trySend(aiDecodeData.data)

    }

    private fun startDecodingJob() {
        decodingJob = coroutineScope.launch {
            while (isActive) { // Keep the decoding loop active
                try {
                    // Offer the packet to the channel without suspending if the channel is not full
                    // If the channel was limited, offer might return false or suspend
                    val data = toDecodeQueue.tryReceive().getOrNull() // Attempt to receive without suspending

                    if (data != null) {
                        Log.d(TAG, "Data received of size: ${data.size}")
                        val decodedData = data.sliceArray(1 until data.size)
                        Log.d(TAG, "Received data: ${decodedData.toHexString()}")

                        handleTokenizerChunk(decodedData)
                    }

                } catch (e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec exception during decoding: ${e.diagnosticInfo}", e)
                    break // Exit the decoding loop on error
                } catch (e: Exception) {
                    Log.e(TAG, "Error in decoding loop: ${e.message}", e)
                    break // Exit the decoding loop on other errors
                }

                // Small delay to prevent a tight loop from consuming too much CPU if no buffers are available
                delay(1) // Adjust delay as needed
            }
            Log.d(TAG, "Decoding job finished.")
        }
    }

    private suspend fun handleTokenizerChunk(decodedData: ByteArray) {
        val unpack = BitPacking12.unpack12(decodedData)

        // Check if last unpack was received within 800ms
        val currentTime = System.currentTimeMillis()
        val previousUnpack = if (lastUnpack != null && (currentTime - lastUnpackTime) < 2000) {
            lastUnpack
        } else {
            null
        }
        val previousSample = if (lastDecodedSamples != null && (currentTime - lastUnpackTime) < 2000) {
            lastDecodedSamples
        } else {
            null
        }
        val finalPcmData = wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, selectedModel)
//        val finalPcmData = wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, aiDecodeData?.modelType ?: WavTokenizerDecoder.ModelType.General)
        Log.d(TAG, "Decoded tokenizer unpack size ${unpack.size} , PCM data: ${finalPcmData.size} samples")

        // Save current unpack and timestamp for next iteration
        lastUnpack = unpack
        lastDecodedSamples = finalPcmData
        lastUnpackTime = currentTime

        // Add buffering delay only if there was no previous unpack (first packet)
        if (previousUnpack == null)
            delay(BUFFERING_TIME_MS)

//        aiDecodeData?.onPcmReady?.invoke(finalPcmData)
        PcmStreamPlayer.enqueue(finalPcmData, 24000, aiDecodeData?.from ?: "", aiDecodeData?.source ?: "")
    }

    suspend fun handleTokenizerChunkForTest(unpack: List<Long>) {

        // Check if last unpack was received within 800ms
        val currentTime = System.currentTimeMillis()
        val previousUnpack = if (lastUnpack != null && (currentTime - lastUnpackTime) < 2000) {
            lastUnpack
        } else {
            null
        }
        val previousSample = if (lastDecodedSamples != null && (currentTime - lastUnpackTime) < 2000) {
            lastDecodedSamples
        } else {
            null
        }
        val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)

        val finalPcmData = wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, modelTypeSelected)
        Log.d(TAG, "Decoded tokenizer unpack size ${unpack.size} , PCM data: ${finalPcmData.size} samples")

        // Save current unpack and timestamp for next iteration
        lastUnpack = unpack
        lastDecodedSamples = finalPcmData
        lastUnpackTime = currentTime

        // Add buffering delay only if there was no previous unpack (first packet)
        if (previousUnpack == null)
            delay(BUFFERING_TIME_MS)

        PcmStreamPlayer.enqueue(finalPcmData, 24000, "from", "source")
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }
}