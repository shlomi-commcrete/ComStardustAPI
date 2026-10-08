package com.commcrete.stardust.room.new_db.message

import com.commcrete.stardust.util.FileReceiver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the startup sweep applies to a row the last run of the app left RECEIVING, and the
 * persistence contract of the one field it introduces.
 *
 * The rule has to be right per message type, because what survives a crash differs per type: a PTT
 * is written through as it arrives and usually has real audio on disk, an attachment is assembled
 * only at the end and so has nothing.
 */
class StaleInFlightSettlementTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val withAudio: (String) -> Boolean = { true }
    private val withoutAudio: (String) -> Boolean = { false }

    private fun ptt(path: String = "/files/chat/1727100000000-alice.wav") =
        MessageExtraData.PTT(path = path, encoderType = EncoderType.CODEC2)

    private fun attachment() = MessageExtraData.Attachment(
        title = "photo.jpg",
        path = "",
        subtype = AttachmentType.IMAGE,
    )

    @Test
    fun `a partial PTT stays playable and is marked truncated`() {
        val settlement = settlementForStaleInFlight(MessageState.RECEIVING, ptt(), withAudio)

        assertEquals(MessageState.RECEIVED, settlement.state)
        val extra = settlement.extraData as MessageExtraData.PTT
        assertTrue("the user heard this much; it must stay replayable", extra.truncated)
        assertEquals("the audio that survived is still where it was", ptt().path, extra.path)
    }

    @Test
    fun `a PTT with no audio on disk is a failure`() {
        val settlement = settlementForStaleInFlight(MessageState.RECEIVING, ptt(), withoutAudio)

        assertEquals(MessageState.FAILED, settlement.state)
        assertNull("nothing to add to the blob, so leave it alone", settlement.extraData)
    }

    @Test
    fun `an interrupted attachment fails with the reason and no path`() {
        val settlement = settlementForStaleInFlight(MessageState.RECEIVING, attachment(), withAudio)

        assertEquals(MessageState.FAILED, settlement.state)
        val extra = settlement.extraData as MessageExtraData.Attachment
        assertEquals(FileReceiver.FileFailure.INTERRUPTED, extra.failure)
        assertEquals("no file was ever assembled", "", extra.path)
        assertEquals("the name survives", "photo.jpg", extra.title)
    }

    /** An attachment never consults the audio probe — the two types must not share a rule. */
    @Test
    fun `an attachment is never settled as received`() {
        for (probe in listOf(withAudio, withoutAudio)) {
            assertEquals(MessageState.FAILED, settlementForStaleInFlight(MessageState.RECEIVING, attachment(), probe).state)
        }
    }

    /**
     * A text is whole only once LAST arrived, and a row that saw LAST was settled by the live
     * assembler. One still RECEIVING never saw it. Fails against the old rule, which said RECEIVED.
     */
    @Test
    fun `a multi-part text that never saw LAST fails with the parts that arrived`() {
        val settlement = settlementForStaleInFlight(MessageState.RECEIVING, MessageExtraData.Text("hello wor"), withoutAudio)

        assertEquals(MessageState.FAILED, settlement.state)
        val extra = settlement.extraData as MessageExtraData.Text
        assertEquals("the row keeps every part that arrived", "hello wor", extra.text)
        assertEquals(FileReceiver.FileFailure.INTERRUPTED, extra.failure)
    }

    @Test
    fun `a row of any other type is settled without touching its blob`() {
        val settlement = settlementForStaleInFlight(
            MessageState.RECEIVING,
            MessageExtraData.Location(latitude = 0.0, longitude = 0.0, altitude = 0.0), withAudio,
        )

        assertEquals(MessageState.FAILED, settlement.state)
        assertNull(settlement.extraData)
    }

    // ── SENDING: settled by what had really gone out ─────────────────────

    private fun text(sent: Int, needed: Int) =
        MessageExtraData.Text("hello world", sendProgress = SendProgress(sent, needed))

    private fun sendingAttachment(sent: Int, needed: Int) =
        attachment().copy(path = "/files/chat/files/photo.jpg", sendProgress = SendProgress(sent, needed))

    @Test
    fun `an outgoing text whose every part went out is sent`() {
        val settlement = settlementForStaleInFlight(MessageState.SENDING, text(3, 3), withoutAudio)

        assertEquals(MessageState.SENT, settlement.state)
        assertNull(settlement.extraData)
    }

    @Test
    fun `an outgoing text cut short is a failure with its text kept`() {
        val settlement = settlementForStaleInFlight(MessageState.SENDING, text(2, 3), withoutAudio)

        assertEquals(MessageState.FAILED, settlement.state)
        val extra = settlement.extraData as MessageExtraData.Text
        assertEquals("the whole text stays on the row, ready to resend", "hello world", extra.text)
        assertEquals(FileReceiver.FileFailure.INTERRUPTED, extra.failure)
    }

    /** Each live path names its own cause; only a failed send carries one. */
    @Test
    fun `an interrupted live send carries the reason it was given`() {
        val lostLink = settlementForInterruptedSend(text(1, 3), FileReceiver.FileFailure.DISCONNECTED)
        assertEquals(FileReceiver.FileFailure.DISCONNECTED, (lostLink.extraData as MessageExtraData.Text).failure)

        val lostFile = settlementForInterruptedSend(sendingAttachment(1, 9), FileReceiver.FileFailure.DISCONNECTED)
        assertEquals(FileReceiver.FileFailure.DISCONNECTED, (lostFile.extraData as MessageExtraData.Attachment).failure)

        val delivered = settlementForInterruptedSend(text(3, 3), FileReceiver.FileFailure.DISCONNECTED)
        assertEquals(MessageState.SENT, delivered.state)
        assertNull("a send that got through has no failure to record", delivered.extraData)
    }

    @Test
    fun `an outgoing file whose needed packages went out is sent`() {
        val settlement = settlementForStaleInFlight(MessageState.SENDING, sendingAttachment(9, 9), withoutAudio)

        assertEquals(MessageState.SENT, settlement.state)
    }

    @Test
    fun `an outgoing file cut short fails as interrupted and keeps its local copy`() {
        val settlement = settlementForStaleInFlight(MessageState.SENDING, sendingAttachment(4, 9), withoutAudio)

        assertEquals(MessageState.FAILED, settlement.state)
        val extra = settlement.extraData as MessageExtraData.Attachment
        assertEquals(FileReceiver.FileFailure.INTERRUPTED, extra.failure)
        assertEquals("the sender's local copy is what they would resend", "/files/chat/files/photo.jpg", extra.path)
    }

    /** Nothing recorded means nothing says it went: never SENT on no evidence. */
    @Test
    fun `a SENDING row with no progress recorded fails`() {
        for (extra in listOf(ptt(), attachment(), MessageExtraData.Text("x"), null)) {
            assertEquals(
                MessageState.FAILED,
                settlementForStaleInFlight(MessageState.SENDING, extra, withAudio).state,
            )
        }
    }

    /** The live send paths and the sweep must settle a send the same way. */
    @Test
    fun `the sweep and an interrupted live send apply one rule`() {
        for (extra in listOf(text(3, 3), text(1, 3), sendingAttachment(9, 9), sendingAttachment(1, 9))) {
            assertEquals(
                settlementForInterruptedSend(extra, FileReceiver.FileFailure.INTERRUPTED),
                settlementForStaleInFlight(MessageState.SENDING, extra, withAudio),
            )
        }
    }

    @Test
    fun `sendProgress is omitted from the blob unless it is set`() {
        val encoded = json.encodeToString<MessageExtraData>(MessageExtraData.Text("hi"))

        assertFalse("a plain text must serialize as it always did: $encoded", encoded.contains("sendProgress"))
    }

    @Test
    fun `sendProgress round-trips`() {
        val encoded = json.encodeToString<MessageExtraData>(text(2, 5))
        val decoded = json.decodeFromString<MessageExtraData>(encoded) as MessageExtraData.Text

        assertEquals(SendProgress(2, 5), decoded.sendProgress)
    }

    @Test
    fun `a text row written before the field existed still parses`() {
        val decoded = json.decodeFromString<MessageExtraData>("""{"type":"Text","text":"hi"}""") as MessageExtraData.Text

        assertNull(decoded.sendProgress)
    }

    /** Persisted id: renumbering it would silently re-state every SENDING row on disk. */
    @Test
    fun `SENDING keeps id 7`() {
        assertEquals(7, MessageState.SENDING.id)
    }

    @Test
    fun `truncated is omitted from the blob unless it is set`() {
        val encoded = json.encodeToString<MessageExtraData>(ptt())

        assertFalse("a complete PTT must serialize as it always did: $encoded", encoded.contains("truncated"))
    }

    @Test
    fun `truncated round-trips`() {
        val encoded = json.encodeToString<MessageExtraData>(ptt().copy(truncated = true))
        val decoded = json.decodeFromString<MessageExtraData>(encoded) as MessageExtraData.PTT

        assertTrue(decoded.truncated)
        assertEquals(EncoderType.CODEC2, decoded.encoderType)
    }

    @Test
    fun `a PTT row written before the field existed still parses`() {
        val legacyRow = """
            {"type":"PTT","path":"/files/chat/1727100000000-alice.wav","encoderType":"CODEC2"}
        """.trimIndent()

        val decoded = json.decodeFromString<MessageExtraData>(legacyRow) as MessageExtraData.PTT

        assertFalse("an old row must read as 'complete'", decoded.truncated)
    }
}
