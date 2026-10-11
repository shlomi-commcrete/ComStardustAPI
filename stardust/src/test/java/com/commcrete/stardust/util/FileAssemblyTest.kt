package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

/**
 * [FileReceiver.assembleFileBytes] must give back exactly the bytes the sender chunked — no parity,
 * no padding — however the codeword arrived. Built the way FileSender.createSparePackages builds it:
 * the last data chunk padded to a full chunk, parity per block, interleaved past 255 packages.
 */
class FileAssemblyTest {

    private val chunk = 8

    private class Sent(val file: ByteArray, val start: StardustFileStartPackage, val codeword: List<ByteArray>)

    private fun send(fileSize: Int, spare: Int, seed: Int = 1): Sent {
        val file = Random(seed).nextBytes(fileSize)
        val chunks = file.toList().chunked(chunk).map { it.toByteArray() }.toMutableList()
        val padding = chunk - chunks.last().size
        chunks[chunks.lastIndex] = chunks.last().copyOf(chunk)
        val codeword = ReedSolomon(totalDataPackets = chunks.size, totalParityPackets = spare).encode(chunks)
        val start = StardustFileStartPackage(
            type = 0, total = codeword.size, spare = spare, spareData = padding, fileEnding = "bin", fileName = "f",
        )
        return Sent(file, start, codeword)
    }

    private fun Sent.receive(drop: Set<Int> = emptySet()): ByteArray =
        FileReceiver.assembleFileBytes(
            start,
            codeword.withIndex().filter { it.index !in drop }.associate { it.index to it.value },
        ).fold(ByteArray(0)) { acc, p -> acc + p }

    /** Regression: with nothing missing, parity and padding were written after the file. */
    @Test
    fun `every package arrived — the file and nothing else`() {
        val sent = send(fileSize = 75, spare = 4)
        assertArrayEquals(sent.file, sent.receive())
    }

    @Test
    fun `only parity lost — no decode needed, same file`() {
        val sent = send(fileSize = 75, spare = 4)
        val parity = ReedSolomon(totalDataPackets = sent.start.total - 4, totalParityPackets = 4).parityIndices()
        assertArrayEquals(sent.file, sent.receive(drop = parity.take(3).toSet()))
    }

    @Test
    fun `data lost within the parity — decoded`() {
        val sent = send(fileSize = 75, spare = 4)
        assertArrayEquals(sent.file, sent.receive(drop = setOf(0, 5, 9)))
    }

    /**
     * Regression: the data was taken to be the first total - spare packages. Past 255 packages
     * parity is interleaved per block, so those include a block's parity and miss later data.
     */
    @Test
    fun `multi-block, all data and no parity — the data, in order`() {
        val sent = send(fileSize = 300 * chunk - 3, spare = 40)
        val parity = ReedSolomon(totalDataPackets = sent.start.total - 40, totalParityPackets = 40).parityIndices()
        assertArrayEquals(sent.file, sent.receive(drop = parity))
    }

    /** What the old shortcut accepted as complete: exactly K packages, but not the data ones. */
    @Test
    fun `multi-block, the first K packages — not buildable`() {
        val sent = send(fileSize = 300 * chunk - 3, spare = 40)
        val k = sent.start.total - sent.start.spare
        val missing = (k until sent.start.total).toSet()
        val rs = ReedSolomon(totalDataPackets = k, totalParityPackets = 40)
        assertFalse(rs.canRecover(missing))
    }

    @Test
    fun `multi-block, data lost in each block within its parity — decoded`() {
        val sent = send(fileSize = 300 * chunk - 3, spare = 40)
        // One loss per block, at its first data package.
        assertArrayEquals(sent.file, sent.receive(drop = setOf(0, 200)))
    }

    @Test
    fun `no parity — packages in order`() {
        val file = Random(3).nextBytes(20)
        val chunks = file.toList().chunked(chunk).map { it.toByteArray() }
        val start = StardustFileStartPackage(
            type = 0, total = chunks.size, spare = 0, spareData = 0, fileEnding = "bin", fileName = "f",
        )
        val out = FileReceiver.assembleFileBytes(start, chunks.withIndex().associate { it.index to it.value })
            .fold(ByteArray(0)) { acc, p -> acc + p }
        assertArrayEquals(file, out)
    }
}
