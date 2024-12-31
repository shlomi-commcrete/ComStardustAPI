package com.commcrete.stardust.util.audio

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

object WavFileExporter {

    fun exportPcmToWav (context: Context, location: String?) {
        if(location == null) {
            return
        }
        val file = getAudioFile(location)
        if(file == null) {
            return
        }
        val wavTempFile = File.createTempFile("temp_audio", ".wav", context.cacheDir)
        val pcmDataSize = file.length().toInt()
        try {
            val wavFileOutputStream = FileOutputStream(wavTempFile)
            val wavHeader = createWavHeader(pcmDataSize, PlayerUtils.sampleRate, 1, 16)

            // Write the header first
            wavFileOutputStream.write(wavHeader)

            // Write the PCM data to the WAV file
            val pcmInputStream = FileInputStream(file)
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (pcmInputStream.read(buffer).also { bytesRead = it } != -1) {
                wavFileOutputStream.write(buffer, 0, bytesRead)
            }

            pcmInputStream.close()
            wavFileOutputStream.close()

            println("PCM file converted to WAV successfully")
            val fileName = extractFileName(wavTempFile.absolutePath)
            saveWavToPublicMusicFolder(context, wavTempFile,fileName)


        }finally {
            if (wavTempFile.exists()) {
                wavTempFile.delete()
                println("Temporary WAV file deleted.")
            }

        }



    }

    private fun getAudioFile( destination: String) : File? {
        val file = File(destination)
        if(file.exists()){
            return file
        }
        return null
    }

    private fun createWavHeader(pcmDataSize: Int, sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val wavHeader = ByteArray(44)

        // ChunkID "RIFF"
        wavHeader[0] = 'R'.toByte()
        wavHeader[1] = 'I'.toByte()
        wavHeader[2] = 'F'.toByte()
        wavHeader[3] = 'F'.toByte()

        // ChunkSize: 36 + pcmDataSize
        val chunkSize = 36 + pcmDataSize
        wavHeader[4] = (chunkSize and 0xff).toByte()
        wavHeader[5] = (chunkSize shr 8 and 0xff).toByte()
        wavHeader[6] = (chunkSize shr 16 and 0xff).toByte()
        wavHeader[7] = (chunkSize shr 24 and 0xff).toByte()

        // Format "WAVE"
        wavHeader[8] = 'W'.toByte()
        wavHeader[9] = 'A'.toByte()
        wavHeader[10] = 'V'.toByte()
        wavHeader[11] = 'E'.toByte()

        // Subchunk1ID "fmt "
        wavHeader[12] = 'f'.toByte()
        wavHeader[13] = 'm'.toByte()
        wavHeader[14] = 't'.toByte()
        wavHeader[15] = ' '.toByte()

        // Subchunk1Size (16 for PCM)
        wavHeader[16] = 16
        wavHeader[17] = 0
        wavHeader[18] = 0
        wavHeader[19] = 0

        // AudioFormat (1 for PCM)
        wavHeader[20] = 1
        wavHeader[21] = 0

        // NumChannels
        wavHeader[22] = channels.toByte()
        wavHeader[23] = 0

        // SampleRate
        wavHeader[24] = (sampleRate and 0xff).toByte()
        wavHeader[25] = (sampleRate shr 8 and 0xff).toByte()
        wavHeader[26] = (sampleRate shr 16 and 0xff).toByte()
        wavHeader[27] = (sampleRate shr 24 and 0xff).toByte()

        // ByteRate (SampleRate * NumChannels * BitsPerSample / 8)
        wavHeader[28] = (byteRate and 0xff).toByte()
        wavHeader[29] = (byteRate shr 8 and 0xff).toByte()
        wavHeader[30] = (byteRate shr 16 and 0xff).toByte()
        wavHeader[31] = (byteRate shr 24 and 0xff).toByte()

        // BlockAlign (NumChannels * BitsPerSample / 8)
        wavHeader[32] = ((channels * (bitDepth / 8)) and 0xff).toByte()
        wavHeader[33] = 0

        // BitsPerSample
        wavHeader[34] = bitDepth.toByte()
        wavHeader[35] = 0

        // Subchunk2ID "data"
        wavHeader[36] = 'd'.toByte()
        wavHeader[37] = 'a'.toByte()
        wavHeader[38] = 't'.toByte()
        wavHeader[39] = 'a'.toByte()

        // Subchunk2Size (pcmDataSize)
        wavHeader[40] = (pcmDataSize and 0xff).toByte()
        wavHeader[41] = (pcmDataSize shr 8 and 0xff).toByte()
        wavHeader[42] = (pcmDataSize shr 16 and 0xff).toByte()
        wavHeader[43] = (pcmDataSize shr 24 and 0xff).toByte()

        return wavHeader
    }

    private fun extractFileName(filePath: String): String {
        return File(filePath).name // This will extract "temp_audio1559048311799254263.wav" from the full path
    }

    private fun saveWavToPublicMusicFolder(context: Context, wavFile: File, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(wavFile).use { inputStream ->
                        val buffer = ByteArray(1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                }

                // Mark the file as complete
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                println("WAV file saved to public Music folder: $uri")
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            println("Failed to save file to public Music folder.")
        }
    }


    private fun saveWavToExternalStorage(context: Context, wavFile: File) {
        // Ensure the external storage is available
        val externalStorageState = Environment.getExternalStorageState()
        if (externalStorageState != Environment.MEDIA_MOUNTED) {
            println("External storage is not available.")
            return
        }

        // Get external storage directory
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)

        if (externalDir != null && externalDir.exists()) {
            val externalWavFile = File(externalDir, "exported_audio.wav")

            try {
                val inputStream = FileInputStream(wavFile)
                val outputStream = FileOutputStream(externalWavFile)

                // Copy WAV data to the external file
                val buffer = ByteArray(1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }

                inputStream.close()
                outputStream.close()

                println("WAV file saved to external storage: ${externalWavFile.absolutePath}")

            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            println("External directory is not available.")
        }
    }



}