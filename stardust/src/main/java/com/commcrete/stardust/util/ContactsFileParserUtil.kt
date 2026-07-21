package com.commcrete.stardust.util


import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.SharedContactSummary
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.Locale

object ContactsFileParserUtil {

    private const val CSV_NEWLINE = "\r\n"
    private const val UTF8_BOM = "﻿"

    private data class ContactCsvRow(
        val id: String,
        val deviceId: String,
        val name: String,
        val type: String,
        val image: String,
    )

    /**
     * Exports contacts to a CSV file matching the legacy TS format:
     *
     * ID,Device_ID,Name,Type,Image
     * ...rows...
     *
     * #HASH:<base36>
     * #COUNT:<n>
     * #CREATED:<iso-utc>
     * #CREATOR:<creator>
     */
    @Throws(IOException::class)
    fun exportContactsToCsv(
        context: Context,
        contacts: Set<FullContactData>,
        fileName: String = "contacts_export",
        creatorName: String = "Contacts System",
    ): FileUtils.MediaStoreFile {
        val content = buildContactsCsvContent(contacts, creatorName)
        val finalName = ensureCsvExtension(fileName)
        val mediaFile = createCsvInDownloads(context, finalName)
        mediaFile.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
            writer.flush()
        }
        return mediaFile
    }

    /**
     * Exports [contacts] to CSV and returns a local [File] in the app cache, ready to
     * be sent as a chat attachment. Encapsulates the export + the MediaStore→File copy
     * a sharing caller would otherwise do inline.
     */
    @Throws(IOException::class)
    fun createContactsShareFile(
        context: Context,
        contacts: Set<FullContactData>,
        fileName: String,
    ): File {
        val mediaFile = exportContactsToCsv(context = context, contacts = contacts, fileName = fileName)
        val shareFile = File(context.cacheDir, "contact_share.csv")
        context.contentResolver.openInputStream(mediaFile.uri).use { input ->
            shareFile.outputStream().use { output -> input?.copyTo(output) }
        }
        return shareFile
    }

    /** Pure builder for tests/callers that only need the CSV payload. */
    fun buildContactsCsvContent(
        contacts: Set<FullContactData>,
        creatorName: String = "Contacts System",
    ): String {
        val rows = contacts
            .map(::mapContactToCsvRow)
            .filter { it.name.isNotBlank() }
            .sortedWith(
                compareBy<ContactCsvRow> { it.name.lowercase(Locale.getDefault()) }
                    .thenBy { it.id.lowercase(Locale.getDefault()) }
                    .thenBy { it.deviceId.lowercase(Locale.getDefault()) }
            )

        val csvDataContent = buildCsvDataContent(rows)
        val dataHash = generateCsvHash(csvDataContent)
        val timestamp = Instant.now().toString()

        val metadata = listOf(
            "",
            "#HASH:$dataHash",
            "#COUNT:${rows.size}",
            "#CREATED:$timestamp",
            "#CREATOR:$creatorName",
        )

        return UTF8_BOM + csvDataContent + CSV_NEWLINE + metadata.joinToString(CSV_NEWLINE)
    }

    private fun mapContactToCsvRow(contact: FullContactData): ContactCsvRow {
        val entity = contact.contact
        val image = normalizeImageName(entity.image.orEmpty())
        return when (contact) {
            is FullContactData.User -> ContactCsvRow(
                id = contact.userId,
                deviceId = contact.devices.firstOrNull()?.id.orEmpty(),
                name = entity.name,
                type = "app",
                image = image,
            )
            is FullContactData.Group -> ContactCsvRow(
                id = contact.groupId,
                deviceId = "",
                name = entity.name,
                type = "group",
                image = image,
            )
            is FullContactData.Device -> ContactCsvRow(
                id = "",
                deviceId = contact.deviceId,
                name = entity.name,
                type = "device",
                image = image,
            )
        }
    }

    private fun buildCsvDataContent(rows: List<ContactCsvRow>): String {
        val headers = listOf("ID", "Device_ID", "Name", "Type", "Image")
        val textFields = setOf("ID", "Device_ID", "Serial")

        val body = rows.map { row ->
            headers.joinToString(",") { header ->
                val value = when (header) {
                    "ID" -> row.id
                    "Device_ID" -> row.deviceId
                    "Name" -> row.name
                    "Type" -> row.type
                    "Image" -> row.image
                    else -> ""
                }
                encodeCsvValue(value, forceExcelText = textFields.contains(header))
            }
        }
        return listOf(headers.joinToString(","))
            .plus(body)
            .joinToString(CSV_NEWLINE)
    }

    private fun encodeCsvValue(value: String, forceExcelText: Boolean): String {
        if (forceExcelText && value.isNotEmpty()) return "=\"$value\""
        val mustQuote = value.contains(',') || value.contains('"') || value.contains('\n')
        return if (mustQuote) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    /** Matches the simple JS hashing logic used by the source TS app. */
    private fun generateCsvHash(csvDataContent: String): String {
        val normalized = csvDataContent.replace(CSV_NEWLINE, "\n").trim()
        var hash = 0
        normalized.forEach { ch ->
            hash = (hash shl 5) - hash + ch.code
        }
        val positive = kotlin.math.abs(hash.toLong())
        return positive.toString(36)
    }

    private fun normalizeImageName(raw: String): String {
        val known = setOf(
            "User", "Group", "Device", "Vehicle", "Helicopter",
            "Aircraft", "Marine_Vessel", "Tank", "HQ",
        )
        if (known.contains(raw)) return raw

        return when (raw.trim().lowercase(Locale.getDefault())) {
            "user" -> "User"
            "group", "people" -> "Group"
            "device" -> "Device"
            "vehicle" -> "Vehicle"
            "helicopter" -> "Helicopter"
            "aircraft" -> "Aircraft"
            "marine_vessel", "marine vessel", "vessel" -> "Marine_Vessel"
            "tank" -> "Tank"
            "hq" -> "HQ"
            else -> "User"
        }
    }

    private fun ensureCsvExtension(fileName: String): String =
        if (fileName.lowercase(Locale.getDefault()).endsWith(".csv")) fileName else "$fileName.csv"

    @Throws(IOException::class)
    private fun createCsvInDownloads(context: Context, fileName: String): FileUtils.MediaStoreFile {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createCsvMediaStore(context, fileName)
        } else {
            createCsvLegacy(fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class)
    private fun createCsvMediaStore(context: Context, fileName: String): FileUtils.MediaStoreFile {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create CSV file in Downloads")
        val out = resolver.openOutputStream(uri, "w")
            ?: throw IOException("Failed to open CSV output stream")
        return FileUtils.MediaStoreFile(uri, out)
    }

    @Throws(IOException::class)
    private fun createCsvLegacy(fileName: String): FileUtils.MediaStoreFile {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val file = File(downloadsDir, fileName)
        return FileUtils.MediaStoreFile(uri = file.toUri(), outputStream = FileOutputStream(file))
    }

    suspend fun saveContactsToDatabase(rawData: List<FolderReader.ExcelUser>) {
        val contacts = parseContactsForDb(rawData)
        DataManager.getAppRepo().insertContactsWithChats(contacts)
    }

    fun registerSelectedUser(selectedUser: FolderReader.ExcelUser): Boolean {

        if(selectedUser.id.isEmpty()) { return false }

        val newUser = RegisterUser(
            displayName = selectedUser.name,
            _deviceId = selectedUser.deviceId,
            _appId = selectedUser.id
        )
        SharedPreferencesUtil.setAppUser(newUser)

        return true

    }

    /**
     * Parses a contacts CSV (the format produced by [exportContactsToCsv]) back into
     * a list of [FullContactData].
     *
     * The header row, trailing metadata lines (`#HASH`, `#COUNT`, ...) and blank lines
     * are ignored. Excel text-guarded values (`="0000032b"`) and quoted values are
     * decoded before mapping.
     */
    fun parseContactsFromCsv(csvContent: String): List<FullContactData> {
        val excelUsers = parseCsvToExcelUsers(csvContent)
        return parseContactsForDb(excelUsers)
    }

    /**
     * Reads a contacts CSV [file] from disk and parses it into a list of [FullContactData].
     * Returns an empty list if the file cannot be read.
     */
    fun parseContactsFromCsv(file: File): List<FullContactData> {
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return emptyList()
        }
        return parseContactsFromCsv(content)
    }

    /**
     * Reads a contacts CSV from a content [uri] (e.g. a file picked by the user) and parses it
     * into a list of [FullContactData]. Returns an empty list if the stream cannot be read.
     */
    fun parseContactsFromCsv(uri: Uri): List<FullContactData> {
        val content = try {
            DataManager.appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        } ?: return emptyList()
        return parseContactsFromCsv(content)
    }

    /**
     * Parses a contacts CSV into a compact [SharedContactSummary] for caching in a
     * message's [com.commcrete.stardust.room.new_db.message.FileSummary]. This is the
     * single parse: the message bubble then renders from the summary without touching
     * the file again. Blank-named rows are dropped, matching [buildContactsCsvContent].
     */
    fun buildSharedContactSummary(file: File): SharedContactSummary =
        parseContactsFromCsv(file).toSharedContactSummary()

    fun buildSharedContactSummary(csvContent: String): SharedContactSummary =
        parseContactsFromCsv(csvContent).toSharedContactSummary()

    private fun List<FullContactData>.toSharedContactSummary(): SharedContactSummary =
        SharedContactSummary(totalCount = size, contacts = this)

    private fun parseCsvToExcelUsers(csvContent: String): List<FolderReader.ExcelUser> {
        val lines = csvContent
            .removePrefix(UTF8_BOM)
            .split("\r\n", "\n")

        val headerIndex = lines.indexOfFirst { it.isNotBlank() && !it.startsWith("#") }
        if (headerIndex == -1) return emptyList()

        val headerMap = splitCsvLine(lines[headerIndex])
            .mapIndexed { index, header -> decodeCsvField(header).trim().lowercase(Locale.getDefault()) to index }
            .toMap()

        fun List<String>.value(header: String): String {
            val index = headerMap[header] ?: return ""
            return decodeCsvField(getOrElse(index) { "" })
        }

        val users = mutableListOf<FolderReader.ExcelUser>()
        for (i in (headerIndex + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith("#")) continue

            val fields = splitCsvLine(line)
            val user = FolderReader.ExcelUser(
                _id = fields.value("id"),
                _deviceId = fields.value("device_id"),
                name = fields.value("name"),
                type = fields.value("type"),
                image = fields.value("image"),
            )
            users.add(user)
        }
        return users
    }

    /** Splits a single CSV line into raw fields, honoring double-quoted sections and `""` escapes. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        current.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> current.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    /** Strips the leading `=` used by the Excel text guard (`="..."`) left over after quote handling. */
    private fun decodeCsvField(field: String): String = field.removePrefix("=")

    fun parseContactsForDb(contacts: List<FolderReader.ExcelUser>): List<FullContactData> {
        val result = mutableListOf<FullContactData>()

        for (contact in contacts) {
            val appId = contact.id
            val deviceId = contact.deviceId
            val name = contact.name
            val image = contact.image

            val type = when(contact.type.lowercase()) {
                "app" -> ContactType.USER
                "group" -> ContactType.GROUP
                else -> ContactType.DEVICE
            }

            val fullContact = when (type) {
                ContactType.USER -> FullContactData.Companion.createUserContact(
                    name = name,
                    image = image,
                    userId = appId,
                    deviceId = deviceId,
                    model = contact.model,
                    serial = contact.serial,
                )
                ContactType.GROUP -> FullContactData.Companion.createGroupContact(
                    name = name,
                    image = image,
                    groupId = appId,
                )
                ContactType.DEVICE -> FullContactData.Companion.createDeviceContact(
                    name = name,
                    image = image,
                    deviceId = deviceId,
                    model = contact.model,
                    serial = contact.serial,
                )
            }

            fullContact?.let { result.add(it) }
        }
        return result.sortedBy { it.contact.name }
    }


    private fun onFinishLoadData() {
        if(!BleManager.isBluetoothConnected() && !BleManager.isUSBConnected) { return }
        StardustInitConnectionHandler.start()
    }

}