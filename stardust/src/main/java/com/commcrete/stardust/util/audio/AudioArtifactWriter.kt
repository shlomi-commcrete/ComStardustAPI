package com.commcrete.stardust.util.audio

import com.commcrete.stardust.util.DataManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.content.Context

/**
 * Persists the test feeder's intermediate and round-trip artifacts to disk:
 *  - normalized / filtered WAV files (post-AGC, post-LPF, post-notch, post-RNNoise),
 *  - WavTokenizer encoded tokens (text + binary forms),
 *  - the original 24 kHz mono reference WAV next to the decoded reconstruction.
 *
 * Pure I/O. No analysis, no logging beyond what the writer absolutely needs to
 * report write success/failure (callers decide when to log).
 */
internal object AudioArtifactWriter {

    /** Default destination for round-trip artifacts. */
    fun defaultArtifactDir(context: Context, destination: String): File {
        val baseDir = try {
            File(DataManager.fileLocation)
        } catch (t: Throwable) {
            context.getExternalFilesDir(null) ?: context.cacheDir
        }
        return File(baseDir, "test_feeder/${sanitizeFileStem(destination)}/roundtrip")
    }

    /** Strip path separators / weird chars so labels can safely become filenames. */
    fun sanitizeFileStem(s: String): String {
        val cleaned = s.trim().replace(Regex("[^A-Za-z0-9._\\-]+"), "_")
        return if (cleaned.isEmpty()) "src" else cleaned.take(64)
    }

    /** Writes a 16-bit PCM little-endian mono WAV file. Standard 44-byte RIFF header. */
    fun writePcm16Wav(file: File, pcm: ShortArray, sampleRate: Int) {
        val byteRate = sampleRate * 2
        val dataSize = pcm.size * 2
        val totalSize = 36 + dataSize
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intToLeBytes(totalSize))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intToLeBytes(16))
            out.write(shortToLeBytes(1))
            out.write(shortToLeBytes(1))
            out.write(intToLeBytes(sampleRate))
            out.write(intToLeBytes(byteRate))
            out.write(shortToLeBytes(2))
            out.write(shortToLeBytes(16))
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intToLeBytes(dataSize))
            val buf = ByteArray(dataSize)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bb.putShort(s)
            out.write(buf)
        }
    }

    /**
     * Writes encoded tokens as a human-readable text file, one token per line,
     * with a small header tying the file back to the original audio source.
     */
    fun writeTokensTxt(file: File, tokens: LongArray, source: Source, chunks: Int) {
        file.bufferedWriter().use { w ->
            w.write("# WavTokenizer encoded tokens\n")
            w.write("# source.label    : ${source.label}\n")
            w.write("# source.file     : ${source.file.absolutePath}\n")
            w.write("# source.size     : ${source.file.length()} bytes\n")
            w.write("# sample_rate     : ${AudioTestFeeder.TARGET_SAMPLE_RATE}\n")
            w.write("# chunk_samples   : ${AudioTestFeeder.SAMPLES_PER_CHUNK}   (${AudioTestFeeder.CHUNK_DURATION_MS} ms)\n")
            w.write("# tokens_per_sec  : 40\n")
            w.write("# codebook_size   : 4096 (12-bit)\n")
            w.write("# total_chunks    : $chunks\n")
            w.write("# total_tokens    : ${tokens.size}\n")
            w.write("# format          : one token (uint12 as decimal) per line\n")
            for (t in tokens) {
                w.write(t.toString())
                w.write("\n")
            }
        }
    }

    /**
     * Writes encoded tokens as a compact little-endian binary file:
     *   magic(4) "WTOK" | version(uint16=1) | tokenCount(uint32) | tokens[uint16] × N
     */
    fun writeTokensBin(file: File, tokens: LongArray) {
        java.io.DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.write("WTOK".toByteArray(Charsets.US_ASCII))
            out.write(shortToLeBytes(1))
            out.write(intToLeBytes(tokens.size))
            val buf = ByteArray(tokens.size * 2)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (t in tokens) {
                val clipped = (t.toInt() and 0xFFFF).toShort()
                bb.putShort(clipped)
            }
            out.write(buf)
        }
    }

    private fun intToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun shortToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
    )
}

