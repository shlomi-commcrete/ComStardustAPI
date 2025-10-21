package com.commcrete.stardust.ai.codec

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.codecs.WavTokenizerEncoder
import com.commcrete.aiaudio.media.WavHelper
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FunctionalityType
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.audio.WavRecorder
import com.commcrete.stardust.util.audio.endsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

object PttSendManager {
    private val TAG = "PttManager"
    private val TAG_DECODE = "PttManager_Decode"
    private val TAG_ENCODE = "PttManager_Encode"
    private var coroutineScope = CoroutineScope(Dispatchers.Default) // Scope for decoding and frame dropping
    private var encodingJob: Job? = null
    private var toEncodeQueue = Channel<ShortArray>(Channel.UNLIMITED) // Equivalent to m_packet_queue using a Channel
    private lateinit var wavTokenizerEncoder: WavTokenizerEncoder
    private lateinit var wavTokenizerDecoder: WavTokenizerDecoder
    private lateinit var cacheDir: File
    private var fileToSave: File? = null
    var carrier : Carrier? = null
    var chatID: String? = null
    private var viewModel : PttInterface? = null

    fun init(context: Context, pluginContext: Context, viewModel : PttInterface? = null) {
        cacheDir = context.cacheDir
        if(!::wavTokenizerEncoder.isInitialized) {
            wavTokenizerEncoder = WavTokenizerEncoder(context, pluginContext)
        }
        if(!::wavTokenizerDecoder.isInitialized) {
            wavTokenizerDecoder = WavTokenizerDecoder(context, pluginContext)
        }
        this.viewModel = viewModel
        startEncodingJob()

//        AudioDebugTest(context, wavTokenizerEncoder, wavTokenizerDecoder).runTest()
    }

    fun addNewFrame(pcmArray: ShortArray, file: File, carrier: Carrier? = null, chatID: String? = null) {
        toEncodeQueue.trySend(pcmArray)
        fileToSave = file
        this.carrier = carrier
        this.chatID = chatID
    }

    fun finish() {
        saveTofile(byteArrayOf(), finish = true) // Need to delete
    }

    private fun startEncodingJob() {
        encodingJob = coroutineScope.launch {
            while (isActive) { // Keep the decoding loop active
                try {
                    // Offer the packet to the channel without suspending if the channel is not full
                    // If the channel was limited, offer might return false or suspend
                    val pcmArray = toEncodeQueue.tryReceive().getOrNull() // Attempt to receive without suspending

                    if (pcmArray != null) {
                        handleTokenizerChunk(pcmArray)
                        Log.d(TAG, "Codec decoding loop iteration completed.")
                    }

                } catch (e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec exception during decoding: ${e.diagnosticInfo}", e)
                    break // Exit the decoding loop on error
                } catch (e: Exception) {
                    Log.e(TAG, "Error in decoding loop: ${e.message}", e)
                    break // Exit the decoding loop on other errors
                }

                // Small delay to prevent a tight loop from consuming too much CPU if no buffers are available
                delay(10) // Adjust delay as needed
            }
            Log.d(TAG, "Decoding job finished.")
        }
    }

    private suspend fun handleTokenizerChunk(pcmArray: ShortArray) {
        Log.d(TAG_ENCODE, "Encoding PCM chunk of size")
        val chunkCodes = wavTokenizerEncoder.encode(pcmArray)
        Log.d(TAG_ENCODE, "Tokenizer encoded chunk size")
        Log.d(TAG, "Encoded chunk size ${chunkCodes.size}")

        val packedData = BitPacking12.pack12(chunkCodes.toList())

        sendData(packedData)

        saveTofile(packedData) // Need to delete
    }

    // Equivalent to private void SendData(byte[] data)
    private suspend fun sendData(data: ByteArray) {
        Log.d(TAG, "Send msg: ${data.size} data: ${data.toHexString()}")
        Scopes.getDefaultCoroutine().launch {
            val bittelPackage = viewModel?.let {
                val fullData = ByteArray(data.size + 1)
                fullData[0] = 0x00
                System.arraycopy(data, 0, fullData, 1, data.size)
                val audioIntArray = StardustPackageUtils.byteArrayToIntArray(fullData)
                StardustPackageUtils.getStardustPackage(source = it.getSource(), destenation = it.getDestenation() ?: "" , stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_PTT_AI,
                    data = audioIntArray)
            }
            val radio = CarriersUtils.getRadioToSend(carrier, functionalityType = FunctionalityType.PTT)
            val isLast = data.size != 30
            bittelPackage?.stardustControlByte?.stardustPartType = if( isLast) StardustControlByte.StardustPartType.LAST else StardustControlByte.StardustPartType.MESSAGE
            bittelPackage?.stardustControlByte?.stardustDeliveryType = radio.second
            bittelPackage?.checkXor =
                bittelPackage?.getStardustPackageToCheckXor()
                    ?.let { StardustPackageUtils.getCheckXor(it) }

            bittelPackage?.let {
                DataManager.sendDataToBle(bittelPackage)
            }

        }

    }

    private fun savePtt(chatID : String, path : String, context: Context){
        Scopes.getDefaultCoroutine().launch {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let {
                val chatsRepo = DataManager.getChatsRepo(context)
                val chatItem = chatsRepo.getChatByBittelID(chatID)
                chatItem?.message = Message(
                    senderID = it,
                    text = "PTT Sent",
                    seen = true
                )
                chatItem?.let { chatsRepo.addChat(it) }
                MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).savePttMessage(
                    MessageItem(senderID = it,
                        epochTimeMs = RecorderUtils.ts, senderName = "" ,
                        chatId = chatID, text = "", fileLocation = path,
                        isAudio = true, seen = SeenStatus.SENT, audioType = RecorderUtils.CODE_TYPE.AI.id)
                )
            }
            RecorderUtils.ts = 0
            RecorderUtils.file = null
        }
    }

    fun restart () {
        isFirst = true
        startRecording = 0L
        needToRun = true
        frameBuffer.clear()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }

    private var isFirst = true;
    private var startRecording = 0L
    private var needToRun = true;
    private val frameBuffer = mutableListOf<ShortArray>()

    private fun saveTofile(packData: ByteArray, finish : Boolean = false) {
        Log.d(TAG, "saveTofile called with data size: ${packData.size}")
        if (!needToRun) return

        if (isFirst) {
            isFirst = false
            startRecording = System.currentTimeMillis()
        }
        Log.d(TAG_DECODE, "Processing packData of size: ${packData.size}")
        val unpack = BitPacking12.unpack12(packData)
        val finalPcmData = wavTokenizerDecoder.decode(unpack)
        Log.d(TAG_DECODE, "Decoded PCM data size")
        frameBuffer.add(finalPcmData)

        if ((System.currentTimeMillis() - startRecording > 45000) || finish) {
            Log.d(TAG, "3 seconds elapsed, saving to file.")
            needToRun = false
            val file = fileToSave
            try {
                file?.let {
                    val sampleArray = frameBuffer.flatMap { it.asIterable() }.toShortArray()
                    WavHelper.createWavFile(sampleArray, 24000, file)
                    Log.d(TAG, "WAV file created: ${file.absolutePath}")
                    savePtt(context = DataManager.context, chatID = chatID ?:"", path = fileToSave?.absolutePath?:"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating WAV file", e)
            }
        }
    }

    private var isFirst2 = true;
    private var startRecording2 = 0L
    private var needToRun2 = true;
    private val frameBuffer2 = mutableListOf<ShortArray>()

    private fun savePcmTofile(pcmData: ShortArray) {
        if (!needToRun2) return

        if (isFirst2) {
            isFirst2 = false
            startRecording2 = System.currentTimeMillis()
        }

        frameBuffer2.add(pcmData)

        if (System.currentTimeMillis() - startRecording2 > 3000) {
            needToRun2 = false
            val fileName = "record.wav"
            val file = File("/data/data/com.commcrete.aiaudio/cache", fileName)

            try {
                val sampleArray = frameBuffer2.flatMap { it.asIterable() }.toShortArray()
                WavHelper.createWavFile(sampleArray, 24000, file)
                Log.d(TAG, "WAV file created: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating WAV file", e)
            }
        }
    }
}