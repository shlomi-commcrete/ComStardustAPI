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
import androidx.sqlite.db.SupportSQLiteDatabase
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.contacts.ContactsDatabase
import com.commcrete.stardust.room.messages.MessagesDatabase
import java.io.BufferedOutputStream
import java.io.FileWriter
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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


    fun getContentUriForFile(context: Context, file: File): Uri {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(file))
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS
        ) // Saves to Downloads folder

        val resolver = context.contentResolver
        val contentUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
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

    fun getAllChatFilesDirs(context: Context, chatIDs: List<String>): List<File> {
        val rootDir = context.filesDir

        return rootDir.listFiles()
            ?.filter { it.isDirectory && chatIDs.contains(it.name)}
            ?.sortedBy { it.name }
            ?: emptyList()
    }


    fun exportAppLogcat(
        context: Context,
        fileName: String = "app_logs_${System.currentTimeMillis()}.logcat"
    ): File {

        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        val logFile = File(outputDir, fileName)

        val pid = android.os.Process.myPid()

        // Clear buffer first (optional but recommended)
        Runtime.getRuntime().exec("logcat -c")

        val command = arrayOf(
            "logcat",
            "--pid=$pid",
            "-d",                 // dump and exit
            "-v", "time"          // timestamped format
        )

        val process = Runtime.getRuntime().exec(command)

        process.inputStream.use { input ->
            FileOutputStream(logFile).use { output ->
                input.copyTo(output)
            }
        }

        return logFile
    }

    fun zipData(
        sourceItems: List<ZipItem>,
        outputStream: OutputStream,
        onFinished: (() -> Unit)? = null
    ) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
            sourceItems.forEach { items ->
                items.files.forEach { file ->
                    if (file.exists()) {
                        if (file.isDirectory) {
                            zipDirRecursive(file, file, zipOut, items.wrapperFolder)
                        } else {
                            zipSingleFile(file, zipOut, items.wrapperFolder)
                        }
                        if(items.removeWhenZipped) file.delete()
                    }
                }
            }
        }
        onFinished?.invoke()
    }

    fun zipSingleFile(
        file: File,
        zipOut: ZipOutputStream,
        wrapperFolder: String? = null
    ) {
        if (!file.exists() || !file.isFile) return

        // Build the entry name, optionally adding the wrapper folder
        val entryName = if (wrapperFolder != null) {
            "$wrapperFolder/${file.name}"
        } else {
            file.name
        }

        zipOut.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input ->
            input.copyTo(zipOut)
        }
        zipOut.closeEntry()
    }

    fun zipDirRecursive(
        rootDir: File,
        currentFile: File,
        zipOut: ZipOutputStream,
        wrapperFolder: String? = null
    ) {
        val files = currentFile.listFiles() ?: return

        for (file in files) {
            val relativePath = file.relativeTo(rootDir).path
            val entryName = if (wrapperFolder != null) {
                "$wrapperFolder/${currentFile.name}/$relativePath"
            } else {
                "$relativePath"
            }

            if (file.isDirectory) {
                zipDirRecursive(rootDir, file, zipOut, wrapperFolder)
            } else {
                zipOut.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }
        }
    }

    fun zipFoldersWithWrapper(
        sourceDirs: List<File>,
        wrapperFolder: String,
        outputStream: OutputStream
    ) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
            sourceDirs.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    zipDirRecursive(dir, dir, zipOut, wrapperFolder)
                }
            }
        }
    }

    fun zipFolder(exportDir: File): File {
        val zipFile = File(exportDir.parent, "${exportDir.name}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            exportDir.listFiles()?.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().copyTo(zip)
                zip.closeEntry()
            }
        }
        return zipFile
    }

    fun exportAllDatabasesToCsv(
        context: Context,
        exportFolderName: String = "databases_export"
    ): File {
        val data = listOf(
            ContactsDatabase.getDatabase(context),
            ChatsDatabase.getDatabase(context),
            MessagesDatabase.getDatabase(context),
        ).associate {
            "${it.openHelper.databaseName}" to it.openHelper.readableDatabase
        }

        return  exportMultipleDatabasesToCsv(
            context = context,
            databases = data,
            exportFolderName = exportFolderName
        )
    }

    /**
     * Export multiple databases to CSV files.
     *
     * @param context App context
     * @param databases Map of database name -> SQLiteDatabase
     * @param exportFolderName Name of folder under filesDir to store CSVs
     * @return The root export directory
     */
    fun exportMultipleDatabasesToCsv(
        context: Context,
        databases: Map<String, SupportSQLiteDatabase>,
        exportFolderName: String = "databases"
    ): File {
        val exportRoot = File(context.getExternalFilesDir(null), exportFolderName)
        if (!exportRoot.exists()) exportRoot.mkdirs()

        databases.forEach { (dbName, db) ->
            val dbExportDir = File(exportRoot, dbName)
            if (!dbExportDir.exists()) dbExportDir.mkdirs()

            exportDatabaseToCsv(db, dbExportDir)
        }

        return exportRoot
    }

    /**
     * Get (or create) a temporary directory for your app.
     * This directory is inside the cache folder and can be cleared anytime.
     */
    fun getTempDir(context: Context): File {
        val tempDir = File(context.cacheDir, "temp_files")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }

    /**
     * Creates a temporary file, passes it to [block], then deletes the file automatically.
     * Example usage:
     * ```
     * TempFileUtil.withTempFile(context, ".txt") { file ->
     *     file.writeText("Hello")
     *     // do stuff with file
     * } // file is deleted automatically here
     * ```
     */
    fun <T> withTempFile(
        context: Context,
        prefix: String = "temp_",
        suffix: String? = null,
        block: (File) -> T
    ): File {
        val tempFile = File.createTempFile(prefix, suffix, getTempDir(context))
        try {
            block(tempFile)
        } finally {
            tempFile.delete()
        }
        return tempFile
    }

    /**
     * Create a temporary file inside the temp directory.
     * The file will have a unique name and optional extension.
     */
    fun createTempFile(context: Context, prefix: String = "temp_", suffix: String? = null): File {
        return File.createTempFile(prefix, suffix, getTempDir(context))
    }

    /**
     * Delete all temp files in the temp directory.
     */
    fun clearTempDir(context: Context) {
        val tempDir = getTempDir(context)
        tempDir.listFiles()?.forEach { it.delete() }
    }

    private fun exportDatabaseToCsv(database: SupportSQLiteDatabase, exportDir: File) {
        val tableCursor = database.query(
            """
        SELECT name FROM sqlite_master
        WHERE type='table'
        AND name NOT LIKE 'sqlite_%'
        AND name != 'android_metadata'
        """.trimIndent()
        )

        tableCursor.use { cursor ->
            while (cursor.moveToNext()) {
                val tableName = cursor.getString(0)
                exportTableToCsv(database, tableName, exportDir)
            }
        }
    }

    private fun exportTableToCsv(
        database: SupportSQLiteDatabase,
        tableName: String,
        exportDir: File
    ) {
        val csvFile = File(exportDir, "$tableName.csv")
        val writer = FileWriter(csvFile)

        val cursor = database.query("SELECT * FROM $tableName")
        cursor.use {
            val columns = it.columnNames
            // Header
            writer.append(columns.joinToString(","))
            writer.append("\n")

            while (it.moveToNext()) {
                val row = buildString {
                    for (i in columns.indices) {
                        val value = it.getString(i)
                            ?.replace("\"", "\"\"")
                            ?.replace("\n", " ")
                            ?: ""
                        append("\"$value\"")
                        if (i < columns.size - 1) append(",")
                    }
                }
                writer.append(row)
                writer.append("\n")
            }
        }

        writer.flush()
        writer.close()
    }


    enum class FileType {
        UNKNOWN,
        FILE,
        IMAGE
    }

    data class ZipItem(
        val files: List<File>,         // files or directories to include
        val wrapperFolder: String? = null, // optional folder name inside the ZIP
        val removeWhenZipped: Boolean = false
    )
}