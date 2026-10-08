package com.commcrete.stardust.stardust

import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.FileReceiver
import com.commcrete.stardust.util.TextPartPacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the parts of a long incoming text become one message: which parts are joined, and that the
 * row stays RECEIVING until LAST or until the continuation window passes without a further part.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingTextAssemblerTest {

    private class RecordingSink : IncomingTextAssembler.Sink {
        val calls = mutableListOf<String>()
        private var nextId = 1L

        override suspend fun open(pkg: StardustAPIPackage, text: String): Long {
            val id = nextId++
            calls += "open#$id:$text"
            return id
        }

        override suspend fun append(messageId: Long, text: String) {
            calls += "append#$messageId:$text"
        }

        override suspend fun complete(messageId: Long?, pkg: StardustAPIPackage, text: String) {
            calls += "complete#${messageId ?: "-"}:$text"
        }

        override suspend fun abandon(
            messageId: Long?,
            pkg: StardustAPIPackage,
            text: String,
            failure: FileReceiver.FileFailure,
        ) {
            // The reason is part of the call, so a timeout and a lost link cannot be confused.
            calls += "abandon#${messageId ?: "-"}:$text" + if (failure == FileReceiver.FileFailure.MISSING) "" else "/$failure"
        }
    }

    private val alicePrivate = IncomingTextAssembler.Key("alice", chatId = "alice", groupId = null, deliveryType = 1)
    private val aliceInGroup = IncomingTextAssembler.Key("alice", chatId = "g1", groupId = "g1", deliveryType = 1)
    private val aliceOtherCarrier = alicePrivate.copy(deliveryType = 2)
    private val pkg = StardustAPIPackage(senderId = "alice", receiverId = "me", carrier = Carrier(0, CarrierType.HR))
    private val lrPkg = pkg.copy(carrier = Carrier(1, CarrierType.LR))
    private val hrWindow = TextPartPacing.continuationWindowMs(CarrierType.HR, bandwidth = null)
    private val lrWindow = TextPartPacing.continuationWindowMs(CarrierType.LR, bandwidth = null)

    /** Not a child of the test: the consumer loops forever. Same scheduler, so virtual time applies. */
    private fun TestScope.assembler(sink: RecordingSink) = IncomingTextAssembler(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        sink = sink,
        // By type only: the production default also reads the bandwidth from the radio config.
        continuationWindowMs = { TextPartPacing.continuationWindowMs(it.carrier?.type, bandwidth = null) },
        log = { _, _ -> },
    )

    @Test
    fun `a single-part text is saved directly without an in-flight row`() = runTest {
        val sink = RecordingSink()
        assembler(sink).onPart(alicePrivate, pkg, "hi", isLast = true)
        runCurrent()

        assertEquals(listOf("complete#-:hi"), sink.calls)
    }

    /**
     * The field capture: HR parts sent 800 ms apart arrived 2.9 s and 2.05 s apart. All of them
     * land in the first part's row, which is settled once, on LAST — never in between.
     */
    @Test
    fun `parts are joined in one row that settles only on LAST`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, pkg, "one ", isLast = false)
        runCurrent()
        advanceTimeBy(2_900)
        assembler.onPart(alicePrivate, pkg, "two ", isLast = false)
        runCurrent()
        advanceTimeBy(2_050)
        assembler.onPart(alicePrivate, pkg, "three", isLast = true)
        runCurrent()

        assertEquals(
            listOf("open#1:one ", "append#1:one two ", "complete#1:one two three"),
            sink.calls,
        )
    }

    /** The window restarts on every part: total duration does not matter, only each silence. */
    @Test
    fun `the window is measured from the latest part, not the first`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, pkg, "a", isLast = false)
        runCurrent()
        repeat(3) {
            advanceTimeBy(hrWindow - 1)
            assembler.onPart(alicePrivate, pkg, "b", isLast = false)
            runCurrent()
        }

        assertEquals("nothing settled across three almost-full windows", 4, sink.calls.size)
        assertTrue(sink.calls.none { it.startsWith("complete") || it.startsWith("abandon") })

        advanceTimeBy(hrWindow + 1)
        assertEquals("abandon#1:abbb", sink.calls.last())
    }

    @Test
    fun `without LAST the row fails when the window passes, and the next part is new`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, pkg, "old", isLast = false)
        runCurrent()
        advanceTimeBy(hrWindow + 1)
        assembler.onPart(alicePrivate, pkg, "new", isLast = false)
        runCurrent()

        // abandon, not complete: LAST never came, so nothing says the text is whole.
        assertEquals(listOf("open#1:old", "abandon#1:old", "open#2:new"), sink.calls)
    }

    @Test
    fun `after LAST the next part is a new message`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, pkg, "a", isLast = false)
        assembler.onPart(alicePrivate, pkg, "b", isLast = true)
        runCurrent()
        advanceTimeBy(500)
        assembler.onPart(alicePrivate, pkg, "next", isLast = false)
        runCurrent()

        assertEquals(listOf("open#1:a", "complete#1:ab", "open#2:next"), sink.calls)
    }

    @Test
    fun `the window follows the carrier the parts arrive on`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, lrPkg, "slow ", isLast = false)
        runCurrent()
        advanceTimeBy(hrWindow + 1)
        assembler.onPart(alicePrivate, lrPkg, "radio", isLast = false)
        runCurrent()
        assertEquals(
            "an HR window's silence must not end an LR message",
            listOf("open#1:slow ", "append#1:slow radio"),
            sink.calls,
        )

        advanceTimeBy(lrWindow + 1)
        assertEquals("abandon#1:slow radio", sink.calls.last())
    }

    @Test
    fun `an unknown carrier waits as long as the slowest one`() {
        assertEquals(lrWindow, TextPartPacing.continuationWindowMs(type = null, bandwidth = null))
        assertTrue(hrWindow < lrWindow)
    }

    @Test
    fun `private and group parts from the same sender are never joined`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, pkg, "private-1 ", isLast = false)
        assembler.onPart(aliceInGroup, pkg, "group-1 ", isLast = false)
        assembler.onPart(alicePrivate, pkg, "private-2", isLast = true)
        assembler.onPart(aliceInGroup, pkg, "group-2", isLast = true)
        runCurrent()

        assertEquals(
            listOf(
                "open#1:private-1 ",
                "open#2:group-1 ",
                "complete#1:private-1 private-2",
                "complete#2:group-1 group-2",
            ),
            sink.calls,
        )
    }

    @Test
    fun `parts over different carriers are never joined`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, pkg, "a", isLast = false)
        assembler.onPart(aliceOtherCarrier, pkg, "b", isLast = true)
        runCurrent()

        assertEquals(listOf("open#1:a", "complete#-:b"), sink.calls)
    }

    @Test
    fun `settleAll fails every open message and the next part is new`() = runTest {
        val sink = RecordingSink()
        val assembler = assembler(sink)

        assembler.onPart(alicePrivate, pkg, "cut ", isLast = false)
        assembler.onPart(aliceInGroup, pkg, "also cut", isLast = false)
        assembler.settleAll()
        runCurrent()
        advanceTimeBy(lrWindow * 2)
        assembler.onPart(alicePrivate, pkg, "after reconnect", isLast = false)
        runCurrent()

        assertEquals(
            listOf(
                "open#1:cut ",
                "open#2:also cut",
                "abandon#1:cut /DISCONNECTED",
                "abandon#2:also cut/DISCONNECTED",
                "open#3:after reconnect",
            ),
            sink.calls,
        )
    }
}
