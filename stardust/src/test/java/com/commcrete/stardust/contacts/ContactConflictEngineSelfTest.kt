package com.commcrete.stardust.contacts

import com.commcrete.stardust.room.new_db.contact.ContactType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operator's own values, as every contact path sees them: a contact can never take the
 * operator's callsign, App ID or radio, and nothing about that is a Keep/Swap conflict.
 */
class ContactConflictEngineSelfTest {

    private val me = ContactDraft(name = "Eagle", appId = "a1b2c3d4", deviceId = "0011aabb", type = ContactType.USER)

    private fun held(incoming: ContactDraft, self: ContactDraft? = me) =
        ContactConflictEngine.detect(incoming, emptyList(), self).heldBySelf

    @Test
    fun `the callsign is held trimmed and ignoring case`() {
        assertEquals(setOf(ConflictField.CALLSIGN), held(ContactDraft(name = "  eAGLE ", appId = "ffff0001")))
    }

    @Test
    fun `a group on the operator's App ID is held`() {
        assertEquals(setOf(ConflictField.APP_ID), held(ContactDraft(name = "Team", appId = "A1B2C3D4", type = ContactType.GROUP)))
    }

    @Test
    fun `the connected radio is held`() {
        assertEquals(setOf(ConflictField.DEVICE_ID), held(ContactDraft(name = "Radio", deviceId = "0011AABB", type = ContactType.DEVICE)))
    }

    @Test
    fun `no operator, nothing held`() {
        assertTrue(held(me, self = null).isEmpty())
    }

    @Test
    fun `blank never matches`() {
        val blankMe = me.copy(deviceId = "")
        assertTrue(held(ContactDraft(name = "Other", appId = "ffff0001", deviceId = ""), blankMe).isEmpty())
    }

    @Test
    fun `held values are not owner conflicts`() {
        val conflicts = ContactConflictEngine.detect(me.copy(), emptyList(), me)
        assertEquals(setOf(ConflictField.CALLSIGN, ConflictField.APP_ID, ConflictField.DEVICE_ID), conflicts.heldBySelf)
        assertTrue(conflicts.isEmpty)
    }
}
