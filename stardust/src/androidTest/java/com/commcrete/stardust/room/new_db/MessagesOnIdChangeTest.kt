package com.commcrete.stardust.room.new_db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Messages follow their id when a save changes who holds it.
 *
 * A private chat only shows messages exchanged with its contact's current ids. So when an id
 * moves to another contact its messages move to that contact's chat, and when it goes to
 * nobody they are archived. Messages under ids the contact still holds are left alone.
 */
@RunWith(AndroidJUnit4::class)
class MessagesOnIdChangeTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    private val me = "a0a0a0a0"
    private val appX = "0a1b2c3d"
    private val appY = "99887766"
    private val appZ = "55667788"
    private val devD = "11223344"
    private val oldQ = "77777777"

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
            database = db,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun user(name: String, appId: String, device: String? = null) =
        repo.insertContactWithChat(FullContactData.createUserContact(name = name, image = null, userId = appId, deviceId = device)!!)

    private suspend fun draftNamed(name: String): ContactDraft =
        repo.getAllContacts().map(ContactDraft.Companion::fromFullContactData).single { it.name == name }

    private suspend fun chatIdOf(name: String): String =
        repo.getChatSummaries().first().single { it.name == name }.chatId

    private suspend fun received(chatId: String, from: String): Long =
        db.appMessagesDao().addMessage(
            MessageEntity(chatId = chatId, senderID = from, receiverID = me, extraData = MessageExtraData.Text("in"), state = MessageState.RECEIVED)
        )

    private suspend fun sent(chatId: String, to: String): Long =
        db.appMessagesDao().addMessage(
            MessageEntity(chatId = chatId, senderID = me, receiverID = to, extraData = MessageExtraData.Text("out"), state = MessageState.SENT)
        )

    private suspend fun stateOf(id: Long) = db.appMessagesDao().getMessageById(id)?.state
    private suspend fun chatOfMessage(id: Long) = db.appMessagesDao().getMessageById(id)?.chatId

    @Test
    fun aReplacedAppIdArchivesOnlyItsOwnMessages() = runBlocking {
        user("Alpha", appX, devD)
        val chat = chatIdOf("Alpha")
        val fromX = received(chat, appX)
        val toX = sent(chat, appX)
        val fromD = received(chat, devD)
        val alpha = draftNamed("Alpha")

        repo.applyContactOperations(listOf(ContactOperation.UpdateExisting(alpha, alpha.copy(appId = appY))))

        assertEquals(MessageState.ARCHIVED, stateOf(fromX))
        assertEquals(MessageState.ARCHIVED, stateOf(toX))
        assertEquals("the device's messages stay", MessageState.RECEIVED, stateOf(fromD))
    }

    @Test
    fun aRemovedDeviceArchivesItsMessages() = runBlocking {
        user("Alpha", appX, devD)
        val chat = chatIdOf("Alpha")
        val fromX = received(chat, appX)
        val fromD = received(chat, devD)
        val alpha = draftNamed("Alpha")

        repo.applyContactOperations(listOf(ContactOperation.UpdateExisting(alpha, alpha.copy(deviceId = ""))))

        assertEquals(MessageState.ARCHIVED, stateOf(fromD))
        assertEquals(MessageState.RECEIVED, stateOf(fromX))
    }

    @Test
    fun aDeviceMovedToAnotherContactTakesItsMessages() = runBlocking {
        user("Alpha", appX, devD)
        user("Bravo", appZ)
        val alphaChat = chatIdOf("Alpha")
        val bravoChat = chatIdOf("Bravo")
        val fromX = received(alphaChat, appX)
        val fromD = received(alphaChat, devD)
        val toD = sent(alphaChat, devD)
        val alpha = draftNamed("Alpha")
        val bravo = draftNamed("Bravo")

        repo.applyContactOperations(
            listOf(
                ContactOperation.UpdateExisting(alpha, alpha.copy(deviceId = "")),
                ContactOperation.UpdateExisting(bravo, bravo.copy(deviceId = devD)),
            )
        )

        assertEquals(bravoChat, chatOfMessage(fromD))
        assertEquals(bravoChat, chatOfMessage(toD))
        assertEquals("moved, not archived", MessageState.RECEIVED, stateOf(fromD))
        assertEquals("Alpha's App ID messages stay", alphaChat, chatOfMessage(fromX))
        assertEquals(MessageState.RECEIVED, stateOf(fromX))
    }

    @Test
    fun anEmptiedContactsChatMovesInAndWhatIsNotTheReceiversIsArchived() = runBlocking {
        repo.insertContactWithChat(FullContactData.createDeviceContact(name = "Radio 7", image = null, deviceId = devD)!!)
        user("Alpha", appX)
        val radioChat = chatIdOf("Radio 7")
        val alphaChat = chatIdOf("Alpha")
        val fromD = received(radioChat, devD)
        val fromOldQ = received(radioChat, oldQ) // an id Radio 7 held before; hidden there already
        val radio = draftNamed("Radio 7")
        val alpha = draftNamed("Alpha")
        val applied = alpha.copy(deviceId = devD)

        repo.applyContactOperations(
            listOf(
                ContactOperation.UpdateExisting(alpha, applied),
                ContactOperation.DeleteExisting(radio, reparentTo = applied),
            )
        )

        assertNull("the emptied contact is gone", db.appChatsDao().getChatByChatId(radioChat))
        assertEquals(alphaChat, chatOfMessage(fromD))
        assertEquals("its device's messages are now Alpha's", MessageState.RECEIVED, stateOf(fromD))
        assertEquals(alphaChat, chatOfMessage(fromOldQ))
        assertEquals("kept, but not Alpha's", MessageState.ARCHIVED, stateOf(fromOldQ))
    }

    // User ↔ group: an id's messages never move into the other kind of chat.

    private suspend fun group(name: String, groupId: String) =
        repo.insertContactWithChat(FullContactData.createGroupContact(name = name, image = null, groupId = groupId)!!)

    @Test
    fun aUserAndAGroupSwappingAppIdsKeepTheirOwnHistory() = runBlocking {
        user("Alpha", appX)
        group("Ops", appY)
        val alphaChat = chatIdOf("Alpha")
        val opsChat = chatIdOf("Ops")
        val fromX = received(alphaChat, appX)
        val groupTraffic = db.appMessagesDao().addMessage(
            MessageEntity(chatId = opsChat, senderID = appZ, receiverID = appY, extraData = MessageExtraData.Text("all"), state = MessageState.RECEIVED)
        )
        val alpha = draftNamed("Alpha")
        val ops = draftNamed("Ops")

        repo.applyContactOperations(
            listOf(
                ContactOperation.UpdateExisting(alpha, alpha.copy(appId = "")),
                ContactOperation.UpdateExisting(ops, ops.copy(appId = "")),
                ContactOperation.UpdateExisting(alpha.copy(appId = ""), alpha.copy(appId = appY)),
                ContactOperation.UpdateExisting(ops.copy(appId = ""), ops.copy(appId = appX)),
            )
        )

        assertEquals("the group's traffic stays in the group chat", opsChat, chatOfMessage(groupTraffic))
        assertEquals(MessageState.RECEIVED, stateOf(groupTraffic))
        assertEquals("Alpha's own messages do not go to the group", alphaChat, chatOfMessage(fromX))
        assertEquals(MessageState.ARCHIVED, stateOf(fromX))
    }

    @Test
    fun anEmptiedGroupsChatLandsArchivedInAPrivateChat() = runBlocking {
        user("Alpha", appX)
        group("Ops", appY)
        val alphaChat = chatIdOf("Alpha")
        val opsChat = chatIdOf("Ops")
        val groupTraffic = db.appMessagesDao().addMessage(
            MessageEntity(chatId = opsChat, senderID = appZ, receiverID = appY, extraData = MessageExtraData.Text("all"), state = MessageState.RECEIVED)
        )
        val alpha = draftNamed("Alpha")
        val ops = draftNamed("Ops")
        val applied = alpha.copy(appId = appY)

        repo.applyContactOperations(
            listOf(
                ContactOperation.UpdateExisting(alpha, applied),
                ContactOperation.DeleteExisting(ops, reparentTo = applied),
            )
        )

        assertNull(db.appChatsDao().getChatByChatId(opsChat))
        assertEquals(alphaChat, chatOfMessage(groupTraffic))
        assertEquals("kept, never shown as a private chat's", MessageState.ARCHIVED, stateOf(groupTraffic))
    }
}
