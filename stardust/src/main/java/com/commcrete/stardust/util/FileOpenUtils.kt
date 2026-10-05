package com.commcrete.stardust.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opening an attachment — received or sent — in another app.
 *
 * The type on the VIEW intent is what decides which apps are offered, not the type the
 * URI's provider reports. A spreadsheet opened as `text/plain` is offered to text
 * viewers only, never to Sheets or an Excel viewer, however correct the MediaStore row
 * behind it is. So the type is always set explicitly, from the file's ending.
 */
object FileOpenUtils {

    private const val TAG = "FileOpenUtils"

    /** Intent type for an ending nothing knows: matches every app that declares any type. */
    const val ANY_MIME_TYPE = "*/*"

    private const val CSV = "text/csv"
    private const val PLAIN_TEXT = "text/plain"

    /**
     * Pinned rather than left to [MimeTypeMap], which differs across Android versions —
     * csv comes back as `text/comma-separated-values` on some and `text/csv` on others,
     * and spreadsheet apps do not all register both.
     */
    private val KNOWN_MIME_TYPES = mapOf(
        "csv" to CSV,
        "tsv" to "text/tab-separated-values",
        "txt" to PLAIN_TEXT,
        "log" to PLAIN_TEXT,
        "json" to "application/json",
        "xml" to "text/xml",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "kml" to "application/vnd.google-earth.kml+xml",
        "kmz" to "application/vnd.google-earth.kmz",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
    )

    /**
     * Types tried, in order, after a file's own type finds no app. A csv is plain text, so
     * a device without a spreadsheet app can still show it — which is how it opened before
     * its type was set correctly.
     */
    private val TEXT_FALLBACKS = mapOf(
        "csv" to listOf("text/comma-separated-values", PLAIN_TEXT),
        "tsv" to listOf(PLAIN_TEXT),
        "json" to listOf(PLAIN_TEXT),
        "xml" to listOf(PLAIN_TEXT),
    )

    /** The lower-cased ending of [fileName] without the dot, or "" when it has none. */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot == -1 || dot == fileName.length - 1) return ""
        return fileName.substring(dot + 1).trim().lowercase()
    }

    /** The type pinned for [extension], or null when it is left to [MimeTypeMap]. */
    fun knownMimeType(extension: String): String? = KNOWN_MIME_TYPES[extension.trim().lowercase()]

    /** The type to open [fileName] with; [ANY_MIME_TYPE] when its ending is unknown. */
    fun getMimeType(fileName: String): String {
        val extension = extensionOf(fileName)
        knownMimeType(extension)?.let { return it }
        if (extension.isEmpty()) return ANY_MIME_TYPE
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: ANY_MIME_TYPE
    }

    /** Every type worth trying for [fileName], best first, ending in [ANY_MIME_TYPE]. */
    fun mimeTypeCandidates(fileName: String): List<String> {
        val fallbacks = TEXT_FALLBACKS[extensionOf(fileName)].orEmpty()
        return (listOf(getMimeType(fileName)) + fallbacks + ANY_MIME_TYPE).distinct()
    }

    /**
     * A VIEW intent that opens [uri] — a `content://` URI the host can grant, such as a
     * MediaStore or FileProvider one — with the type [fileName]'s ending calls for.
     *
     * Uses the first of [mimeTypeCandidates] some app accepts, so a csv still opens on a
     * device with no spreadsheet app. When the host's package visibility hides every app,
     * nothing resolves and the file's own type is used.
     *
     * Carries [Intent.FLAG_ACTIVITY_NEW_TASK] because the SDK's context is usually not an
     * Activity, and the read grant because the viewer does not own the file.
     */
    fun buildOpenFileIntent(context: Context, uri: Uri, fileName: String): Intent {
        val candidates = mimeTypeCandidates(fileName)
        val pm = context.packageManager
        val type = candidates.firstOrNull { pm.hasViewerFor(uri, it) } ?: candidates.first()
        Log.d(TAG, "open $fileName as $type (candidates=$candidates)")
        return viewIntent(uri, type)
    }

    /**
     * [buildOpenFileIntent] for a file still in private storage, shared through the
     * FileProvider registered under [authority]. The provider must be one that can read
     * [file]: in ATAK the SDK writes into ATAK's own directories, which a provider declared
     * in a plugin APK — running as the plugin's uid — cannot read.
     *
     * @throws IllegalArgumentException when [file] is outside the provider's configured paths.
     */
    fun buildOpenFileIntent(context: Context, file: File, authority: String): Intent =
        buildOpenFileIntent(context, FileProvider.getUriForFile(context, authority, file), file.name)

    private fun viewIntent(uri: Uri, type: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun PackageManager.hasViewerFor(uri: Uri, type: String): Boolean =
        runCatching { queryIntentActivities(viewIntent(uri, type), 0).isNotEmpty() }
            .getOrDefault(false)
}
