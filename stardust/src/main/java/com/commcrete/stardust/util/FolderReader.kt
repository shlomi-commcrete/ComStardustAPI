package com.commcrete.stardust.util


import android.content.Context.MODE_PRIVATE
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.commcrete.stardust.room.new_db.internal.normalizeId
import com.commcrete.stardust.room.new_db.internal.normalizeIdOrNull
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream


object FolderReader {

    private const val REQUEST_READ_EXTERNAL_STORAGE = 1020345

    private fun buildHeaderIndexMap(headerRow: Row): Map<String, Int> {
        return headerRow.associate { cell ->
            cell.stringCellValue
                .trim()
                .lowercase() to cell.columnIndex
        }
    }

    private fun readExcelFile(filePath: String) : List<ExcelUser> {
        val demoList = mutableListOf<ExcelUser>()
        try {
            val file = File(filePath)
            val fis = FileInputStream(file)
            val workbook = WorkbookFactory.create(fis)
            val sheet = workbook.getSheetAt(0)

            val rowData = mutableListOf<String>()
            val headerRow = sheet.getRow(0)
                ?: error("Excel file has no header row")

            val headerMap = buildHeaderIndexMap(headerRow)

            fun Row.get(header: String): String {
                val cellIndex = headerMap[header.lowercase()]
                return cellIndex?.let { getCell(cellIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)?.toString() } ?: ""
            }

            for (rowIndex in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue

                val excelUser = ExcelUser(
                    _id       = row.get("id"),
                    _deviceId = row.get("device_id"),
                    name     = row.get("name"),
                    type     = row.get("type"),
                    image    = row.get("image")
                )


                if(excelUser.id.isNotEmpty()){
                    demoList.add(excelUser)
                } else if (excelUser.deviceId.isNotEmpty()) {
                    val excelDeviceUser = excelUser
                    excelDeviceUser.id = excelUser.deviceId
                    excelDeviceUser.deviceId = ""
                    demoList.add(excelDeviceUser)
                }
                // Process rowData as needed
                Timber.tag("readExcelFile").d(rowData.toString())

            }

            workbook.close()
            fis.close()

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return demoList
    }

    fun readFileFromContentUri(uri: Uri): ByteArray? {
        return try {
            DataManager.appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveFileToInternalStorage(fileName: String, data: ByteArray): String? {
        return try {
            val context = DataManager.appContext
            context.openFileOutput(fileName, MODE_PRIVATE).use { fos ->
                fos.write(data)
            }
            File(context.filesDir, fileName).absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveDataToDatabase() {
//        val database = MyDatabase.getDatabase(context)
//        val dao = database.myDao()
//
//        // Assuming you have a data entity that matches the structure
//        val entity = MyEntity(data)
//        dao.insert(entity)
    }

    private fun listFilesInFolder(folderUri: Uri): List<FileInfo> {
        val files = mutableListOf<FileInfo>()
        val contentResolver = DataManager.appContext.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folderUri, DocumentsContract.getTreeDocumentId(folderUri)
        )

        val cursor = contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val documentId = it.getString(0)  // COLUMN_DOCUMENT_ID
                val displayName = it.getString(1) // COLUMN_DISPLAY_NAME
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(
                    folderUri, documentId
                )
                files.add(FileInfo(uri = fileUri, name = displayName))
            }
        }

        return files
    }


    fun processFolder(uri: Uri, onExcelFilesSelected: OnExcelFilesSelected) {
        var excel : Uri? = uri
//        val fileList = listFilesInFolder(context, uri)
//        for (tempFileData in fileList) {
//            when {
//                isExcelFile(context, tempFileData.uri) -> {
//                    println("File at $uri is an Excel file")
//                    excel = tempFileData.uri
//                    // Handle Excel file
//                }
//
//                isImageFile(context, tempFileData.uri) -> {
//                    println("File at $uri is an Image file")
//                    saveImageToInternalStorage(context, tempFileData.uri, tempFileData.name)
//                    // Handle Image file
//                }
//
//                else -> {
//                    println("File at $uri is of an unknown type")
//                    // Handle other file types
//                }
//            }
//        }
        if(excel == null) {
            onExcelFilesSelected.onError()
        }
        excel?.let { processExcelFile(it, onExcelFilesSelected) }
    }

    private fun processExcelFile(uri: Uri, onExcelFilesSelected: OnExcelFilesSelected) {
        val fileData = readFileFromContentUri(uri)
        if (fileData != null) {
            val success = saveFileToInternalStorage("myFile.xlsx", fileData)
            if (success != null) {
                // File saved successfully
                val userList = readExcelFile(success)
                if(userList.isEmpty()) {
                    onExcelFilesSelected.onError()
                } else {
                    onExcelFilesSelected.onGetUsers(fileData, userList)
                }

            } else {
                onExcelFilesSelected.onError()
                // Error saving file
            }
        } else {
            onExcelFilesSelected.onError()
        }
    }



    private fun getPngFilesFromFolder(folderPath: String): List<File> {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            return emptyList()
        }

        return folder.listFiles { file ->
            file.extension.equals("png", ignoreCase = true)
        }?.toList() ?: emptyList()
    }

    private fun savePngFilesToInternalStorage(pngFiles: List<File>) {
        val internalStorageDir = DataManager.appContext.filesDir

        pngFiles.forEach { pngFile ->
            try {
                val destinationFile = File(internalStorageDir, pngFile.name)
                FileInputStream(pngFile).use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        input.copyTo(output)
                    }
                }
                println("Saved ${pngFile.name} to internal storage")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveImageToInternalStorage(uri: Uri, fileName: String): String? {
        try {
            val inputStream: InputStream? = DataManager.appContext.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            val file = File(DataManager.appContext.filesDir, fileName)
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()

            return file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun processPngFiles(folderPath: String) {
        val pngFiles = getPngFilesFromFolder(folderPath)
        if (pngFiles.isNotEmpty()) {
            savePngFilesToInternalStorage(pngFiles)
        } else {
            println("No PNG files found in the folder")
        }
    }

    data class FileInfo(val uri: Uri, val name: String)

    data class ExcelUser (
        private val _id : String = "",
        private val _deviceId : String = "",
        var name : String = "",
        var type : String = "",
        var image : String = "",
        var model : String = "",
        var serial : String = "",
    ) {
        var id: String = normalizeId(_id)
            set(value) { field = normalizeId(value) }

        var deviceId: String = normalizeId(_deviceId)
            set(value) { field = normalizeId(value) }
    }


    val HEADER_ALIASES = mapOf(
        "id" to listOf("id"),
        "device_id" to listOf("device_id", "device id", "deviceid"),
        "name" to listOf("name"),
        "type" to listOf("type"),
        "image" to listOf("image", "img", "picture"),
        "model" to listOf("model"),
        "serial" to listOf("serial")
    )

    fun Row.getByAliases(headerMap: Map<String, Int>, key: String): String? {
        val index = HEADER_ALIASES[key]
            ?.firstNotNullOfOrNull { headerMap[it] }
            ?: return null

        return getCell(index).toString()
    }

    fun getMimeType(uri: Uri): String? {
        return DataManager.appContext.contentResolver.getType(uri)
    }

    fun isExcelFile(uri: Uri): Boolean {
        val mimeType = getMimeType(uri)
        return mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
                mimeType == "application/vnd.ms-excel"
    }

    fun isImageFile(uri: Uri): Boolean {
        val mimeType = getMimeType(uri)
        return mimeType?.startsWith("image/") == true
    }

    interface OnExcelFilesSelected {
        fun onGetUsers(fileData: ByteArray, userList: List<ExcelUser>)
        fun onError ()
    }
}