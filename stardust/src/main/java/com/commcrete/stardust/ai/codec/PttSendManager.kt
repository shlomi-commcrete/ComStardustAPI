package com.commcrete.stardust.ai.codec

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.codecs.WavTokenizerEncoder
import com.commcrete.aiaudio.media.WavHelper
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

object PttSendManager {

    /**
     * Optional per-chunk hook for capturing the decoded (post-WavTokenizer)
     * PCM as it is produced — i.e. the AI **output** that corresponds 1:1
     * to each [addNewFrame] input chunk.
     *
     * Set this to a non-null callback (e.g. from `AudioFeederEngine` during
     * a test-feed run) to receive every decoded chunk in encode order, and
     * clear (set back to null) when capture is finished. Call
     * [resetLiveDecodeState] between sessions to drop residual state.
     *
     * Implementation note: when this hook is registered, [handleTokenizerChunk]
     * runs **one** decode per encoded chunk and shares the resulting PCM
     * with [saveTofile]. Doing it in two independent decode passes corrupts
     * the shared `WavTokenizerDecoder` instance state (`cutTokens`, `index`),
     * because each pass reads what the OTHER pass wrote on the previous
     * chunk — producing garbled output even though each stream's own
     * continuity (`lastTokens` / `lastPCM`) looks fine.
     */
    @Volatile
    var onDecodedChunk: ((ShortArray) -> Unit)? = null

    /**
     * Drop the per-chunk decoder continuity state ([lastTokens] / [lastPCM])
     * AND any buffered [frameBuffer]. Call between sessions / per source so
     * the next stream starts with a clean previousTokens=null path through
     * `WavTokenizerDecoder.decode` (no stale head-cut from a different stream).
     */
    fun resetLiveDecodeState() {
        lastTokens = null
        lastPCM = null
        frameBuffer.clear()
    }

    private val TAG = "PttManager"
    private val TAG_DECODE = "PttManager_Decode"
    private val TAG_ENCODE = "PttManager_Encode"
    private var coroutineScope = CoroutineScope(Dispatchers.Default) // Scope for decoding and frame dropping
    private var encodingJob: Job? = null
    private var toEncodeQueue = Channel<ShortArray>(Channel.UNLIMITED) // Equivalent to m_packet_queue using a Channel
    private var wavTokenizerEncoder: WavTokenizerEncoder= AIModuleInitializer.wavTokenizerEncoder
    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder
    private lateinit var cacheDir: File
    private var fileToSave: File? = null
    var carrier : Carrier? = null
    var chatID: String? = null
    private var viewModel : PttInterface? = null
    var aiEnabled = false

    fun init(context: Context, viewModel : PttInterface? = null) {
        cacheDir = context.cacheDir
        this.viewModel = viewModel
        startEncodingJob(context)
        aiEnabled = true
//        AudioDebugTest(context, wavTokenizerEncoder, wavTokenizerDecoder).runTest()
    }

    fun addNewFrame(pcmArray: ShortArray, file: File, carrier: Carrier? = null, chatID: String? = null) {
        toEncodeQueue.trySend(pcmArray)
        fileToSave = file
        this.carrier = carrier
        this.chatID = chatID
    }

    fun finish(context: Context) {
        saveTofile(context, byteArrayOf(), finish = true) // Need to delete
    }

    private fun startEncodingJob(context: Context) {
        if (!aiEnabled) return  // hard guard everywhere you might touch PyTorch
        encodingJob = coroutineScope.launch {
            while (isActive) { // Keep the decoding loop active
                try {
                    // Offer the packet to the channel without suspending if the channel is not full
                    // If the channel was limited, offer might return false or suspend
                    val pcmArray = toEncodeQueue.tryReceive().getOrNull() // Attempt to receive without suspending

                    if (pcmArray != null) {
                        handleTokenizerChunk(context, pcmArray)
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

    private fun handleTokenizerChunk(context: Context, pcmArray: ShortArray) {
        Log.d(TAG_ENCODE, "Encoding PCM chunk of size")
        val chunkCodes = wavTokenizerEncoder.encode(pcmArray)
        Log.d(TAG_ENCODE, "Tokenizer encoded chunk size")
        Log.d(TAG, "Encoded chunk size ${chunkCodes.size}")

        val packedData = BitPacking12.pack12(chunkCodes.toList())

        sendData(context, packedData)

        // ── Per-chunk decode (single source of truth) ────────────────────
        // Both consumers (onDecodedChunk debug hook AND saveTofile's 45 s
        // buffered WAV) need the decoded PCM for THIS encoded chunk. We
        // MUST run that decode at most once per chunk, because
        // WavTokenizerDecoder is a shared singleton that mutates private
        // instance state (`cutTokens`, `index`) on every call. Two
        // independent decode streams against the same instance silently
        // clobber each other's `cutTokens` between chunks → wrong head-cut
        // in `handleSmart` → garbled output, even though each stream's
        // own `previousTokens` / `previousSamples` continuity looks fine.
        // See `WavTokenizerDecoder.handleSmart` for the math.
        val decodedSink = onDecodedChunk
        val savingToFile = needToRun && DataManager.getSavePTTFilesRequired(context)
        var decodedPcm: ShortArray? = null
        if (decodedSink != null || savingToFile) {
            try {
                val unpack = BitPacking12.unpack12(packedData)
                val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)
                val pcm = wavTokenizerDecoder.decode(unpack, lastTokens, lastPCM, modelTypeSelected)
                lastTokens = unpack
                lastPCM = pcm
                decodedPcm = pcm
                decodedSink?.invoke(pcm)
            } catch (t: Throwable) {
                Log.w(TAG_DECODE, "per-chunk decode failed", t)
            }
        }

        saveTofile(context, packedData, decodedPcm = decodedPcm) // Need to delete
    }

    // Equivalent to private void SendData(byte[] data)
    private fun sendData(context: Context, data: ByteArray) {
        Log.d(TAG, "Send msg: ${data.size} data: ${data.toHexString()}")
        val radio = CarriersUtils.getRadioToSend(carrier, functionalityType = FunctionalityType.PTT) ?: return

        Scopes.getDefaultCoroutine().launch {
            val bittelPackage = viewModel?.let {
                val fullData = ByteArray(data.size + 1)
                fullData[0] = 0x00
                System.arraycopy(data, 0, fullData, 1, data.size)
                val audioIntArray = StardustPackageUtils.byteArrayToIntArray(fullData)
                StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = it.getSource(),
                    destenation = it.getDestenation() ?: "" ,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_PTT_AI,
                    data = audioIntArray)
            }
            val isLast = data.size != 30


            bittelPackage?.let { bittelPackage ->
                bittelPackage.stardustControlByte.stardustPartType = if( isLast) StardustControlByte.StardustPartType.LAST else StardustControlByte.StardustPartType.MESSAGE
                bittelPackage.stardustControlByte.stardustDeliveryType = radio.second
                bittelPackage.checkXor = StardustPackageUtils.getCheckXor(bittelPackage.getStardustPackageToCheckXor())
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
                DataManager.getMessagesRepo(DataManager.context).saveMessage(
                    context = context,
                    isPTT = true,
                    messageItem = MessageItem(senderID = it,
                        epochTimeMs = RecorderUtils.ts, senderName = "" ,
                        chatId = chatID, text = "", fileLocation = path,
                        isAudio = true, seen = SeenStatus.SENT, audioType = RecorderUtils.CODE_TYPE.AI.id)
                )
            }
            RecorderUtils.ts = 0
        }
    }

    fun restart () {
        isFirst = true
        startRecording = 0L
        needToRun = true
        // resetLiveDecodeState() clears frameBuffer, lastPCM and lastTokens —
        // keeping a single source of truth for the decode-side reset.
        resetLiveDecodeState()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }

    private var isFirst = true;
    private var startRecording = 0L
    private var needToRun = true;
    private val frameBuffer = mutableListOf<ShortArray>()
    private var lastPCM  : ShortArray? = null
    private var lastTokens  : List<Long>? = null
    private fun saveTofile(
        context: Context,
        packData: ByteArray,
        finish: Boolean = false,
        decodedPcm: ShortArray? = null,
    ) {
        Log.d(TAG, "saveTofile called with data size: ${packData.size}")
        if (!needToRun || !DataManager.getSavePTTFilesRequired(context)) return

        if (isFirst) {
            isFirst = false
            startRecording = System.currentTimeMillis()
        }
        Log.d(TAG_DECODE, "Processing packData of size: ${packData.size}")

        val finalPcmData: ShortArray = if (decodedPcm != null) {
            decodedPcm
        } else {
            val unpack = BitPacking12.unpack12(packData)
            val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)
            val pcm = wavTokenizerDecoder.decode(unpack, lastTokens, lastPCM, modelTypeSelected)
            lastTokens = unpack
            lastPCM = pcm
            pcm
        }
        Log.d(TAG_DECODE, "Decoded PCM data size ${finalPcmData.size}")
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
                    // TODO: remove copy
                    // Mirror a copy into the active debug-artifact directory
                    // (set by AudioFeederEngine to the per-run artifactDir,
                    // i.e. where *-ai_output_24k.wav lives). Lets the streaming
                    // saveTofile output be A/B'd against the live per-chunk
                    // *-ai_output_24k.wav side by side without hunting through
                    // <DataManager.fileLocation>/test_feeder/<destination>/.
                    // Wrapped in runCatching so production paths (where the
                    // dir may not be writable, or may equal `file.parentFile`
                    // turning this into a no-op) never break the WAV write.
                    runCatching {
                        val artifactDir = RecorderUtils.dirToSaveFile
                        val srcParent = file.parentFile?.canonicalFile
                        val dstParent = artifactDir.canonicalFile
                        if (srcParent != dstParent) {
                            if (!artifactDir.exists()) artifactDir.mkdirs()
                            // Force a `.wav` extension on the mirror — the
                            // production sinkFile uses `.pcm` (createSinkFile
                            // in AudioFeederEngine) even though the bytes are
                            // a real RIFF/WAVE container, which confuses
                            // Audacity / ffmpeg auto-detect.
                            val mirrorName = "${file.nameWithoutExtension}-ptt_finish.wav"
                            val mirror = File(artifactDir, mirrorName)
                            file.copyTo(mirror, overwrite = true)
                            Log.d(TAG, "WAV mirror copied: ${mirror.absolutePath}")
                        }
                    }.onFailure { Log.w(TAG, "WAV mirror copy failed", it) }

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