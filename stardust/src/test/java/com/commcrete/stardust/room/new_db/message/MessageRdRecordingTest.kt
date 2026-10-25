package com.commcrete.stardust.room.new_db.message

import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.util.Carrier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The RD recorded on a message: [MessageEntity.withRd] writes [MessageExtraData.deliveryRd], which
 * lives inside the `extra_data` JSON blob — so it must read back from an already-written row, and a
 * row written before it existed must read back unchanged.
 *
 * Mirrors the configuration of `Converters.EnumConverter`, which does the real encode/decode.
 */
class MessageRdRecordingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun row(extra: MessageExtraData = MessageExtraData.Text(text = "x")) =
        MessageEntity(senderID = "aa", receiverID = "bb", extraData = extra)

    private fun roundTrip(extra: MessageExtraData?): MessageExtraData? =
        json.decodeFromString<MessageExtraData>(json.encodeToString(extra))

    @Test
    fun `an RD spanning several F round-trips`() {
        val recorded = row().withRd(Carrier(index = 1, type = CarrierType.HR, fFirst = 3, fLast = 5))
        val back = roundTrip(recorded.extraData)
        assertEquals(DeliveryRd(rd = 2, fFirst = 3, fLast = 5, type = CarrierType.HR), back?.deliveryRd)
        assertEquals(3..5, back?.deliveryRd?.fRange)
    }

    @Test
    fun `the RD's rate type round-trips, and a row recorded before it reads back without one`() {
        val recorded = row().withRd(Carrier(index = 2, type = CarrierType.LR))
        assertEquals(CarrierType.LR, roundTrip(recorded.extraData)?.deliveryRd?.type)
        val written = """{"type":"Text","text":"x","deliveryRd":{"rd":3}}"""
        assertEquals(DeliveryRd(rd = 3), json.decodeFromString<MessageExtraData>(written).deliveryRd)
    }

    @Test
    fun `an RD whose F are not configured records the RD alone`() {
        val recorded = row().withRd(Carrier(index = 3, type = CarrierType.HR))
        assertEquals(4, recorded.extraData?.deliveryRd?.rd)
        assertNull(recorded.extraData?.deliveryRd?.fRange)
    }

    @Test
    fun `every kind of message keeps the RD`() {
        val radio = Carrier(index = 0, type = CarrierType.LR, fFirst = 7, fLast = 7)
        listOf(
            MessageExtraData.Text(text = "x"),
            MessageExtraData.Attachment(title = "a.txt", path = "/p", subtype = AttachmentType.FILE),
            MessageExtraData.PTT(path = "/p"),
            MessageExtraData.Location(latitude = 1.0, longitude = 2.0, altitude = 3.0),
            MessageExtraData.Sos(latitude = 1.0, longitude = 2.0, altitude = 3.0),
        ).forEach { extra ->
            assertEquals(
                DeliveryRd(rd = 1, fFirst = 7, fLast = 7, type = CarrierType.LR),
                roundTrip(row(extra).withRd(radio).extraData)?.deliveryRd,
            )
        }
    }

    @Test
    fun `no radio leaves the row unrecorded`() {
        val original = row()
        assertSame(original, original.withRd(null))
        assertNull(original.extraData?.deliveryRd)
    }

    @Test
    fun `an unrecorded row omits the field, so older rows read back as they were`() {
        assertFalse(json.encodeToString<MessageExtraData>(MessageExtraData.Text(text = "x")).contains("deliveryRd"))
        val written = """{"type":"Text","text":"x"}"""
        assertNull(json.decodeFromString<MessageExtraData>(written).deliveryRd)
    }
}
