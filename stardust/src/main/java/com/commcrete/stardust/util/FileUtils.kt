package com.commcrete.stardust.util

import android.content.Context
import androidx.core.content.FileProvider
import android.net.Uri
import android.webkit.MimeTypeMap
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
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.FileSender.Companion.calculateSendTime
import java.io.BufferedOutputStream
import java.io.FileWriter
import java.io.OutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
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

    suspend fun exportDataAsZip(zipFile: MediaStoreFile, dataToZip: List<ZipItem>, onExportFinished: (zipFile: MediaStoreFile) -> Unit, onError: (e: Exception) -> Unit) {
        try {
            zipData(dataToZip, zipFile.outputStream) {
                dataToZip.forEach { item ->
                    if(item.removeWhenZipped) {
                        item.files.forEach { if(it.exists()) it.delete() }
                    }
                }
                onExportFinished.invoke(zipFile)
            }
        } catch (e: Exception) {
            onError.invoke(e)
        }
    }

    fun exportAppLogcat(
        context: Context,
        fileName: String = "app_logs_${System.currentTimeMillis()}.logcat"
    ): File {

        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        val logFile = File(outputDir, fileName)

        val pid = android.os.Process.myPid()

        val command = arrayOf(
            "logcat",
            "--pid=$pid",
            "-v", "time",
            "-f", logFile.absolutePath,  // write directly to file
            "-d"                         // dump and exit
        )

        Runtime.getRuntime().exec(command)?.waitFor()

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
        val appDb = com.commcrete.stardust.room.new_db.AppDatabase.getDatabase(context)
        val appDbName = appDb.openHelper.databaseName ?: "app_database"
        val appSupportDb = appDb.openHelper.writableDatabase

        return exportMultipleDatabasesToCsv(
            context = context,
            databases = mapOf(appDbName to appSupportDb),
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

    fun decompressTextFile(inputFile: File, outputFile: File) {
        FileInputStream(inputFile).use { fis ->
            GZIPInputStream(fis).use { gzis ->
                FileOutputStream(outputFile).use { fos ->
                    gzis.copyTo(fos)
                }
            }
        }
    }

    fun trimUntilUnderscore(input: String): String {
        return input.substringAfter("_")
    }


    enum class FileType(val bitCode : Int) {
        File(0),
        Image(1);

        fun relatedFunctionalityType() : FunctionalityType = when(this) {
            File -> FunctionalityType.FILE
            Image -> FunctionalityType.IMAGE
        }
    }

    sealed class FileTransferData(
        open val id: String = UUID.randomUUID().toString(),
        open val chatId: String,
        open val fileType: FileType,
        open val numOfPackages: Int,
        open val timestamp: Long = System.currentTimeMillis()) {

        fun remainingTransferTimeAsString(progress: Int): String {
            // Calculate remaining packages: progress is percentage (0-100)
            val completedPackages = (numOfPackages * progress) / 100.0
            val remainingPackages = (numOfPackages - completedPackages).toInt()
            return calculateSendTime(remainingPackages, fileType.relatedFunctionalityType())
        }

        data class Send(
            override val id: String = UUID.randomUUID().toString(),
            override val chatId: String,
            val stardustAPIPackage: StardustAPIPackage,
            val file: File,
            override val fileType: FileType,
            val destinationName: String,
            override val numOfPackages: Int,
            override val timestamp: Long = System.currentTimeMillis()
        ) : FileTransferData(
            id = id,
            chatId = chatId,
            fileType = fileType,
            numOfPackages = numOfPackages,
            timestamp = timestamp)

        data class Receive(
            override val id: String = UUID.randomUUID().toString(),
            override val chatId: String,
            val senderID: String,
            val chatName: String,
            val realSenderName: String = senderID,
            val fileName: String,
            val fileEnding: String,
            override val fileType: FileType,
            override val numOfPackages: Int,
            val deliveryChannel: StardustControlByte.StardustDeliveryType,
            override val timestamp: Long = System.currentTimeMillis(),
        ) : FileTransferData(
            id = id,
            chatId = chatId,
            fileType = fileType,
            numOfPackages = numOfPackages,
            timestamp = timestamp)
    }


    data class ZipItem(
        val files: List<File>,         // files or directories to include
        val wrapperFolder: String? = null, // optional folder name inside the ZIP
        val removeWhenZipped: Boolean = false
    )

    class MediaStoreFile(val uri: Uri, val outputStream: OutputStream)

}