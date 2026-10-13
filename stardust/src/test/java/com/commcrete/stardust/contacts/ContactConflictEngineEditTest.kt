package com.commcrete.stardust.contacts

import com.commcrete.stardust.room.new_db.contact.ContactType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing a stored contact changes it in place.
 *
 * An Insert for a contact that already exists merges into it but opens a second private chat; the
 * old chat keeps the messages and the old name. That is how a contact created from an unknown App
 * ID kept showing the id after its callsign was edited.
 */
class ContactConflictEngineEditTest {

    private val unknown = ContactDraft(name = "0a1b2c3d", appId = "0a1b2c3d", type = ContactType.USER)

    private fun edit(
        to: ContactDraft,
        editing: ContactDraft = unknown,
        conflicts: ContactConflicts = ContactConflicts(),
        resolution: ContactResolution = ContactResolution(incomingName = to.name),
    ) = ContactConflictEngine.resolve(to, conflicts, resolution, editing = editing)

    @Test
    fun `a new callsign updates the contact in place`() {
        val to = unknown.copy(name = "Alpha")
        assertEquals(listOf(ContactOperation.UpdateExisting(unknown, to)), edit(to))
    }

    @Test
    fun `a case-only callsign change is still a change`() {
        val alpha = unknown.copy(name = "alpha")
        val to = alpha.copy(name = "Alpha")
        assertEquals(listOf(ContactOperation.UpdateExisting(alpha, to)), edit(to, editing = alpha))
    }

    @Test
    fun `a new device with a new callsign is one in-place update, not an insert`() {
        val to = unknown.copy(name = "Alpha", deviceId = "11223344")
        assertEquals(listOf(ContactOperation.UpdateExisting(unknown, to)), edit(to))
    }

    @Test
    fun `a new App ID is one in-place update, not an insert`() {
        val to = unknown.copy(name = "Alpha", appId = "99887766")
        assertEquals(listOf(ContactOperation.UpdateExisting(unknown, to)), edit(to))
    }

    @Test
    fun `removing the App ID is an in-place update, not an insert`() {
        val user = unknown.copy(name = "Alpha", deviceId = "11223344")
        val to = user.copy(appId = "")
        assertEquals(listOf(ContactOperation.UpdateExisting(user, to)), edit(to, editing = user))
    }

    @Test
    fun `removing the device is an in-place update`() {
        val user = unknown.copy(name = "Alpha", deviceId = "11223344")
        val to = user.copy(deviceId = "")
        assertEquals(listOf(ContactOperation.UpdateExisting(user, to)), edit(to, editing = user))
    }

    @Test
    fun `a case-only callsign difference is not the same record`() {
        assertFalse(unknown.copy(name = "alpha").sameIdentityAs(unknown.copy(name = "Alpha")))
        assertTrue(unknown.copy(name = "alpha").sameIdentityAs(unknown.copy(name = "alpha", appId = "0A1B2C3D")))
    }

    @Test
    fun `nothing changed writes nothing`() {
        assertEquals(emptyList<ContactOperation>(), edit(unknown))
    }

    @Test
    fun `keeping a conflicting App ID with its owner leaves the edited contact's own`() {
        val owner = ContactDraft(name = "Bravo", appId = "99887766", type = ContactType.USER)
        val to = unknown.copy(name = "Alpha", appId = "99887766")
        val ops = edit(
            to,
            conflicts = ContactConflicts(appIdOwner = owner),
            resolution = ContactResolution(appId = FieldChoice.KEEP, incomingName = "Alpha"),
        )
        assertEquals(listOf(ContactOperation.UpdateExisting(unknown, unknown.copy(name = "Alpha"))), ops)
    }

    @Test
    fun `swapping a callsign renames the owner before the edited contact takes it`() {
        val owner = ContactDraft(name = "Alpha", appId = "99887766", type = ContactType.USER)
        val to = unknown.copy(name = "Alpha")
        val ops = edit(
            to,
            conflicts = ContactConflicts(callsignOwner = owner),
            resolution = ContactResolution(incomingName = "Alpha", ownerName = "Alpha (2)"),
        )
        assertEquals(
            listOf(
                ContactOperation.UpdateExisting(owner, owner.copy(name = "Alpha (2)")),
                ContactOperation.UpdateExisting(unknown, to),
            ),
            ops,
        )
    }

    @Test
    fun `swapping away an owner's only id deletes it and keeps its messages`() {
        val owner = ContactDraft(name = "Bravo", appId = "99887766", type = ContactType.USER)
        val to = unknown.copy(name = "Alpha", appId = "99887766")
        val ops = edit(
            to,
            conflicts = ContactConflicts(appIdOwner = owner),
            resolution = ContactResolution(appId = FieldChoice.SWAP, incomingName = "Alpha"),
        )
        assertEquals(
            listOf(
                ContactOperation.UpdateExisting(unknown, to),
                ContactOperation.DeleteExisting(owner, reparentTo = to),
            ),
            ops,
        )
    }

    @Test
    fun `without an edited contact a case-only rename is still found`() {
        val stored = unknown.copy(name = "alpha")
        val rename = ContactConflictEngine.renameOpFor(stored.copy(name = "Alpha"), listOf(stored))
        assertNotNull(rename)
        assertEquals("Alpha", rename!!.newName)
    }

    @Test
    fun `an add with no edited contact still inserts`() {
        val incoming = ContactDraft(name = "Charlie", appId = "55667788", type = ContactType.USER)
        val ops = ContactConflictEngine.resolve(
            incoming,
            ContactConflicts(),
            ContactResolution(incomingName = "Charlie"),
        )
        assertTrue(ops.single() is ContactOperation.Insert)
    }
}
