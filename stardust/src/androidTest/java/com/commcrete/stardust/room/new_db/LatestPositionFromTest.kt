package com.commcrete.stardust.room.new_db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * A contact's last known position: the newest LOCATION or SOS message an id sent, in any chat,
 * whatever else it sent since.
 */
@RunWith(AndroidJUnit4::class)
class LatestPositionFromTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    private val me = "a0a0a0a0"
    private val appX = "0a1b2c3d"
    private val devD = "11223344"

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

    private suspend fun chatOf(name: String, appId: String, device: String? = null): String {
        repo.insertContactWithChat(FullContactData.createUserContact(name = name, image = null, userId = appId, deviceId = device)!!)
        return repo.getChatSummaries().first().single { it.name == name }.chatId
    }

    private suspend fun from(chatId: String, sender: String, data: MessageExtraData, at: Long) =
        db.appMessagesDao().addMessage(
            MessageEntity(chatId = chatId, senderID = sender, receiverID = me, extraData = data, state = MessageState.RECEIVED, epochTimeMs = at)
        )

    @Test
    fun noPositionYetReadsAsNull() = runBlocking {
        val chat = chatOf("Alpha", appX)
        from(chat, appX, MessageExtraData.Text("hi"), at = 1_000)
        assertNull(repo.observeLatestPositionFrom(appX).first())
    }

    @Test
    fun theNewestLocationOrSosWinsOverLaterText() = runBlocking {
        val chat = chatOf("Alpha", appX)
        from(chat, appX, MessageExtraData.Location(1.0, 2.0, 0.0), at = 1_000)
        from(chat, appX, MessageExtraData.Sos(3.0, 4.0, 0.0), at = 2_000)
        from(chat, appX, MessageExtraData.Text("after"), at = 3_000)
        val latest = repo.observeLatestPositionFrom(appX).first()?.extraData as MessageExtraData.GeoData
        assertEquals(3.0, latest.latitude, 0.0)
    }

    @Test
    fun eachIdIsFollowedOnItsOwn() = runBlocking {
        val chat = chatOf("Alpha", appX, devD)
        from(chat, appX, MessageExtraData.Location(1.0, 1.0, 0.0), at = 1_000)
        from(chat, devD, MessageExtraData.Location(9.0, 9.0, 0.0), at = 2_000)
        val app = repo.observeLatestPositionFrom(appX).first()?.extraData as MessageExtraData.GeoData
        val device = repo.observeLatestPositionFrom(devD).first()?.extraData as MessageExtraData.GeoData
        assertEquals(1.0, app.latitude, 0.0)
        assertEquals(9.0, device.latitude, 0.0)
    }
}
