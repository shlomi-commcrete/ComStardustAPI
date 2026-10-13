package com.commcrete.stardust.room.new_db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.room.new_db.audit.IdentityLogSource
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.FullContactData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An id that leaves a contact stops routing inbound traffic to it.
 *
 * Inbound messages find their chat through two caches: sender id → chat, and id → contact.
 * Neither used to drop an entry when an id was removed, replaced or moved, so a message
 * from that id kept landing in the old chat — where no target shows it, because a private
 * chat only lists messages from the contact's current ids.
 *
 * Each test warms the caches first, so a stale entry is what gets read if one survives.
 */
@RunWith(AndroidJUnit4::class)
class ContactIdCacheTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    private val oldId = "0a1b2c3d"
    private val newId = "99887766"
    private val deviceId = "11223344"

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

    private suspend fun insertUser(name: String, appId: String, device: String? = null) =
        repo.insertContactWithChat(
            FullContactData.createUserContact(name = name, image = null, userId = appId, deviceId = device)!!,
            IdentityLogSource.AUTO_CREATE,
        )

    private suspend fun draft(name: String): ContactDraft =
        repo.getAllContacts().map(ContactDraft.Companion::fromFullContactData).single { it.name == name }

    private suspend fun privateChatOf(name: String): String =
        repo.getChatSummaries().first().single { it.chatType == ChatType.PRIVATE && it.name == name }.chatId

    /** Resolves [id] the way an inbound packet does, so both caches are read and filled. */
    private suspend fun warm(id: String): String {
        repo.isContactExistsByMainCommunicationId(id)
        return repo.getChatIdForReceivedPackage(id, null)
    }

    @Test
    fun aReplacedAppIdNoLongerReachesTheOldChat() = runBlocking {
        insertUser("Alpha", oldId)
        val chat = warm(oldId)
        assertEquals(privateChatOf("Alpha"), chat)

        val alpha = draft("Alpha")
        repo.applyContactOperations(listOf(ContactOperation.UpdateExisting(alpha, alpha.copy(appId = newId))))

        assertEquals("nobody owns the old id", "", repo.getChatIdForReceivedPackage(oldId, null))
        assertFalse(repo.isContactExistsByMainCommunicationId(oldId))
        assertEquals("the new id reaches the same chat", chat, repo.getChatIdForReceivedPackage(newId, null))
    }

    @Test
    fun aDeviceMovedToAnotherContactReachesItsNewChat() = runBlocking {
        insertUser("Alpha", oldId, deviceId)
        insertUser("Bravo", newId)
        assertEquals(privateChatOf("Alpha"), warm(deviceId))

        val alpha = draft("Alpha")
        val bravo = draft("Bravo")
        repo.applyContactOperations(
            listOf(
                ContactOperation.UpdateExisting(alpha, alpha.copy(deviceId = "")),
                ContactOperation.UpdateExisting(bravo, bravo.copy(deviceId = deviceId)),
            )
        )

        assertEquals(privateChatOf("Bravo"), repo.getChatIdForReceivedPackage(deviceId, null))
        assertEquals(
            db.appContactsDao().findContactIdByUserId(newId),
            repo.findContactIdByMainCommunicationId(deviceId),
        )
    }

    @Test
    fun aDeletedContactsIdsResolveToNobody() = runBlocking {
        insertUser("Alpha", oldId, deviceId)
        warm(oldId)
        warm(deviceId)

        repo.applyContactOperations(listOf(ContactOperation.DeleteExisting(draft("Alpha"))))

        assertEquals("", repo.getChatIdForReceivedPackage(oldId, null))
        assertFalse(repo.isContactExistsByMainCommunicationId(oldId))
        assertFalse(repo.isContactExistsByMainCommunicationId(deviceId))
    }

    /**
     * A write no repository path knows about — here a bare DAO call — still reaches the caches,
     * through the database observer. It is asynchronous, so the check polls with a deadline.
     */
    @Test
    fun aDirectDatabaseWriteStillClearsTheLookups() = runBlocking {
        insertUser("Alpha", oldId)
        warm(oldId)

        db.appContactsDao().removeUserId(oldId)

        val deadline = System.currentTimeMillis() + 3_000
        while (repo.getChatIdForReceivedPackage(oldId, null).isNotEmpty() &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals("", repo.getChatIdForReceivedPackage(oldId, null))
        assertFalse(repo.isContactExistsByMainCommunicationId(oldId))
    }

    /** An insert that merges into an existing contact by its device evicts that contact's old App ID. */
    @Test
    fun anAppIdEvictedByAMergingInsertResolvesToNobody() = runBlocking {
        insertUser("Alpha", oldId, deviceId)
        val chat = warm(oldId)

        repo.insertContactsWithChats(
            listOf(FullContactData.createUserContact(name = "Alpha", image = null, userId = newId, deviceId = deviceId)!!),
        )

        assertNotEquals("the evicted id still reaches the chat", chat, repo.getChatIdForReceivedPackage(oldId, null))
        assertFalse(repo.isContactExistsByMainCommunicationId(oldId))
    }
}
