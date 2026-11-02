package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [FileReceiver.transferId] is a received file's identity. It used to be the transport key, the
 * same for every file one sender sent to one chat on one RD, so the host took a second file's
 * percentages as late ones for the first, settled transfer and showed it as already received.
 */
class FileTransferIdTest {

    private fun start(fileName: String = "img", total: Int = 14, spare: Int = 4) = StardustFileStartPackage(
        type = 0, total = total, spare = spare, spareData = 0, fileEnding = "jpg", fileName = fileName,
    )

    private fun id(
        start: StardustFileStartPackage = start(),
        senderId: String = "S1",
        chatId: String = "C1",
        startedAtMs: Long = 1_000L,
        sequence: Long = 1L,
    ) = FileReceiver.transferId("transport", senderId, chatId, start, startedAtMs, sequence)

    /** Regression: same sender, same chat, same RD, same size — two files, two ids. */
    @Test
    fun `two files from one sender in one chat get different ids`() {
        assertNotEquals(id(startedAtMs = 1_000L, sequence = 1L), id(startedAtMs = 61_000L, sequence = 2L))
    }

    @Test
    fun `the start time alone tells two files apart`() {
        assertNotEquals(id(startedAtMs = 1_000L), id(startedAtMs = 1_001L))
    }

    @Test
    fun `the file name alone tells two files apart`() {
        assertNotEquals(id(start = start(fileName = "a")), id(start = start(fileName = "b")))
    }

    @Test
    fun `the sender and the chat each tell two files apart`() {
        assertNotEquals(id(senderId = "S1"), id(senderId = "S2"))
        assertNotEquals(id(chatId = "C1"), id(chatId = "C2"))
    }

    @Test
    fun `the id is stable for the same inputs and short`() {
        assertEquals(id(), id())
        assertEquals(16, id().length)
    }
}
