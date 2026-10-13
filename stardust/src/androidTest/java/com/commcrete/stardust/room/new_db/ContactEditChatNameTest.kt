package com.commcrete.stardust.room.new_db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commcrete.stardust.contacts.ContactConflictEngine
import com.commcrete.stardust.contacts.ContactConflicts
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.contacts.ContactResolution
import com.commcrete.stardust.room.new_db.audit.IdentityLogSource
import com.commcrete.stardust.room.new_db.chat.ChatSummary
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.FullContactData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A callsign edit reaches the chat: the chat list and the conversation header show the new name.
 *
 * The case that failed: a message from an unknown App ID auto-creates a contact and a chat, both
 * named after the id. Editing that contact renamed the contact but left the chat on the id —
 * and adding a device or App ID in the same edit opened a second chat instead.
 *
 * Writes go through the same engine call the plugin's contact editor makes.
 */
@RunWith(AndroidJUnit4::class)
class ContactEditChatNameTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    private val unknownId = "0a1b2c3d"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repo = AppRepository(
            db.appChatsDao(),
            db.appContactsDao(),
            db.appMessagesDao(),
            db.appContactIdentityLogDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    /** What the message-save pipeline does for a sender it does not know. */
    private suspend fun autoCreateUnknown(): ContactDraft {
        repo.insertContactWithChat(
            FullContactData.createUserContact(name = unknownId, image = null, userId = unknownId)!!,
            IdentityLogSource.AUTO_CREATE,
        )
        return storedDraft(unknownId)
    }

    private suspend fun storedDraft(appId: String): ContactDraft =
        repo.getAllContacts().map(ContactDraft.Companion::fromFullContactData).single { it.appId == appId }

    /** The editor's save: no conflicts, the edited contact passed as `editing`. */
    private suspend fun edit(editing: ContactDraft, to: ContactDraft) {
        repo.applyContactOperations(
            ContactConflictEngine.resolve(
                to, ContactConflicts(), ContactResolution(incomingName = to.name), editing = editing,
            )
        )
    }

    private suspend fun privateChats(): List<ChatSummary> =
        repo.getChatSummaries().first().filter { it.chatType == ChatType.PRIVATE }

    private suspend fun assertOneChatNamed(name: String) {
        val chat = privateChats().single()
        assertEquals("chat list", name, chat.name)
        assertEquals("header", name, repo.getChatByChatId(chat.chatId)?.name)
        // getChatByChatId derives the name too; read the raw column to check the UNIQUE copy.
        val copy = db.query("SELECT name FROM chats WHERE id = ?", arrayOf(chat.chatId)).use { c ->
            c.moveToFirst(); c.getString(0)
        }
        assertEquals("stored copy", name, copy)
    }

    @Test
    fun renamingAnUnknownSenderRenamesItsChat() = runBlocking {
        val unknown = autoCreateUnknown()
        assertOneChatNamed(unknownId)

        edit(unknown, unknown.copy(name = "Alpha"))

        assertOneChatNamed("Alpha")
    }

    @Test
    fun aCaseOnlyChangeRenamesTheChat() = runBlocking {
        val unknown = autoCreateUnknown()
        edit(unknown, unknown.copy(name = "alpha"))

        edit(storedDraft(unknownId), storedDraft(unknownId).copy(name = "Alpha"))

        assertOneChatNamed("Alpha")
    }

    @Test
    fun aNewDeviceWithANewCallsignKeepsOneChat() = runBlocking {
        val unknown = autoCreateUnknown()
        val contactId = db.appContactsDao().findContactIdByUserId(unknownId)

        edit(unknown, unknown.copy(name = "Alpha", deviceId = "11223344"))

        assertOneChatNamed("Alpha")
        assertEquals(contactId, db.appContactsDao().findContactIdByDeviceId("11223344"))
    }

    @Test
    fun aNewAppIdWithANewCallsignKeepsOneChat() = runBlocking {
        val unknown = autoCreateUnknown()
        val contactId = db.appContactsDao().findContactIdByUserId(unknownId)

        edit(unknown, unknown.copy(name = "Alpha", appId = "99887766"))

        assertOneChatNamed("Alpha")
        assertEquals(contactId, db.appContactsDao().findContactIdByUserId("99887766"))
        assertNull("old App ID released", db.appContactsDao().findContactIdByUserId(unknownId))
    }

    /** The import screen's rename: a file row with the same ids, callsign differing in case. */
    @Test
    fun anImportedCaseOnlyRenameRenamesTheChat() = runBlocking {
        val unknown = autoCreateUnknown()
        edit(unknown, unknown.copy(name = "alpha"))
        val stored = storedDraft(unknownId)

        val rename = ContactConflictEngine.renameOpFor(stored.copy(name = "Alpha"), listOf(stored))!!
        repo.applyContactOperations(listOf(rename))

        assertOneChatNamed("Alpha")
    }

    @Test
    fun removingTheAppIdKeepsTheContactAsADeviceInItsChat() = runBlocking {
        val unknown = autoCreateUnknown()
        edit(unknown, unknown.copy(name = "Alpha", deviceId = "11223344"))
        val user = storedDraft(unknownId)

        edit(user, user.copy(appId = ""))

        assertOneChatNamed("Alpha")
        val stored = repo.getAllContacts().single()
        assertTrue("now a device contact", stored is FullContactData.Device)
        assertEquals("Alpha", stored.contact.name)
        assertNull("App ID released", db.appContactsDao().findContactIdByUserId(unknownId))
    }

    /** The import's update: a device-only contact given an App ID and a callsign by a file row. */
    @Test
    fun aDeviceContactGivenAnAppIdBecomesAUserInItsChat() = runBlocking {
        repo.insertContactWithChat(
            FullContactData.createDeviceContact(name = "Radio 7", image = null, deviceId = "11223344")!!,
        )
        val device = repo.getAllContacts().map(ContactDraft.Companion::fromFullContactData).single()

        repo.applyContactOperations(
            listOf(ContactOperation.UpdateExisting(device, device.copy(name = "Alpha", appId = unknownId)))
        )

        assertOneChatNamed("Alpha")
        assertTrue("now a user contact", repo.getAllContacts().single() is FullContactData.User)
        assertEquals(
            db.appContactsDao().findContactIdByDeviceId("11223344"),
            db.appContactsDao().findContactIdByUserId(unknownId),
        )
    }

    /** The import's update taking a device from the contact that holds it, which it then empties. */
    @Test
    fun takingADeviceFromItsHolderKeepsOneChatForTheContact() = runBlocking {
        val unknown = autoCreateUnknown()
        repo.insertContactWithChat(
            FullContactData.createDeviceContact(name = "Radio 7", image = null, deviceId = "11223344")!!,
        )
        val holder = repo.getAllContacts().map(ContactDraft.Companion::fromFullContactData)
            .single { it.deviceId == "11223344" }
        val applied = unknown.copy(name = "Alpha", deviceId = "11223344")

        repo.applyContactOperations(
            listOf(
                ContactOperation.DeleteExisting(holder, reparentTo = applied),
                ContactOperation.UpdateExisting(unknown, applied),
            )
        )

        assertOneChatNamed("Alpha")
        assertEquals(
            db.appContactsDao().findContactIdByUserId(unknownId),
            db.appContactsDao().findContactIdByDeviceId("11223344"),
        )
    }

    @Test
    fun removingTheDeviceKeepsOneChat() = runBlocking {
        val unknown = autoCreateUnknown()
        edit(unknown, unknown.copy(name = "Alpha", deviceId = "11223344"))
        val user = storedDraft(unknownId)

        edit(user, user.copy(deviceId = ""))

        assertOneChatNamed("Alpha")
        assertNull("device released", db.appContactsDao().findContactIdByDeviceId("11223344"))
        assertTrue(repo.getAllContacts().single() is FullContactData.User)
    }

    @Test
    fun theChatListReadsTheContactsNameEvenWhenTheCopyIsStale() = runBlocking {
        autoCreateUnknown()
        val contactId = db.appContactsDao().findContactIdByUserId(unknownId)!!

        // A contact-only write, as older paths made: the chat's copy keeps the id.
        db.appContactsDao().renameContact(contactId, "Alpha")

        val chat = privateChats().single()
        assertEquals("Alpha", chat.name)
        assertEquals("Alpha", repo.getChatByChatId(chat.chatId)?.name)
    }

    @Test
    fun aGroupChatIsNamedAfterTheGroupNotAMember() = runBlocking {
        autoCreateUnknown()
        repo.insertContactWithChat(
            FullContactData.createGroupContact(name = "Ops", image = null, groupId = "aabbccdd")!!,
        )

        val group = repo.getChatSummaries().first().single { it.chatType == ChatType.GROUP }
        assertEquals("Ops", group.name)
    }
}
