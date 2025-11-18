package com.commcrete.stardust.util

import android.content.Context
import android.widget.Toast
import com.commcrete.stardust.BuildConfig
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException

class TextLogger(private val context: Context) {

    // Constant file name and location (app's internal storage)
    private val logFile: File = File(context.filesDir, "text_log_files.txt")

    fun logText(text: String) {
        try {
            // Append to file with a newline
            FileWriter(logFile, true).use { writer ->
                writer.appendLine(text)
            }

            // Show long toast with the text
            Scopes.getMainCoroutine().launch {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(context, "Error writing to file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun getFilePath(): String = logFile.absolutePath
}