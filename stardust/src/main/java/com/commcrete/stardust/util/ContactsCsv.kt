package com.commcrete.stardust.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What it takes to read a contacts CSV that a person saved from a spreadsheet program, as opposed to
 * one our own exporter wrote. The two differ in ways the exporter never produces:
 *
 *  - **Encoding.** Excel saves "CSV UTF-8" with a byte-order mark, but plain "CSV" in the machine's
 *    ANSI code page — Windows-1255 on a Hebrew PC — and "Unicode text" in UTF-16.
 *  - **Delimiter.** Excel uses the locale's list separator, which is `;` where the decimal mark is a
 *    comma, and a tab for "Unicode text".
 *
 * Pure, so it is tested without a device.
 */
internal object ContactsCsv {

    private const val UTF8_BOM = "﻿"

    /** The code page a Hebrew Windows saves plain "CSV" in. Its ASCII half is shared, so ids read the same. */
    private val ANSI_FALLBACK: Charset = Charset.forName("windows-1255")

    /**
     * The text of [bytes]. A UTF-16 file is read as UTF-16; anything that is valid UTF-8 is UTF-8, with
     * a byte-order mark removed; anything else is taken to be the ANSI code page, which accepts every
     * byte and so never fails — a wrong guess garbles a name, never loses a row.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(bytes)).toString().removePrefix(UTF8_BOM)
        } catch (_: CharacterCodingException) {
            String(bytes, ANSI_FALLBACK)
        }
    }

    /**
     * The delimiter of a file whose header line is [headerLine]: whichever of `,` `;` and tab appears
     * most outside double quotes, with a comma on a tie or none — what our own exporter writes.
     */
    fun detectDelimiter(headerLine: String): Char {
        val candidates = charArrayOf(',', ';', '\t')
        val counts = IntArray(candidates.size)
        var inQuotes = false
        for (c in headerLine) {
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes) candidates.indexOf(c).takeIf { it >= 0 }?.let { counts[it]++ }
        }
        val (comma, semicolon, tab) = counts.toList()
        return when {
            comma >= semicolon && comma >= tab -> ','
            semicolon >= tab -> ';'
            else -> '\t'
        }
    }

    /**
     * Whether [bytes] are a spreadsheet workbook rather than text: an `.xlsx` is a zip (`PK`) and an
     * `.xls` an OLE container (`D0 CF 11 E0`). Content, not the file name or the mime type the picker
     * reported, because a CSV is routinely labelled `application/octet-stream` and a name is only a name.
     */
    fun looksLikeWorkbook(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val zip = bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val ole = (bytes[0].toInt() and 0xFF) == 0xD0 && (bytes[1].toInt() and 0xFF) == 0xCF &&
            (bytes[2].toInt() and 0xFF) == 0x11 && (bytes[3].toInt() and 0xFF) == 0xE0
        return zip || ole
    }
}
