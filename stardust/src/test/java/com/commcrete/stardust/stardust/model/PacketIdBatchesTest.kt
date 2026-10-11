package com.commcrete.stardust.stardust.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One lookup per batch — the parts of one text, the packets of one PTT transmission — and never
 * shared across batches: a new message after LAST, or after the batch's gap, looks up again.
 */
class PacketIdBatchesTest {

    private var lookups = 0
    private var clock = 0L

    private val batches = PacketIdBatches(
        resolve = { source, _ ->
            lookups++
            StardustPackage.ResolvedIds(senderId = source, groupId = null, chatId = "chat-$lookups")
        },
        now = { clock },
    )

    @Test
    fun `packets of one batch share the first packet's lookup`() {
        val first = batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)
        clock += 300
        val second = batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)
        clock += 300
        val third = batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)

        assertEquals(1, lookups)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `a gap longer than the batch's window starts a new batch`() {
        batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)
        clock += 2_001
        batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)

        assertEquals(2, lookups)
    }

    @Test
    fun `the window runs from the latest packet, not the first`() {
        batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)
        repeat(5) {
            clock += 1_500
            batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)
        }

        assertEquals(1, lookups)
    }

    @Test
    fun `an ended batch is not reused by the next message`() {
        batches.idsFor("A", "B", PacketIdBatches.Kind.TEXT, idleMs = 10_000)
        batches.end("A", "B", PacketIdBatches.Kind.TEXT)
        batches.idsFor("A", "B", PacketIdBatches.Kind.TEXT, idleMs = 10_000)

        assertEquals(2, lookups)
    }

    @Test
    fun `different senders, destinations and kinds are different batches`() {
        batches.idsFor("A", "B", PacketIdBatches.Kind.TEXT, idleMs = 10_000)
        batches.idsFor("C", "B", PacketIdBatches.Kind.TEXT, idleMs = 10_000)
        batches.idsFor("A", "G", PacketIdBatches.Kind.TEXT, idleMs = 10_000)
        batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 10_000)

        assertEquals(4, lookups)
    }

    @Test
    fun `clear ends every batch`() {
        batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)
        batches.clear()
        batches.idsFor("A", "B", PacketIdBatches.Kind.PTT, idleMs = 2_000)

        assertEquals(2, lookups)
    }
}
