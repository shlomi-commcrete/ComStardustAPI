package com.commcrete.stardust.ai.codec

import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ai.codec.PcmStreamPlayer.initPttInputFile
import com.commcrete.stardust.ai.codec.PcmStreamPlayer.isFileInit
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.context
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.audio.PlayerUtils.ParsedAiData
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
    private const val CONTEXT_FRESHNESS_MS = 2000L
    private var coroutineScope = CoroutineScope(Dispatchers.Default) // Scope for decoding and frame dropping
    private var decodingJob: Job? = null
    private var toDecodeQueue = Channel<ByteArray>(Channel.UNLIMITED) // Equivalent to m_packet_queue using a Channel

    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder

    // Variables to track last unpack and timestamp
    private var lastUnpack: List<Long>? = null
    private var lastDecodedSamples: ShortArray? = null
    private var lastUnpackTime: Long = 0L
    private var dataPackage: StardustPackage? = null
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

    fun addNewData(data: ParsedAiData, dataPackage: StardustPackage) {
        selectedModel = data.selectedModule ?: WavTokenizerDecoder.ModelType.General
        this.dataPackage = dataPackage
        toDecodeQueue.trySend(data.decodedBytes)

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
        val (previousUnpack, previousSample) = getPreviousContext()

        val finalPcmData = decodePcm(unpack, previousUnpack, previousSample)
        Log.d(TAG, "Decoded tokenizer unpack size ${unpack.size} , PCM data: ${finalPcmData.size} samples")

        rememberContext(unpack, finalPcmData)

        // Add buffering delay only if there was no previous unpack (first packet)
        if (previousUnpack == null) {
            delay(BUFFERING_TIME_MS)
        }

        notifyPttCallbacks(decodedData)
        PcmStreamPlayer.enqueue(finalPcmData, 24000)
    }

    /** Returns the cached unpack/PCM context if it is still fresh (within the timeout). */
    private fun getPreviousContext(): Pair<List<Long>?, ShortArray?> {
        val currentTime = System.currentTimeMillis()
        val isFresh = (currentTime - lastUnpackTime) < CONTEXT_FRESHNESS_MS
        val previousUnpack = if (isFresh) lastUnpack else null
        val previousSample = if (isFresh) lastDecodedSamples else null
        return previousUnpack to previousSample
    }

    private fun decodePcm(
        unpack: List<Long>,
        previousUnpack: List<Long>?,
        previousSample: ShortArray?
    ): ShortArray {
        return wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, selectedModel)
    }

    private fun rememberContext(unpack: List<Long>, finalPcmData: ShortArray) {
        lastUnpack = unpack
        lastDecodedSamples = finalPcmData
        lastUnpackTime = System.currentTimeMillis()
    }

    private suspend fun notifyPttCallbacks(decodedData: ByteArray) {
        val appId = RegisteredUserUtils.mRegisterUser.value?.appId ?: return
        val data = dataPackage ?: return
        val pkg = StardustAPIPackage(senderId = data.senderId, groupId = data.groupId, receiverId = appId)

        if (!isFileInit) {
            startPttReception(pkg, data, appId)
        } else {
            DataManager.getCallbacks()?.receivePTT(pkg, decodedData)
        }
    }

    private suspend fun startPttReception(pkg: StardustAPIPackage, data: StardustPackage, appId: String) {
        Log.d("PcmStreamPlayer", "Initializing PTT input file...")
        val file = initPttInputFile(context, pkg) ?: return

        persistIncomingPttMessage(file.absolutePath, data, appId)
        DataManager.getCallbacks()?.startedReceivingPTT(pkg, file)
    }

    private suspend fun persistIncomingPttMessage(filePath: String, data: StardustPackage, appId: String) {
        val message = buildIncomingPttMessage(filePath, data, appId)
        DataManager.getAppRepo(context).saveMessage(message, data.groupId)
    }

    private fun buildIncomingPttMessage(filePath: String, data: StardustPackage, appId: String): MessageEntity =
        MessageEntity(
            chatId = data.chatId,
            senderID = data.senderId,
            receiverID = appId,
            state = MessageState.RECEIVING,
            extraData = MessageExtraData.PTT(
                path = filePath,
                encoderType = EncoderType.AI
            )
        )

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }
}