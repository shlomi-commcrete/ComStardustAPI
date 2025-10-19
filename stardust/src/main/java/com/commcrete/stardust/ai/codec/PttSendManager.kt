package com.commcrete.stardust.ai.codec

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.codecs.WavTokenizerEncoder
import com.commcrete.aiaudio.media.WavHelper
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
    private var coroutineScope = CoroutineScope(Dispatchers.Default) // Scope for decoding and frame dropping
    private var encodingJob: Job? = null
    private var toEncodeQueue = Channel<ShortArray>(Channel.UNLIMITED) // Equivalent to m_packet_queue using a Channel
    private lateinit var wavTokenizerEncoder: WavTokenizerEncoder
    private lateinit var wavTokenizerDecoder: WavTokenizerDecoder
    private lateinit var cacheDir: File

    fun init(context: Context) {
        cacheDir = context.cacheDir
        wavTokenizerEncoder = WavTokenizerEncoder(context)
        wavTokenizerDecoder = WavTokenizerDecoder(context)
        startEncodingJob()

//        AudioDebugTest(context, wavTokenizerEncoder, wavTokenizerDecoder).runTest()
    }

    fun addNewFrame(pcmArray: ShortArray) {
        toEncodeQueue.trySend(pcmArray)
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
        val chunkCodes = wavTokenizerEncoder.encode(pcmArray)

        Log.d(TAG, "Encoded chunk size ${chunkCodes.size}")

        val packedData = BitPacking12.pack12(chunkCodes.toList())

        sendData(packedData, isCodec2 = false)

//        saveTofile(packedData) // Need to delete
    }

    // Equivalent to private void SendData(byte[] data)
    private suspend fun sendData(data: ByteArray, isCodec2: Boolean) {
        Log.d(TAG, "Send msg: ${data.size} data: ${data.toHexString()}")
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }

    private var isFirst = true;
    private var startRecording = 0L
    private var needToRun = true;
    private val frameBuffer = mutableListOf<ShortArray>()

    private fun saveTofile(packData: ByteArray) {
        if (!needToRun) return

        if (isFirst) {
            isFirst = false
            startRecording = System.currentTimeMillis()
        }

        val unpack = BitPacking12.unpack12(packData)
        val finalPcmData = wavTokenizerDecoder.decode(unpack)

        frameBuffer.add(finalPcmData)

        if (System.currentTimeMillis() - startRecording > 3000) {
            needToRun = false
            val fileName = "ptt_send.wav"
            val file = File("/data/data/com.commcrete.aiaudio/cache", fileName)

            try {
                val sampleArray = frameBuffer.flatMap { it.asIterable() }.toShortArray()
                WavHelper.createWavFile(sampleArray, 24000, file)
                Log.d(TAG, "WAV file created: ${file.absolutePath}")
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