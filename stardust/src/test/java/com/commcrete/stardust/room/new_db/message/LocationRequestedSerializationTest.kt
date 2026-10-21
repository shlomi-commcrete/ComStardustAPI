package com.commcrete.stardust.room.new_db.message

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence contract of `locationRequested` on [MessageExtraData.Text] and
 * [MessageExtraData.Location]: omitted while false, so every row written before the field
 * existed reads back as "no location requested".
 *
 * Mirrors the configuration of `Converters.EnumConverter`, as [SosAckSerializationTest] does.
 */
class LocationRequestedSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a text with no request carries no field`() {
        val encoded = json.encodeToString<MessageExtraData>(MessageExtraData.Text("hi"))
        assertFalse("locationRequested must not appear: $encoded", encoded.contains("locationRequested"))
    }

    @Test
    fun `a text written before the field existed reads as no request`() {
        val decoded = json.decodeFromString<MessageExtraData>("""{"type":"Text","text":"hi"}""")
        assertFalse((decoded as MessageExtraData.Text).locationRequested)
    }

    @Test
    fun `a requested text round-trips`() {
        val encoded = json.encodeToString<MessageExtraData>(MessageExtraData.Text("hi", locationRequested = true))
        val decoded = json.decodeFromString<MessageExtraData>(encoded) as MessageExtraData.Text
        assertTrue(decoded.locationRequested)
    }

    @Test
    fun `a location written before the field existed reads as no request`() {
        val legacyRow = """{"type":"Location","latitude":32.0853,"longitude":34.7818,"altitude":25.0}"""
        val decoded = json.decodeFromString<MessageExtraData>(legacyRow) as MessageExtraData.Location
        assertFalse(decoded.locationRequested)
    }

    @Test
    fun `a requested location round-trips and stays a location`() {
        val sent = MessageExtraData.Location(32.0853, 34.7818, 25.0, locationRequested = true)
        val decoded = json.decodeFromString<MessageExtraData>(json.encodeToString<MessageExtraData>(sent))
        assertEquals(sent, decoded)
        assertEquals(MessageType.LOCATION, decoded.toMessageType())
    }
}
