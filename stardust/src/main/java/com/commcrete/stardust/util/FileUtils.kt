package com.commcrete.stardust.util

import android.content.Context
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
}