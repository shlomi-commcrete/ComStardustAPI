package com.commcrete.stardust.util.audio

import kotlin.math.roundToInt

/**
 * Stateful counterpart to [AudioDsp.resamplePolyphase] for one continuous stream split
 * into chunks (live PTT recording, RNNoise's internal 48 kHz round-trip).
 *
 * [AudioDsp.resamplePolyphase] treats every call as an independent, self-contained
 * buffer: the FIR kernel gets truncated at both ends of *each call*, because it has no
 * samples outside the array it was given. For a one-shot whole-file resample that's the
 * correct, unavoidable edge behaviour. Applied to consecutive chunks of one ongoing
 * recording, it means every chunk boundary gets that same truncated-kernel treatment —
 * an audible, periodic multi-dB gain dip at every chunk seam.
 *
 * This class carries the trailing samples of each chunk forward as left-context for the
 * next chunk's kernel taps, so only the true start of the stream (first call) and the true
 * end (after [flush]) get the natural edge fade — interior chunk boundaries are computed
 * with the full kernel, identically to resampling the whole stream in one shot.
 *
 * Not thread-safe, and not meant to be: matches [AudioDsp.resamplePolyphase]'s own
 * "single audio thread per session" contract. Create one instance per stream and drive
 * [process]/[flush] from that stream's processing thread only.
 */
internal class StreamingPolyphaseResampler(private val srcRate: Int, private val dstRate: Int) {

    private val passthrough = srcRate == dstRate
    private val ratio = dstRate.toDouble() / srcRate.toDouble()
    private val kernel = if (passthrough) null else AudioDsp.getOrBuildKernel(srcRate, dstRate)

    // Trailing samples retained purely as left-context for the next process() call.
    private var history = ShortArray(0)
    // Global index (in the virtual infinite source stream) of history[0].
    private var historyBase = 0L
    // Global index of the next output sample to produce.
    private var nextOutputIndex = 0L
    // Cumulative input samples ever passed to process(), across all calls.
    private var totalInputFed = 0L

    // Total output length this stream should EVENTUALLY produce, using the same
    // floor(inLen*ratio) convention as AudioDsp.resamplePolyphase's one-shot outLen — NOT
    // "however many samples the kernel could validly compute": for a non-exact ratio, more
    // centers stay in-bounds than floor(inLen*ratio) implies, so without this cap process()+
    // flush() would emit MORE total samples than a one-shot resample of the same audio would,
    // silently drifting the stream out of sync with its declared sample rate/duration.
    private fun targetTotalOutput(): Long =
        if (totalInputFed <= 0L) 0L else (totalInputFed * ratio).toLong().coerceAtLeast(1L)

    /** Resample [chunk] as the continuation of every previous [process] call on this instance. */
    fun process(chunk: ShortArray): ShortArray {
        if (passthrough || chunk.isEmpty()) return chunk
        val k = kernel!!
        val halfLen = k.halfLen

        totalInputFed += chunk.size
        val targetTotal = targetTotalOutput()

        val combined = ShortArray(history.size + chunk.size)
        System.arraycopy(history, 0, combined, 0, history.size)
        System.arraycopy(chunk, 0, combined, history.size, chunk.size)
        val combinedBase = historyBase
        val availableEnd = combinedBase + combined.size

        var outBuf = ShortArray(((chunk.size * ratio).toInt() + 8).coerceAtLeast(8))
        var outCount = 0

        while (nextOutputIndex < targetTotal) {
            val center = nextOutputIndex / ratio
            val centerInt = center.toLong()
            // Right-side taps (up to halfLen samples ahead) must be fully available.
            // Near the true end of the stream this holds back the tail — flush() emits it.
            if (centerInt + halfLen >= availableEnd) break

            val frac = center - centerInt
            val phaseIdx = (frac * k.numPhases + 0.5).toInt().coerceIn(0, k.numPhases - 1)
            val phaseOff = phaseIdx * k.tapsPerPhase
            val localCenter = (centerInt - combinedBase).toInt()

            // Left side may still be truncated at the true start of the stream (no history yet).
            val jMin = maxOf(-halfLen, -localCenter)
            var acc = 0.0
            for (j in jMin..halfLen) {
                acc += combined[localCenter + j].toDouble() * k.table[phaseOff + j + halfLen]
            }

            if (outCount == outBuf.size) outBuf = outBuf.copyOf(outBuf.size * 2 + 8)
            outBuf[outCount++] = acc.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            nextOutputIndex++
        }

        // Retain just enough trailing samples for the next call's left-context.
        val nextCenterFloor = (nextOutputIndex.toDouble() / ratio).toLong()
        val desiredHistoryBase = (nextCenterFloor - halfLen).coerceAtLeast(combinedBase)
        val keep = (availableEnd - desiredHistoryBase).toInt().coerceIn(0, combined.size)
        history = if (keep == combined.size) combined else combined.copyOfRange(combined.size - keep, combined.size)
        historyBase = availableEnd - keep

        return outBuf.copyOf(outCount)
    }

    /**
     * Emit the tail held back by [process] because it lacked right-side context — call this
     * once after the last real chunk of a stream. This last stretch genuinely has no future
     * audio to draw on, so (like [AudioDsp.resamplePolyphase]'s own buffer-edge behaviour) it
     * gets the natural truncated-kernel fade; that's correct here, not a bug.
     *
     * Resets the instance so it can be reused for a new stream afterwards.
     */
    fun flush(): ShortArray {
        if (passthrough) {
            reset()
            return ShortArray(0)
        }
        val k = kernel!!
        val halfLen = k.halfLen
        val targetTotal = targetTotalOutput()
        val combined = history
        val combinedBase = historyBase
        val availableEnd = combinedBase + combined.size

        var outBuf = ShortArray(8)
        var outCount = 0
        while (nextOutputIndex < targetTotal) {
            val center = nextOutputIndex / ratio
            val centerInt = center.toLong()
            // Defensive only: the (nextOutputIndex < targetTotal) bound guarantees centerInt <
            // availableEnd here (targetTotal = floor(totalInputFed*ratio) keeps every produced
            // center within the fed sample range) — this should never actually trigger.
            if (centerInt >= availableEnd) break

            val frac = center - centerInt
            val phaseIdx = (frac * k.numPhases + 0.5).toInt().coerceIn(0, k.numPhases - 1)
            val phaseOff = phaseIdx * k.tapsPerPhase
            val localCenter = (centerInt - combinedBase).toInt()

            val jMin = maxOf(-halfLen, -localCenter)
            val jMax = minOf(halfLen, combined.size - 1 - localCenter)
            var acc = 0.0
            for (j in jMin..jMax) {
                acc += combined[localCenter + j].toDouble() * k.table[phaseOff + j + halfLen]
            }

            if (outCount == outBuf.size) outBuf = outBuf.copyOf(outBuf.size * 2)
            outBuf[outCount++] = acc.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            nextOutputIndex++
        }

        reset()
        return outBuf.copyOf(outCount)
    }

    /** Drop all carried state. Use when abandoning a stream without calling [flush] (e.g. session reset). */
    fun reset() {
        history = ShortArray(0)
        historyBase = 0L
        nextOutputIndex = 0L
        totalInputFed = 0L
    }
}
