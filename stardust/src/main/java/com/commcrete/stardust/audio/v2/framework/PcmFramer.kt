package com.commcrete.stardust.audio.v2.framework

/**
 * Framework ring — turns the arbitrary-sized buffers an [android.media.AudioRecord] hands back into
 * fixed-size frames, plus exactly one short tail frame at the end of the stream.
 *
 * Extracted from [MicCaptureSource]'s capture loop so the property that matters can be tested on a
 * plain JVM: **every sample offered comes out exactly once, in order**. Concatenating every
 * [nextFrame] result and then [drain] reproduces the offered stream byte for byte — no sample
 * duplicated (which would stutter the audio and desync the decoder's continuity) and none dropped
 * (which silently truncated every recording before the tail was emitted at all).
 *
 * Pull-style rather than returning a list, because the caller emits into a suspending flow: it loops
 * on [nextFrame] and suspends between frames without this class having to know about coroutines.
 *
 * [drain] is deliberately single-shot. The tail is the one frame that is not self-limiting — a second
 * call could re-send audio already on the wire — so "sent once" is enforced here, by consuming the
 * remainder, rather than by the order of statements at the call site.
 *
 * Deliberately free of Android, coroutine and domain types (`ShortArray` in, `ShortArray` out): the
 * caller owns rate/[com.commcrete.stardust.audio.v2.domain.RecordingId] and wraps the frames in
 * [com.commcrete.stardust.audio.v2.domain.PcmChunk].
 *
 * NOT thread-safe, and holds one stream's worth of state: one instance per recording, used only from
 * the capture thread.
 *
 * @param frameSamples samples per whole frame; must be positive.
 */
class PcmFramer(private val frameSamples: Int) {

    init {
        require(frameSamples > 0) { "frameSamples must be positive, was $frameSamples" }
    }

    /**
     * Unconsumed samples live in `buffer[readPos until writePos]`.
     *
     * A plain [ShortArray] with a moving read position, not the `ArrayList<Short>` this replaced:
     * that boxed every sample and dropped consumed ones with `removeAt(0)` in a loop, which shifts the
     * whole backing array once per sample — quadratic work on the capture thread, ~25 times a second.
     */
    private var buffer = ShortArray(frameSamples * 2)
    private var readPos = 0
    private var writePos = 0

    private var drained = false

    /** Samples offered but not yet handed back by [nextFrame] / [drain]. */
    val pendingSamples: Int get() = writePos - readPos

    /**
     * Take the first [length] samples of [buffer] into this framer. Values beyond [length] are ignored
     * — `AudioRecord.read` fills only a prefix, and the rest of the caller's array is stale audio from
     * the previous read.
     *
     * A non-positive [length] is a no-op, so the caller can pass a read result through unchecked.
     */
    fun offer(buffer: ShortArray, length: Int = buffer.size) {
        if (length <= 0) return
        val n = minOf(length, buffer.size)
        ensureCapacity(n)
        System.arraycopy(buffer, 0, this.buffer, writePos, n)
        writePos += n
    }

    /**
     * The next whole frame, or `null` when fewer than [frameSamples] samples are pending. Returns a
     * fresh array each time, so the caller may hand it downstream without copying.
     */
    fun nextFrame(): ShortArray? {
        if (pendingSamples < frameSamples) return null
        val frame = buffer.copyOfRange(readPos, readPos + frameSamples)
        readPos += frameSamples
        return frame
    }

    /**
     * The remainder once the stream has ended: `1..frameSamples-1` samples, or `null` when nothing is
     * left. Call it after [nextFrame] has returned `null`; a whole frame still pending would be
     * returned here as-is rather than held back.
     *
     * Single-shot — every later call returns `null`, even if [offer] is called again.
     */
    fun drain(): ShortArray? {
        if (drained) return null
        drained = true
        if (pendingSamples <= 0) return null
        val tail = buffer.copyOfRange(readPos, writePos)
        readPos = writePos
        return tail
    }

    /**
     * Make room for [incoming] more samples: first by sliding the unconsumed remainder back to index 0
     * (the steady state — the remainder is always smaller than one frame), and only then by growing.
     */
    private fun ensureCapacity(incoming: Int) {
        if (writePos + incoming <= buffer.size) return

        val remaining = pendingSamples
        if (remaining + incoming <= buffer.size) {
            System.arraycopy(buffer, readPos, buffer, 0, remaining)
        } else {
            var newSize = buffer.size * 2
            while (newSize < remaining + incoming) newSize *= 2
            val grown = ShortArray(newSize)
            System.arraycopy(buffer, readPos, grown, 0, remaining)
            buffer = grown
        }
        readPos = 0
        writePos = remaining
    }
}