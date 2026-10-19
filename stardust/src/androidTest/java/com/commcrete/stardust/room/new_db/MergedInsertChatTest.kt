package com.commcrete.stardust.room.new_db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.contacts.ContactOperation
import com.commcrete.stardust.room.new_db.chat.ChatEntity
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An insert that lands on an existing contact — it brings an id that contact still holds —
 * keeps that contact's one private chat; and a contact an older build left with two private
 * chats is folded back to one, history kept.
 *
 * The case seen on device: "Someone Else" added with Moish's App ID and Dor's radio, Moish
 * deleted into it. The insert merged into Moish's row and opened a second chat beside his.
 */
@RunWith(AndroidJUnit4::class)
class MergedInsertChatTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    private val me = "a0a0a0a0"
    private val moishApp = "11000010"
    private val dorApp = "11000017"
    private val dorRadio = "00000165"

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

    private suspend fun privateChats() = repo.getChatSummaries().first().filter { it.chatType == ChatType.PRIVATE }

    private suspend fun received(chatId: String, from: String): Long =
        db.appMessagesDao().addMessage(
            MessageEntity(chatId = chatId, senderID = from, receiverID = me, extraData = MessageExtraData.Text("in"), state = MessageState.RECEIVED)
        )

    @Test
    fun anInsertMergedIntoAContactKeepsItsOneChat() = runBlocking {
        user("Moish", moishApp)
        user("Dor", dorApp, dorRadio)
        val moishChat = privateChats().single { it.name == "Moish" }.chatId
        val fromMoish = received(moishChat, moishApp)
        val moish = draftNamed("Moish")
        val dor = draftNamed("Dor")
        val someoneElse = ContactDraft(name = "Someone Else", appId = moishApp, deviceId = dorRadio, type = ContactType.USER)

        // As the import's table writes it: strip Dor's radio, add Someone Else, delete Moish into it.
        repo.applyContactOperations(
            listOf(
                ContactOperation.UpdateExisting(dor, dor.copy(deviceId = "")),
                ContactOperation.Insert(someoneElse),
                ContactOperation.DeleteExisting(moish, reparentTo = someoneElse),
            )
        )

        val chats = privateChats()
        assertEquals("one chat each for Dor and Someone Else", listOf("Dor", "Someone Else"), chats.map { it.name }.sorted())
        assertEquals("Moish's history is Someone Else's", chats.single { it.name == "Someone Else" }.chatId,
            db.appMessagesDao().getMessageById(fromMoish)?.chatId)
    }

    @Test
    fun aSecondPrivateChatIsFoldedIntoTheOneWithHistory() = runBlocking {
        user("Moish", moishApp)
        val kept = privateChats().single().chatId
        val old = received(kept, moishApp)
        val contactId = db.appContactsDao().findContactIdByUserId(moishApp)!!
        // What the old insert left: a second private chat for the same contact.
        val extra = ChatEntity(name = "Moish copy", type = ChatType.PRIVATE)
        db.appChatsDao().insertChatWithParticipants(extra, listOf(contactId))
        val stray = received(extra.id, moishApp)

        assertEquals(1, repo.mergeDuplicatePrivateChats())

        val chats = privateChats()
        assertEquals(1, chats.size)
        assertEquals(kept, chats.single().chatId)
        assertEquals(kept, db.appMessagesDao().getMessageById(old)?.chatId)
        assertEquals("the extra chat's messages move, not lost", kept, db.appMessagesDao().getMessageById(stray)?.chatId)
        assertEquals("nothing left to fold", 0, repo.mergeDuplicatePrivateChats())
    }
}
