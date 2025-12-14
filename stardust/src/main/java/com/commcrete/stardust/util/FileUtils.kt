package com.commcrete.stardust.util

import android.content.ContentValues
import android.content.Context
import androidx.core.content.FileProvider
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader


object FileUtils {
    fun createFile(context : Context, folderName : String = "logs"
                           , fileName : String, fileType : String = ".txt") : File {
        val directory = File("${context.filesDir}/$folderName")
        val newFile = File("${context.filesDir}/$folderName/$fileName$fileType")
        if(!directory.exists()){
            directory.mkdir()
        }
        if(!newFile.exists()){
            newFile.createNewFile()
        }
        return newFile
    }

    fun saveToFile(filePath : String , data: ByteArray , append : Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            var os: FileOutputStream? = null
            try {
                os = FileOutputStream(filePath,append)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }
            os?.write(data)
            try {
                os?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun openFile(context : Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileUri = getContentUriForFile(context, file)

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(fileUri, getMimeType(file))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(intent)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error opening file!", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun openImage(context : Context, imagePath: String) {
        val imageFile = File(imagePath)
        if (!imageFile.exists()) {
            Toast.makeText(context, "Image not found!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val imageUri = getContentUriForFile(context, imageFile)

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(imageUri, "image/*")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error opening image!", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun getContentUriForFile(context: Context, file: File): Uri {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(file))
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS
        ) // Saves to Downloads folder

        val resolver = context.contentResolver
        val contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(contentUri, values)

        try {
            resolver.openOutputStream(uri!!).use { out ->
                FileInputStream(file).use { `in` ->
                    val buffer = ByteArray(1024)
                    var bytesRead: Int
                    while ((`in`.read(buffer).also { bytesRead = it }) != -1) {
                        out!!.write(buffer, 0, bytesRead)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return uri!!
    }

    fun getMimeType(file: File): String {
        val name = file.name
        var extension = ""
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex != -1 && dotIndex < name.length - 1) {
            extension = name.substring(dotIndex + 1).lowercase()
        }
        // Special cases
        // Fallbacks
        // Let Android show anything that can handle it
        when (extension) {
            "log" -> {
    // Treat .log as plain text
                return "text/plain"
            }

            "xls" -> {
                return "application/vnd.ms-excel"
            }

            "xlsx" -> {
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            }
            // Default resolution via MimeTypeMap
            else -> {
                var mime: String? = null
                if (!extension.isEmpty()) {
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
                if (mime == null) {
                    // Fallbacks
                    if (name.endsWith(".log")) {
                        return "text/plain"
                    }
                    return "*/*" // Let Android show anything that can handle it
                }
                return mime
            }
        }
    }

    fun clearFile(context : Context, folderName : String = "logs"
                  , fileName : String, fileType : String = ".txt"){
        val file = File("${context.filesDir}/$folderName/$fileName$fileType")
        if(file.exists()){
            file.delete()
        }
    }

    fun readFile (context : Context, folderName : String = "logs"
                  , fileName : String, fileType : String = ".txt"): String {
        val file = File("${context.filesDir}/$folderName/$fileName$fileType")
        val stringBuilder = StringBuilder()

        try {
            FileInputStream(file).use { fileInputStream ->
                InputStreamReader(fileInputStream).use { inputStreamReader ->
                    BufferedReader(inputStreamReader).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stringBuilder.append(line).append("\n")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // Handle the exception
        }

        return stringBuilder.toString()

    }

    fun readRawResourceAsString(context: Context, resId: Int): String {
        return context.resources.openRawResource(resId).use { inputStream ->
            inputStream.bufferedReader().use(BufferedReader::readText)
        }
    }


    fun readRawResourceAsByteArray(context: Context, resId: Int): ByteArray {
        return context.resources.openRawResource(resId).use { inputStream ->
            inputStream.readBytes()
        }
    }

    fun getMimeType(context: Context, file: File): String {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }
}