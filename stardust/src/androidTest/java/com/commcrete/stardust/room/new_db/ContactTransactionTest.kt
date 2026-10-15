package com.commcrete.stardust.room.new_db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.room.new_db.contact.FullContactData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A contact save lands whole or not at all.
 *
 * A save is several writes. Before transactions, a refusal part-way kept everything written
 * before it: here, Alpha's new App ID, with Bravo's rename refused after it.
 */
@RunWith(AndroidJUnit4::class)
class ContactTransactionTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    private val alphaId = "0a1b2c3d"
    private val bravoId = "55667788"
    private val newId = "99887766"

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

    private suspend fun draftOf(id: String): ContactDraft =
        repo.getAllContacts().map(ContactDraft.Companion::fromFullContactData).single { it.appId == id }

    @Test
    fun aRefusedStepUndoesTheWholeSave() = runBlocking {
        repo.insertContactWithChat(FullContactData.createUserContact(name = "Alpha", image = null, userId = alphaId)!!)
        repo.insertContactWithChat(FullContactData.createUserContact(name = "Bravo", image = null, userId = bravoId)!!)
        val alpha = draftOf(alphaId)
        val bravo = draftOf(bravoId)
        // A stale chat already holds the name Bravo will be renamed to. chats.name is UNIQUE,
        // so renaming Bravo's chat is refused — after Alpha's App ID has been written.
        db.openHelper.writableDatabase.execSQL("INSERT INTO chats (id, name, type) VALUES ('stale', 'Charlie', 'PRIVATE')")

        val failure = runCatching {
            repo.applyContactOperations(
                listOf(
                    ContactOperation.UpdateExisting(alpha, alpha.copy(appId = newId)),
                    ContactOperation.UpdateExisting(bravo, bravo.copy(name = "Charlie")),
                )
            )
        }

        assertTrue("the save reports its failure", failure.isFailure)
        assertEquals("Alpha keeps its App ID", alpha.name, draftOf(alphaId).name)
        assertNull("the new App ID was not kept", db.appContactsDao().findContactIdByUserId(newId))
        assertEquals("Bravo keeps its name", "Bravo", draftOf(bravoId).name)
    }
}
