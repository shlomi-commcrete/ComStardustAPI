package com.commcrete.stardust.room.new_db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.room.new_db.audit.IdentityLogSource
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.contact.FullContactData
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
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
 * One App ID, saved as a user and arriving as a group (or the reverse): the contact changes kind
 * in place. Same contact row and same chat row, so nothing is deleted. Its messages are
 * archived, so one audience's traffic never shows up in the other kind of chat.
 */
@RunWith(AndroidJUnit4::class)
class ContactKindSwitchTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    private val appId = "0a1b2c3d"
    private val deviceId = "11223344"
    private val memberId = "55667788"

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

    private suspend fun draftOf(id: String): ContactDraft =
        repo.getAllContacts().map(ContactDraft.Companion::fromFullContactData).single { it.appId == id }

    private suspend fun chatOf(name: String) = repo.getChatSummaries().first().single { it.name == name }

    private suspend fun addMessage(chatId: String, from: String): Long =
        db.appMessagesDao().addMessage(
            MessageEntity(
                chatId = chatId,
                senderID = from,
                receiverID = appId,
                extraData = MessageExtraData.Text(text = "test"),
                state = MessageState.RECEIVED,
            )
        )

    private fun participants(chatId: String): Int =
        db.query("SELECT COUNT(*) FROM chat_participants WHERE chat_id = ?", arrayOf(chatId)).use {
            it.moveToFirst(); it.getInt(0)
        }

    @Test
    fun aUserBecomesAGroupInItsOwnChat() = runBlocking {
        repo.insertContactWithChat(
            FullContactData.createUserContact(name = "Alpha", image = null, userId = appId, deviceId = deviceId)!!,
            IdentityLogSource.AUTO_CREATE,
        )
        repo.insertContactWithChat(
            FullContactData.createUserContact(name = "Bravo", image = null, userId = memberId)!!,
        )
        val chat = chatOf("Alpha")
        val message = addMessage(chat.chatId, from = appId)
        // Warm the private route, so a surviving cache entry would be read.
        assertEquals(chat.chatId, repo.getChatIdForReceivedPackage(appId, null))
        val user = draftOf(appId)
        assertEquals(1, repo.archivableMessageCount(user))

        repo.applyContactOperations(
            listOf(
                ContactOperation.UpdateExisting(
                    user,
                    user.copy(name = "Ops", type = ContactType.GROUP, deviceId = "", model = "", serial = ""),
                )
            )
        )

        assertTrue(repo.getAllContacts().single { it.contact.name == "Ops" } is FullContactData.Group)
        val converted = chatOf("Ops")
        assertEquals("same chat, converted", chat.chatId, converted.chatId)
        assertEquals(ChatType.GROUP, converted.chatType)
        assertEquals(MessageState.ARCHIVED, db.appMessagesDao().getMessageById(message)?.state)
        assertEquals("every member plus the group", 2, participants(chat.chatId))
        assertEquals("a group message reaches it", chat.chatId, repo.getChatIdForReceivedPackage(memberId, appId))
        assertEquals("no private route left", "", repo.getChatIdForReceivedPackage(appId, null))
        assertNull("a group holds no device", db.appContactsDao().findContactIdByDeviceId(deviceId))
    }

    @Test
    fun aGroupBecomesAUserInItsOwnChat() = runBlocking {
        repo.insertContactWithChat(
            FullContactData.createUserContact(name = "Bravo", image = null, userId = memberId)!!,
        )
        repo.insertContactWithChat(
            FullContactData.createGroupContact(name = "Ops", image = null, groupId = appId)!!,
        )
        val chat = chatOf("Ops")
        val message = addMessage(chat.chatId, from = memberId)
        assertEquals(chat.chatId, repo.getChatIdForReceivedPackage(memberId, appId))
        val group = draftOf(appId)

        repo.applyContactOperations(
            listOf(ContactOperation.UpdateExisting(group, group.copy(name = "Alpha", type = ContactType.USER)))
        )

        assertTrue(repo.getAllContacts().single { it.contact.name == "Alpha" } is FullContactData.User)
        val converted = chatOf("Alpha")
        assertEquals(chat.chatId, converted.chatId)
        assertEquals(ChatType.PRIVATE, converted.chatType)
        assertEquals(MessageState.ARCHIVED, db.appMessagesDao().getMessageById(message)?.state)
        assertEquals("a private chat has its one contact", 1, participants(chat.chatId))
        assertEquals("a private message reaches it", chat.chatId, repo.getChatIdForReceivedPackage(appId, null))
        assertEquals("nothing left to archive", 0, repo.archivableMessageCount(draftOf(appId)))
    }
}
