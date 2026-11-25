package com.commcrete.stardust.util

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsCsvTest {

    private val header = "type,id,device_id,name"
    private val hebrew = "שלום"

    private fun users(text: String, charset: Charset = Charsets.UTF_8) =
        ContactsFileParserUtil.parseUsersFromCsvBytes(text.toByteArray(charset))

    // ── Encoding ────────────────────────────────────────────────────────────

    @Test
    fun `utf-8 is read as utf-8, with or without a byte-order mark`() {
        assertEquals("$hebrew,1", ContactsCsv.decode("$hebrew,1".toByteArray(Charsets.UTF_8)))
        assertEquals("$hebrew,1", ContactsCsv.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "$hebrew,1".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a hebrew ansi file from a spreadsheet program is not read as garbage`() {
        val ansi = "$hebrew,1".toByteArray(Charset.forName("windows-1255"))

        assertEquals("$hebrew,1", ContactsCsv.decode(ansi))
    }

    @Test
    fun `utf-16 unicode text is read from its byte-order mark`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\tb".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "a\tb".toByteArray(Charsets.UTF_16BE)

        assertEquals("a\tb", ContactsCsv.decode(le))
        assertEquals("a\tb", ContactsCsv.decode(be))
    }

    // ── Delimiter and kind ──────────────────────────────────────────────────

    @Test
    fun `the delimiter is the commonest of comma, semicolon and tab outside quotes, comma on a tie`() {
        assertEquals(',', ContactsCsv.detectDelimiter("type,id,device_id,name"))
        assertEquals(';', ContactsCsv.detectDelimiter("type;id;device_id;name"))
        assertEquals('\t', ContactsCsv.detectDelimiter("type\tid\tdevice_id\tname"))
        assertEquals(',', ContactsCsv.detectDelimiter("type"))
        assertEquals(';', ContactsCsv.detectDelimiter("\"a,b,c\";id;name"))
        assertEquals(',', ContactsCsv.detectDelimiter("a,b;c"))
    }

    @Test
    fun `a workbook is told from text by its first bytes, not its name`() {
        assertTrue(ContactsCsv.looksLikeWorkbook(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
        assertTrue(ContactsCsv.looksLikeWorkbook(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())))
        assertFalse(ContactsCsv.looksLikeWorkbook("type,id\napp,1".toByteArray()))
        assertFalse(ContactsCsv.looksLikeWorkbook(ByteArray(2)))
    }

    // ── Rows ────────────────────────────────────────────────────────────────

    @Test
    fun `a plain csv gives its rows`() {
        val rows = users("$header\napp,1A2B3C4D,,Alpha\ngroup,0B0B0001,,Team\n")

        assertEquals(listOf("alpha", "team"), rows.map { it.name.lowercase() })
        assertEquals(listOf("1a2b3c4d", "0b0b0001"), rows.map { it.id })
        assertEquals(listOf("app", "group"), rows.map { it.type })
    }

    @Test
    fun `a semicolon csv, as a comma-decimal locale saves it, gives the same rows`() {
        val rows = users("type;id;device_id;name\napp;1a2b3c4d;aabbccdd;Alpha\n")

        assertEquals(1, rows.size)
        assertEquals("1a2b3c4d", rows[0].id)
        assertEquals("aabbccdd", rows[0].deviceId)
        assertEquals("Alpha", rows[0].name)
    }

    @Test
    fun `a tab separated file gives the same rows`() {
        val rows = users("type\tid\tdevice_id\tname\napp\t1a2b3c4d\t\tAlpha\n")

        assertEquals("1a2b3c4d", rows.single().id)
    }

    @Test
    fun `quoted values keep their commas and escaped quotes`() {
        val rows = users("$header\napp,1a2b3c4d,,\"Smith, \"\"Ghost\"\" J\"\n")

        assertEquals("Smith, \"Ghost\" J", rows.single().name)
    }

    @Test
    fun `an excel text guard on an id is removed`() {
        val rows = users("$header\napp,=\"0000032b\",,Alpha\n")

        assertEquals("0000032b", rows.single().id)
    }

    @Test
    fun `values are trimmed and the id is normalised like the excel reader`() {
        val rows = users("$header\n  app  , 1A2B3C4D ,, Alpha  \n")

        assertEquals("app", rows.single().type)
        assertEquals("Alpha", rows.single().name)
        assertEquals("1a2b3c4d", rows.single().id)
    }

    @Test
    fun `a device row that carries only a device id gets it as its id, as in excel`() {
        val rows = users("type,id,device_id,name\ndevice,,aabbccdd,Radio\n")

        assertEquals("aabbccdd", rows.single().id)
        assertEquals("", rows.single().deviceId)
    }

    @Test
    fun `a row with no id of either kind is left out`() {
        val rows = users("$header\napp,,,Nobody\napp,1a2b3c4d,,Alpha\n")

        assertEquals(listOf("Alpha"), rows.map { it.name })
    }

    @Test
    fun `header spellings the excel reader accepts are accepted`() {
        val rows = users("Type,ID,Device ID,Name,Img\napp,1a2b3c4d,aabbccdd,Alpha,hq\n")

        assertEquals("aabbccdd", rows.single().deviceId)
        assertEquals("hq", rows.single().image)
    }

    @Test
    fun `crlf, bare cr, blank lines and comment lines are all fine`() {
        val rows = users("$header\r\napp,1a2b3c4d,,Alpha\r\n\r\n# comment\rapp,5e6f7a8b,,Bravo\r\n#HASH,abc\r\n")

        assertEquals(listOf("Alpha", "Bravo"), rows.map { it.name })
    }

    @Test
    fun `hebrew names survive a utf-8 file and an ansi one`() {
        assertEquals(hebrew, users("$header\napp,1a2b3c4d,,$hebrew\n").single().name)
        assertEquals(hebrew, users("$header\napp,1a2b3c4d,,$hebrew\n", Charset.forName("windows-1255")).single().name)
    }

    @Test
    fun `a file that is not a contacts csv gives no rows`() {
        assertTrue(users("hello world, this is not a contacts file").isEmpty())
        assertTrue(users("").isEmpty())
        assertTrue(ContactsFileParserUtil.parseUsersFromCsvBytes(byteArrayOf(0, 1, 2, 3, 4)).isEmpty())
    }
}
