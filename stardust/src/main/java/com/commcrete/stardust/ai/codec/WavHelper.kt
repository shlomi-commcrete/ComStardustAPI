package com.commcrete.aiaudio.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Build.VERSION_CODES.M
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavHelper {

    fun extractPcmFromWavFile(wavFile: File): ShortArray {
        try {
            val bytes = wavFile.readBytes()

            // WAV header is 44 bytes, PCM data starts after that
            val headerSize = 44
            if (bytes.size <= headerSize) {
                throw IOException("WAV file too small or corrupt")
            }

            // Extract PCM data (skip WAV header)
            val pcmBytes = bytes.sliceArray(headerSize until bytes.size)

            // Convert bytes to short array (16-bit PCM, little-endian)
            val pcmData = ShortArray(pcmBytes.size / 2)
            for (i in pcmData.indices) {
                val byteIndex = i * 2
                if (byteIndex + 1 < pcmBytes.size) {
                    // Little-endian: low byte first, then high byte
                    val low = pcmBytes[byteIndex].toInt() and 0xFF
                    val high = pcmBytes[byteIndex + 1].toInt() and 0xFF
                    pcmData[i] = ((high shl 8) or low).toShort()
                }
            }

            return pcmData

        } catch (e: Exception) {
            Log.e("MainActivity", "Error extracting PCM from WAV file", e)
            throw e
        }
    }

    fun playPcmData(pcmData: ShortArray, sampleRate: Int) {
        try {
            val audioTrack = if (Build.VERSION.SDK_INT >= M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcmData.size * 2)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    pcmData.size * 2,
                    AudioTrack.MODE_STATIC
                )
            }

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()

            // Wait for playback to finish
            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(100)
            }

            audioTrack.release()

        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing PCM data", e)
            throw e
        }
    }

    /**
     * Resample PCM data from 24kHz to 8kHz using simple decimation
     * Takes every 3rd sample (24000/8000 = 3)
     */
    fun resampleTo8kHz(pcm24kHz: ShortArray, originalSampleRate: Int): ShortArray {
        if (originalSampleRate == 8000) {
            return pcm24kHz // Already 8kHz
        }

        val decimationFactor = originalSampleRate / 8000
        val outputSize = pcm24kHz.size / decimationFactor
        val pcm8kHz = ShortArray(outputSize)

        for (i in 0 until outputSize) {
            pcm8kHz[i] = pcm24kHz[i * decimationFactor]
        }

        return pcm8kHz
    }

    fun createWavHeader(
        totalAudioBytes: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = 36 + totalAudioBytes
        val header = ByteArray(44)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        bb.putInt(4, totalDataLen)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        bb.putInt(16, 16) // Subchunk1Size (PCM)
        bb.putShort(20, 1.toShort()) // AudioFormat (PCM)
        bb.putShort(22, channels.toShort())
        bb.putInt(24, sampleRate)
        bb.putInt(28, byteRate)
        bb.putShort(32, (channels * bitsPerSample / 8).toShort()) // Block align
        bb.putShort(34, bitsPerSample.toShort())

        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        bb.putInt(40, totalAudioBytes)

        return header
    }

    fun createWavFile(pcmData: ShortArray, sampleRate: Int, file: File) {
        try {
            FileOutputStream(file).use { fos ->
                val dataSize = pcmData.size * 2 // 16-bit samples
                val header = createWavHeader(
                    totalAudioBytes = dataSize,
                    sampleRate = sampleRate,
                    channels = 1,
                    bitsPerSample = 16
                )
                fos.write(header)

                // Write PCM data
                val bb = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in pcmData) {
                    bb.putShort(sample)
                }
                fos.write(bb.array())
            }
        } catch (e: IOException) {
            Log.e("WavHelper", "Error creating WAV file", e)
        }
    }

    fun createTempWavFile(pcmData: ShortArray, sampleRate: Int, file: File): File {
        val tempFile = File.createTempFile("temp_chunk", ".wav", file)

        FileOutputStream(tempFile).use { fos ->
            val dataSize = pcmData.size * 2 // 16-bit samples
            val header = createWavHeader(
                totalAudioBytes = dataSize,
                sampleRate = sampleRate,
                channels = 1,
                bitsPerSample = 16
            )
            fos.write(header)

            // Write PCM data
            val bb = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmData) {
                bb.putShort(sample)
            }
            fos.write(bb.array())
        }

        return tempFile
    }

    fun getDuration(file: File): Double {
        return try {
            file.inputStream().use { inputStream ->
                // Skip RIFF header (4 bytes) and file size (4 bytes)
                inputStream.skip(8)

                // Check if it's a WAV file
                val waveHeader = ByteArray(4)
                inputStream.read(waveHeader)
                if (!waveHeader.contentEquals("WAVE".toByteArray())) {
                    return 0.0
                }

                // Find fmt chunk
                while (true) {
                    val chunkId = ByteArray(4)
                    if (inputStream.read(chunkId) != 4) break

                    val chunkSize = ByteArray(4)
                    if (inputStream.read(chunkSize) != 4) break

                    val size = chunkSize.reversedArray().fold(0) { acc, byte ->
                        (acc shl 8) + (byte.toInt() and 0xFF)
                    }

                    if (chunkId.contentEquals("fmt ".toByteArray())) {
                        inputStream.skip(2) // Audio format
                        inputStream.skip(2) // Number of channels

                        // Sample rate (4 bytes, little endian)
                        val sampleRateBytes = ByteArray(4)
                        inputStream.read(sampleRateBytes)
                        val sampleRate = sampleRateBytes.fold(0) { acc, byte ->
                            (acc shl 8) + (byte.toInt() and 0xFF)
                        }

                        // Calculate duration: file_size / sample_rate / channels / bytes_per_sample
                        val dataSize = file.length() - 44 // Approximate data size (minus header)
                        return dataSize.toDouble() / sampleRate / 2 / 2 // Assuming 16-bit stereo
                    } else {
                        inputStream.skip(size.toLong())
                    }
                }
            }
            0.0
        } catch (e: Exception) {
            e.printStackTrace()
            0.0
        }
    }
}