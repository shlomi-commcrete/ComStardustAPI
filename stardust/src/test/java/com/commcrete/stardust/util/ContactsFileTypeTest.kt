package com.commcrete.stardust.util

import com.commcrete.stardust.room.new_db.contact.FullContactData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A contacts-file row is one of the three types the exporter writes, or it is left out and said. */
class ContactsFileTypeTest {

    private fun row(type: String) = FolderReader.ExcelUser(_id = "a1b2c3d4", name = "Alpha", type = type)

    private fun parse(vararg rows: FolderReader.ExcelUser): Pair<List<FullContactData>, List<ContactsFileParserUtil.RejectReason>> {
        val rejected = mutableListOf<ContactsFileParserUtil.RejectReason>()
        val parsed = ContactsFileParserUtil.parseContactsForDb(rows.toList()) { _, reason -> rejected += reason }
        return parsed to rejected
    }

    @Test
    fun `app, group and device are read, any case`() {
        val (parsed, rejected) = parse(row("App"), row(" group ").copy(name = "Team"), row("DEVICE").copy(name = "Radio"))
        assertEquals(3, parsed.size)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `an unknown type is rejected, not made a device`() {
        val (parsed, rejected) = parse(row("usr"))
        assertTrue(parsed.isEmpty())
        assertEquals(listOf(ContactsFileParserUtil.RejectReason.UNKNOWN_TYPE), rejected)
    }

    @Test
    fun `a blank type is rejected`() {
        val (parsed, rejected) = parse(row(""))
        assertTrue(parsed.isEmpty())
        assertEquals(listOf(ContactsFileParserUtil.RejectReason.UNKNOWN_TYPE), rejected)
    }
}
