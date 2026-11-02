package com.commcrete.stardust.util

import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.request_objects.takeHistoryFrom
import com.commcrete.stardust.request_objects.toJson
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operator's ids over time: a message keeps its sender after an App ID or radio change,
 * and an id taken over from a contact is not the operator's before they took it.
 */
class SelfIdentityTest {

    /** As SharedPreferencesUtil.setAppUser does: the new user takes its history from the old. */
    private fun register(previous: RegisterUser?, appId: String, deviceId: String?, at: Long) =
        RegisterUser("Eagle", appId, deviceId).also { it.takeHistoryFrom(previous, at) }

    @Test
    fun `logged out, nothing is mine`() {
        assertFalse(SelfIdentity.of(null).wasMine("a1", 0))
    }

    @Test
    fun `a fresh login holds its ids since forever`() {
        val me = SelfIdentity.of(register(null, "a1", "d1", at = 100))
        assertTrue(me.wasMine("a1", Long.MIN_VALUE))
        assertTrue(me.wasMine("A1", Long.MAX_VALUE - 1))
        assertTrue(me.wasOwnRadio("d1", 0))
    }

    @Test
    fun `an App ID change keeps the old one in its period and starts the new one now`() {
        val first = register(null, "a1", "d1", at = 0)
        val me = SelfIdentity.of(register(first, "b2", "d1", at = 1_000))
        assertTrue(me.wasMine("a1", 999))
        assertFalse(me.wasMine("a1", 1_000))
        assertTrue(me.wasMine("b2", 1_000))
        assertFalse("b2 was a contact's before", me.wasMine("b2", 999))
        assertFalse(me.isMineNow("a1"))
    }

    @Test
    fun `a new radio closes the old radio's period`() {
        val first = register(null, "a1", "d1", at = 0)
        val me = SelfIdentity.of(register(first, "a1", "d2", at = 2_000))
        assertTrue(me.wasOwnRadio("d1", 1_999))
        assertFalse(me.wasOwnRadio("d1", 2_000))
        assertTrue(me.wasOwnRadio("d2", 2_000))
        assertFalse("an App ID is not a radio", me.wasOwnRadio("a1", 0))
    }

    @Test
    fun `a first radio is held since forever, a later one from now`() {
        val noRadio = register(null, "a1", null, at = 0)
        val first = register(noRadio, "a1", "d1", at = 10)
        assertTrue(SelfIdentity.of(first).wasOwnRadio("d1", 0))
        val cleared = register(first, "a1", null, at = 20)
        val second = register(cleared, "a1", "d2", at = 30)
        val me = SelfIdentity.of(second)
        assertFalse(me.wasOwnRadio("d2", 29))
        assertTrue(me.wasOwnRadio("d2", 30))
    }

    @Test
    fun `A to B and back to A`() {
        val a = register(null, "a1", null, at = 0)
        val b = register(a, "b2", null, at = 100)
        val me = SelfIdentity.of(register(b, "a1", null, at = 200))
        assertTrue(me.wasMine("a1", 50))
        assertFalse(me.wasMine("a1", 150))
        assertTrue(me.wasMine("a1", 250))
    }

    @Test
    fun `re-registering the same ids adds nothing`() {
        var user = register(null, "a1", "d1", at = 0)
        repeat(3) { user = register(user, "a1", "d1", at = 100L * (it + 1)) }
        assertNull(user.formerIds)
        assertNull(user.appIdSinceMs)
    }

    @Test
    fun `a clock set back never inverts a period`() {
        val first = register(null, "a1", null, at = 0)
        val second = register(first, "b2", null, at = 1_000)
        val third = register(second, "c3", null, at = 500)
        val former = third.formerIds!!.last()
        assertEquals("b2", former.id)
        assertTrue(former.toMs >= (former.fromMs ?: Long.MIN_VALUE))
    }

    @Test
    fun `history survives Gson, and JSON written before it reads as none`() {
        val first = register(null, "a1", "d1", at = 0)
        val user = register(first, "b2", "d1", at = 1_000)
        val back = Gson().fromJson(user.toJson(), RegisterUser::class.java)
        assertEquals(SelfIdentity.of(user), SelfIdentity.of(back))

        val old = Gson().fromJson("""{"displayName":"Eagle","appId":"a1","deviceId":"d1"}""", RegisterUser::class.java)
        assertNull(old.formerIds)
        assertTrue(SelfIdentity.of(old).wasMine("a1", 0))
    }
}
