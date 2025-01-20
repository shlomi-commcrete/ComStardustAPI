package com.commcrete.stardust.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.commcrete.bittell.util.demo.DemoDataUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFCell
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream


object FolderReader {

    private const val REQUEST_READ_EXTERNAL_STORAGE = 1020345

    private fun readExcelFile(filePath: String) : List<ExcelUser> {
        val demoList = mutableListOf<ExcelUser>()
        try {
            val file = File(filePath)
            val fis = FileInputStream(file)
            val workbook = WorkbookFactory.create(fis)
            val sheet = workbook.getSheetAt(0)

            for (row in sheet) {
                val rowData = mutableListOf<String>()
                var loop = 0
                val excelUser = ExcelUser()
                for (cell in row) {
                    Timber.tag("readExcelFile").d(cell.toString())
                    if(cell.toString() == "ID"){
                        break
                    }
                    if(loop == 0) {
                        excelUser.id = (cell as XSSFCell).stringCellValue
                    }
                    if(loop == 1) {
                        excelUser.deviceId = (cell as XSSFCell).stringCellValue
                    }
                    if(loop == 2) {
                        excelUser.name = cell.toString()
                    }
                    if(loop == 3) {
                        excelUser.type = cell.toString()
                    }
                    if(loop == 4) {
                        excelUser.image = cell.toString()
                    }
                    rowData.add(cell.toString())
                    loop++
                }
                if(excelUser.id.isNotEmpty()){
                    demoList.add(excelUser)
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

    fun readFileFromContentUri(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveFileToInternalStorage(context: Context, fileName: String, data: ByteArray): String? {
        return try {
            context.openFileOutput(fileName, Context.MODE_PRIVATE).use { fos ->
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

    private fun listFilesInFolder(context: Context, folderUri: Uri): List<FileInfo> {
        val files = mutableListOf<FileInfo>()
        val contentResolver = context.contentResolver
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


    fun processFolder(uri: Uri, context: Context, onExcelFilesSelected: OnExcelFilesSelected) {
        var excel : Uri? = null
        val fileList = listFilesInFolder(context, uri)
        for (tempFileData in fileList) {
            when {
                isExcelFile(context, tempFileData.uri) -> {
                    println("File at $uri is an Excel file")
                    excel = tempFileData.uri
                    // Handle Excel file
                }

                isImageFile(context, tempFileData.uri) -> {
                    println("File at $uri is an Image file")
                    saveImageToInternalStorage(context, tempFileData.uri, tempFileData.name)
                    // Handle Image file
                }

                else -> {
                    println("File at $uri is of an unknown type")
                    // Handle other file types
                }
            }
        }
        excel?.let { processExcelFile(it, context, onExcelFilesSelected) }
    }

    private fun processExcelFile(uri: Uri, context: Context, onExcelFilesSelected: OnExcelFilesSelected) {
        val fileData = readFileFromContentUri(context, uri)
        if (fileData != null) {
            val success = saveFileToInternalStorage(context, "myFile.xlsx", fileData)
            if (success != null) {
                // File saved successfully
                val userList = readExcelFile(success)
                onExcelFilesSelected.onGetUsers(userList)

            } else {
                // Error saving file
            }
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

    private fun savePngFilesToInternalStorage(context: Context, pngFiles: List<File>) {
        val internalStorageDir = context.filesDir

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

    fun saveImageToInternalStorage(context: Context, uri: Uri, fileName: String): String? {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            val file = File(context.filesDir, fileName)
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

    fun processPngFiles(context: Context, folderPath: String) {
        val pngFiles = getPngFilesFromFolder(folderPath)
        if (pngFiles.isNotEmpty()) {
            savePngFilesToInternalStorage(context, pngFiles)
        } else {
            println("No PNG files found in the folder")
        }
    }

    data class FileInfo(val uri: Uri, val name: String)

    data class ExcelUser (
        var id : String = "",
        var deviceId : String = "",
        var name : String = "",
        var type : String = "",
        var image : String = "",
    )

    fun getMimeType(context: Context, uri: Uri): String? {
        return context.contentResolver.getType(uri)
    }

    fun isExcelFile(context: Context, uri: Uri): Boolean {
        val mimeType = getMimeType(context, uri)
        return mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
                mimeType == "application/vnd.ms-excel"
    }

    fun isImageFile(context: Context, uri: Uri): Boolean {
        val mimeType = getMimeType(context, uri)
        return mimeType?.startsWith("image/") == true
    }

    interface OnExcelFilesSelected {
        fun onGetUsers(userList: List<ExcelUser>)
    }
}