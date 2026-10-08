package com.commcrete.stardust.audio.v2.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the stream-id format documented on StardustAPI.setPttVolume. The host builds the same string
 * from the callback package's groupId / senderId, so a change here silently detaches every host's
 * volume control from the stream it means.
 */
class StreamIdTest {

    @Test
    fun direct_isTheSender() {
        assertEquals("a1b2", StardustPackageRouter.streamIdOf(groupId = null, senderId = "a1b2"))
    }

    @Test
    fun group_isGroupThenSender() {
        assertEquals("g9_a1b2", StardustPackageRouter.streamIdOf(groupId = "g9", senderId = "a1b2"))
    }

    @Test
    fun group_talkersAreSeparateStreams() {
        assertNotEquals(
            StardustPackageRouter.streamIdOf(groupId = "g9", senderId = "a1b2"),
            StardustPackageRouter.streamIdOf(groupId = "g9", senderId = "c3d4"),
        )
    }
}
