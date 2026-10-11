package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileReceiver.isValidStart] guards everything a receiver builds from a start header that came off
 * the air. Before it, `spare > total` reached [ReedSolomon]'s constructor on the main thread, whose
 * `require` threw and crashed the host.
 */
class FileStartValidationTest {

    private fun start(total: Int, spare: Int, spareData: Int = 0) = StardustFileStartPackage(
        type = 0, total = total, spare = spare, spareData = spareData, fileEnding = "txt", fileName = "f",
    )

    @Test
    fun `a transfer with data and parity is valid`() {
        assertTrue(FileReceiver.isValidStart(start(total = 14, spare = 4, spareData = 12)))
    }

    @Test
    fun `a transfer with no parity is valid`() {
        assertTrue(FileReceiver.isValidStart(start(total = 1, spare = 0)))
    }

    @Test
    fun `the largest numberable transfer is valid`() {
        assertTrue(FileReceiver.isValidStart(start(total = FileSender.MAX_TOTAL_PACKAGES, spare = 100)))
    }

    /** Regression: this one reached ReedSolomon(totalDataPackets = -3, ...) and crashed. */
    @Test
    fun `more parity than packages is refused`() {
        val corrupt = start(total = 4, spare = 7)
        assertFalse(FileReceiver.isValidStart(corrupt))
        // What the guard keeps the receiver from doing.
        assertTrue(runCatching {
            ReedSolomon(totalDataPackets = corrupt.total - corrupt.spare, totalParityPackets = corrupt.spare)
        }.isFailure)
    }

    @Test
    fun `only parity is refused`() {
        assertFalse(FileReceiver.isValidStart(start(total = 5, spare = 5)))
    }

    @Test
    fun `no packages is refused`() {
        assertFalse(FileReceiver.isValidStart(start(total = 0, spare = 0)))
    }
}
