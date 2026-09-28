package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.ai.codec.WavHelper
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Framework ring — writes one received PTT to a WAV file INCREMENTALLY, so the file on disk is a
 * valid, playable WAV after every chunk rather than only after the stream ends.
 *
 * The receive path used to buffer every decoded sample and write the WAV once, at end-of-stream. A
 * process death mid-PTT therefore lost the whole recording even though the user had already heard
 * most of it, and a long PTT sat in the heap until it finished. This writes through instead: header
 * first, then each chunk appended, with the two size fields patched in place afterwards.
 *
 * Patching the sizes after every append is what makes the partial file playable — a WAV whose
 * RIFF/data lengths still say 0 is not. It costs two four-byte writes per chunk (~50/s at a 20 ms
 * frame), which is why this is not batched: an always-correct header is worth more than the writes
 * it saves.
 *
 * Deliberately NOT fsync'd. Writes land in the page cache, which outlives the process, and process
 * death is the case this exists for. An fsync per chunk would buy power-loss/panic durability at the
 * price of a disk sync every few tens of milliseconds.
 *
 * NOT thread-safe: every call must come from the single writer thread that [PttReceiveStore] owns.
 * [hasAudio] is the one exception — it is read from the actor thread when a close cannot be waited
 * for, so [dataBytes] is volatile.
 */
internal class PttWavWriter(private val file: File) {

    private var raf: RandomAccessFile? = null

    /** Volatile only for [hasAudio]; every write to it happens on the writer thread. */
    @Volatile private var dataBytes: Int = 0

    /** Set by an [IOException]: the file is unusable, so later appends are dropped rather than retried. */
    private var broken = false

    /** After [close] the file is final; a late append must not reopen and truncate it. */
    private var finished = false

    /** The rate the header was written with — the first chunk's, kept for the whole stream. */
    private var headerRateHz = 0

    /** Whether any PCM actually reached the file. Safe to read from another thread. */
    val hasAudio: Boolean get() = dataBytes > 0

    /**
     * Append one decoded chunk, opening the file (and writing its header) on the first call — the
     * sample rate is not known before then. A chunk at a different rate than the header's is written
     * as-is: the header is already committed, and resampling a live PTT retroactively is worse than a
     * rate that is right for all but a pathological stream.
     */
    fun append(samples: ShortArray, rateHz: Int) {
        if (broken || finished || samples.isEmpty()) return
        try {
            val out = raf ?: open(rateHz) ?: return
            if (rateHz != headerRateHz) {
                Timber.tag(TAG).w("rate changed mid-stream ($headerRateHz -> $rateHz) for ${file.name}; keeping header rate")
            }
            out.seek(HEADER_BYTES.toLong() + dataBytes)
            out.write(shortsToLePcm16(samples))
            dataBytes += samples.size * 2
            patchSizes(out)
        } catch (e: IOException) {
            fail(e, "appending to")
        }
    }

    /**
     * Final size patch and close. Idempotent, and safe to call on a writer that never opened — a
     * stream that ended before its first decoded chunk leaves no file at all, which is what tells
     * [PttReceiveStore] to settle that row as failed rather than as an empty recording.
     */
    fun close() {
        finished = true
        val out = raf ?: return
        raf = null
        try {
            patchSizes(out)
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Could not finalize WAV header for ${file.name}")
        } finally {
            runCatching { out.close() }
        }
    }

    private fun open(rateHz: Int): RandomAccessFile? {
        file.parentFile?.mkdirs()
        return try {
            RandomAccessFile(file, "rw").also {
                // Truncate: the path is timestamped per stream, but a leftover file must never be
                // appended to — it would play back as two PTTs spliced together.
                it.setLength(0)
                it.write(WavHelper.createWavHeader(0, rateHz, CHANNELS, BITS_PER_SAMPLE))
                headerRateHz = rateHz
                raf = it
            }
        } catch (e: IOException) {
            fail(e, "opening")
            null
        }
    }

    /** RIFF chunk size at offset 4, data chunk size at offset 40 — both little-endian. */
    private fun patchSizes(out: RandomAccessFile) {
        out.seek(RIFF_SIZE_OFFSET)
        out.write(le32(HEADER_BYTES - 8 + dataBytes))
        out.seek(DATA_SIZE_OFFSET)
        out.write(le32(dataBytes))
    }

    private fun fail(e: IOException, what: String) {
        broken = true
        Timber.tag(TAG).e(e, "Error $what WAV ${file.absolutePath}; dropping the rest of this stream")
        raf?.let { runCatching { it.close() } }
        raf = null
    }

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private companion object {
        const val TAG = "PttWavWriter"
        const val HEADER_BYTES = 44
        const val RIFF_SIZE_OFFSET = 4L
        const val DATA_SIZE_OFFSET = 40L
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
    }
}

/** Decoded samples as little-endian PCM16 — the on-disk layout and the one the host callback takes. */
internal fun shortsToLePcm16(samples: ShortArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    var i = 0
    for (s in samples) {
        out[i++] = (s.toInt() and 0xFF).toByte()
        out[i++] = ((s.toInt() shr 8) and 0xFF).toByte()
    }
    return out
}
