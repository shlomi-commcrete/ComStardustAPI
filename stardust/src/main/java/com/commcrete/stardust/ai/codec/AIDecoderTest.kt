package com.commcrete.stardust.ai.codec

import android.content.Context
import android.util.Log
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AIDecoderTest {

    private const val TAG = "AIDecoderTest"

    private const val EXPECTED_SAMPLE_RATE = 24_000
    private const val EXPECTED_BITS_PER_SAMPLE = 16
    private const val EXPECTED_CHANNELS = 1

    data class WavInfo(
        val sampleRate: Int,
        val bitsPerSample: Int,
        val channels: Int,
        val numSamples: Int,
        val durationMs: Long
    )

    fun checkAndLoadWavFromAssets(
        context: Context,
        assetFileName: String = "Vocal__original.wav"
    ): Pair<WavInfo, ShortArray>? {
        return try {
            context.assets.open(assetFileName).use { inputStream ->
                val header = ByteArray(44)
                val readHeader = inputStream.read(header)
                if (readHeader != 44) {
                    Log.e(TAG, "Invalid WAV header size: $readHeader")
                    return null
                }

                val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

                val chunkId = String(header.copyOfRange(0, 4))
                val format = String(header.copyOfRange(8, 12))
                val subChunk1Id = String(header.copyOfRange(12, 16))

                if (chunkId != "RIFF" || format != "WAVE" || subChunk1Id != "fmt ") {
                    Log.e(TAG, "Not a valid PCM WAV (chunkId=$chunkId, format=$format, fmt=$subChunk1Id)")
                    return null
                }

                val audioFormat = headerBuf.getShort(20).toInt() and 0xFFFF
                val numChannels = headerBuf.getShort(22).toInt() and 0xFFFF
                val sampleRate = headerBuf.getInt(24)
                val bitsPerSample = headerBuf.getShort(34).toInt() and 0xFFFF

                if (audioFormat != 1) {
                    Log.e(TAG, "Only PCM (format 1) supported, got $audioFormat")
                    return null
                }

                if (sampleRate != EXPECTED_SAMPLE_RATE ||
                    bitsPerSample != EXPECTED_BITS_PER_SAMPLE ||
                    numChannels != EXPECTED_CHANNELS
                ) {
                    Log.w(
                        TAG,
                        "WAV format mismatch. Got: $sampleRate Hz, $bitsPerSample bits, $numChannels ch"
                    )
                } else {
                    Log.d(TAG, "WAV format OK: $sampleRate Hz, $bitsPerSample bits, $numChannels ch")
                }

                val dataBytes = inputStream.readBytes()
                if (bitsPerSample != 16) {
                    Log.e(TAG, "Only 16-bit PCM supported in this test")
                    return null
                }

                val numSamples = dataBytes.size / 2
                val samples = ShortArray(numSamples)
                ByteBuffer.wrap(dataBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(samples)

                val durationMs = numSamples * 1000L / sampleRate

                val info = WavInfo(
                    sampleRate = sampleRate,
                    bitsPerSample = bitsPerSample,
                    channels = numChannels,
                    numSamples = numSamples,
                    durationMs = durationMs
                )

                info to samples
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading WAV from assets", e)
            null
        }
    }

    fun splitInto500msChunks(
        samples: ShortArray,
        sampleRate: Int = EXPECTED_SAMPLE_RATE
    ): List<ShortArray> {
        val samplesPer500ms = sampleRate / 2  // 24000 / 2 = 12000
        if (samplesPer500ms <= 0) return emptyList()

        val chunks = mutableListOf<ShortArray>()
        var index = 0
        while (index < samples.size) {
            val end = (index + samplesPer500ms).coerceAtMost(samples.size)
            val chunkSize = end - index
            if (chunkSize <= 0) break

            val chunk = ShortArray(chunkSize)
            System.arraycopy(samples, index, chunk, 0, chunkSize)
            chunks.add(chunk)

            index += samplesPer500ms
        }
        Log.d(TAG, "Split into ${chunks.size} chunks of ~500ms")
        return chunks
    }

    fun convert48kTo24k(input: ShortArray): ShortArray {
        val outputSize = input.size / 2
        val output = ShortArray(outputSize)

        var inIndex = 0
        var outIndex = 0
        while (inIndex + 1 < input.size && outIndex < outputSize) {
            output[outIndex++] = input[inIndex]
            inIndex += 2
        }

        return output
    }

    /**
     * Main helper:
     * 1. Load WAV from assets
     * 2. Split into 500 ms chunks
     * 3. Send each chunk to PTTSendManager.addNewFrame(...)
     */
    fun sendAssetWavToPTTAs500msFrames(
        context: Context,
        outputFile: File,
        carrier: Carrier? = null,
        chatId: String? = null,
        assetFileName: String = "Vocal__original.wav"
    ) {
        val pair = checkAndLoadWavFromAssets(context, assetFileName) ?: run {
            Log.e(TAG, "Failed to load WAV from assets")
            return
        }

        var (info, samples) = pair

        // --- NEW: Downsample if needed ---
        if (info.sampleRate == 48_000) {
            Log.w(TAG, "Converting WAV from 48000 Hz → 24000 Hz")
            samples = convert48kTo24k(samples)

            // Update the metadata
            info = info.copy(
                sampleRate = 24_000,
                numSamples = samples.size,
                durationMs = samples.size * 1000L / 24_000
            )
        }

        val chunks = splitInto500msChunks(samples, info.sampleRate)
        Log.d(TAG, "Sending ${chunks.size} frames to PTTSendManager")

        Scopes.getDefaultCoroutine().launch {
            for (chunk in chunks) {
                PttSendManager.addNewFrame(
                    pcmArray = chunk,
                    file = outputFile,
                    carrier = carrier,
                    chatID = chatId
                )
                delay(500)
            }
        }
    }

    fun testTokens (context: Context, assetFileName: String = "Vocal__tokens.txt"
    ) {
        val tokens: List<List<Long>> =
            loadTokensFromAssets(context, assetFileName)
        Scopes.getDefaultCoroutine().launch {
            for (chunk in tokens) {
                PttReceiveManager.handleTokenizerChunkForTest(chunk)
                delay(500)
            }
        }
    }

    fun loadTokensFromAssets(context: Context, assetFileName: String): List<List<Long>> {
        // Open the file from assets
        val inputStream = context.assets.open(assetFileName)

        // Read all lines and convert to Long
        val tokens: List<Long> = inputStream.bufferedReader().use { reader ->
            reader.readLines().mapNotNull { line ->
                line.trim().toLongOrNull()
            }
        }

        // Split into chunks of 20 and return
        return tokens.chunked(20)
    }
}