package com.commcrete.stardust.stardust.model

import com.commcrete.bittell.util.bittel_package.model.StardustFileStartParser
import com.commcrete.stardust.stardust.StardustPackageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start package's original size: 3 bytes, big-endian, after the 50-byte name — where the JS
 * client's convertFileMessageToByte puts it. Hand-built bytes, not captured ones: no capture of a
 * JS start package carrying the size exists yet.
 */
class StardustFileStartRoundTripTest {

    private val parser = StardustFileStartParser()

    private fun packageOf(data: Array<Int>) = StardustPackage(
        syncBytes = arrayOf(),
        destinationBytes = arrayOf(),
        sourceBytes = arrayOf(),
        stardustControlByte = StardustControlByte(),
        stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
        length = data.size,
        data = data,
    )

    private val start = StardustFileStartPackage(
        type = 2, total = 0x0123, spare = 0x0045, spareData = 17,
        fileEnding = "csv", fileName = "contacts", originalSize = 0x0A0B0C,
    )

    /** What FileSender sends: a length byte, "STR", then the header. */
    @Test
    fun `this SDK's start package round-trips`() {
        val header = start.toArrayInt()
        val data = arrayOf(3 + header.size) + "STR".map { it.code }.toTypedArray() + header
        assertEquals(start, parser.parseFileStart(packageOf(data)))
    }

    @Test
    fun `original size is the last three bytes, big-endian`() {
        val header = start.toArrayInt()
        assertEquals(6 + 5 + 50 + 3, header.size)
        assertEquals(listOf(0x0A, 0x0B, 0x0C), header.takeLast(3))
    }

    /** The JS client's layout: no length byte, "STR", then the same fields in the same order. */
    @Test
    fun `the JS client's start package parses`() {
        val js = mutableListOf<Int>()
        js += "STR".map { it.code }
        js += 1                       // fileCategory: image
        js += listOf(0x01, 0x23)      // index, high byte first after the unshifts
        js += listOf(0x00, 0x45)      // parityPackets, the same
        js += 17                      // paddingSize
        js += "jpg".map { it.code } + List(2) { 0 }
        js += "photo".map { it.code } + List(45) { 0 }
        js += listOf(0x0A, 0x0B, 0x0C) // fileSize, pushed high byte first

        val parsed = parser.parseFileStart2(packageOf(js.toTypedArray()))!!
        assertEquals(1, parsed.type)
        assertEquals(0x0123, parsed.total)
        assertEquals(0x45, parsed.spare)
        assertEquals(17, parsed.spareData)
        assertEquals("jpg", parsed.fileEnding)
        assertEquals("photo", parsed.fileName)
        assertEquals(0x0A0B0C, parsed.originalSize)
    }

    /** A sender from before the field ends the package at the name. */
    @Test
    fun `a start package without the size parses as unknown`() {
        val withoutSize = start.toArrayInt().dropLast(3).toTypedArray()
        val data = arrayOf(3 + withoutSize.size) + "STR".map { it.code }.toTypedArray() + withoutSize
        assertEquals(start.copy(originalSize = 0), parser.parseFileStart(packageOf(data)))
    }

    @Test
    fun `the largest size fits and one more is refused`() {
        val max = start.copy(originalSize = StardustFileStartPackage.MAX_ORIGINAL_SIZE)
        assertEquals(listOf(0xFF, 0xFF, 0xFF), max.toArrayInt().takeLast(3))
        assertTrue(runCatching {
            start.copy(originalSize = StardustFileStartPackage.MAX_ORIGINAL_SIZE + 1).toArrayInt()
        }.isFailure)
    }
}
