package com.commcrete.stardust.audio.v2.application.receive

import com.commcrete.stardust.audio.v2.application.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.application.port.DecoderSession
import com.commcrete.stardust.audio.v2.application.port.PlaybackSink
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.Gain
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.StreamKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Application layer — one incoming PTT stream (R5) with its own volume (R6).
 *
 * Owns its OWN [decoder] (per-stream continuity — no shared singleton save/restore) and its OWN
 * [sink] (one AudioTrack), so simultaneous streams on different channels decode and play in parallel
 * without colliding. A fresh instance is created per burst by [StreamRegistry], so a sender's second
 * PTT never inherits stale decoder continuity.
 *
 * The decode itself runs on [decodeContext] — a thread the send path's encode never uses — while the
 * blocking [PlaybackSink.write] stays on [scope], so one stream waiting on its AudioTrack never holds
 * up another stream's decode.
 *
 * [onFrame] never blocks the caller (unbounded ingress of tiny encoded frames); playout jitter/drop
 * is the [sink]'s concern. The stream self-closes on the terminal frame or after [idleTimeoutMs] of
 * silence, notifying the registry via [onClosed] so decoders and AudioTracks don't leak.
 *
 * Every frame reports a [ReceiveFrameTiming] to [onTiming], to tell a gap caused on the air (late
 * arrival) apart from one caused here (slow decode, a decode lock held by someone else).
 */
class ReceiveStream(
    val key: StreamKey,
    private val codecId: CodecId,
    private val decoder: DecoderSession,
    private val sink: PlaybackSink,
    private val sampleRateHz: Int,
    private val initialGain: Gain,
    private val onClosed: (StreamKey) -> Unit,
    private val onDecoded: (StreamKey, PcmChunk) -> Unit,
    private val idleTimeoutMs: Long,
    private val scope: CoroutineScope,
    private val decodeContext: CoroutineContext = EmptyCoroutineContext,
    private val onTiming: (ReceiveFrameTiming) -> Unit = {},
) {
    private val incoming = Channel<Arrival>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    /** Open the sink and start the per-stream decode→play loop. Idempotent. */
    fun start() {
        if (job != null) return
        sink.open(sampleRateHz)
        sink.setGain(initialGain)
        job = scope.launch {
            var index = 0
            var previousArrivalNs = 0L
            var firstWriteNs = 0L
            var audioWrittenMs = 0.0
            // True when the sender ended the stream (terminal frame / idle) rather than someone
            // closing it from outside — only then is the buffered tail played out before release.
            var endedNaturally = false
            try {
                while (isActive) {
                    // No frame for idleTimeoutMs → treat the stream as ended and evict.
                    val result = withTimeoutOrNull(idleTimeoutMs) { incoming.receiveCatching() }
                    if (result == null) {
                        endedNaturally = true
                        break
                    }
                    val arrival = result.getOrNull() ?: break          // channel closed
                    val frame = arrival.frame
                    val dequeuedNs = System.nanoTime()

                    var lockedNs = 0L
                    val pcm = withContext(decodeContext) {
                        CodecRegistry.withDecode(codecId) {
                            lockedNs = System.nanoTime()
                            decoder.decode(frame)
                        }
                    }
                    val decodedNs = System.nanoTime()

                    onDecoded(key, pcm)                                // notify integration (persist/replay)
                    sink.write(pcm)
                    val writtenNs = System.nanoTime()

                    if (firstWriteNs == 0L) firstWriteNs = decodedNs
                    val audioMs = if (pcm.sampleRateHz > 0) pcm.samples.size * 1000.0 / pcm.sampleRateHz else 0.0
                    audioWrittenMs += audioMs
                    runCatching {
                        onTiming(
                            ReceiveFrameTiming(
                                key = key,
                                codecId = codecId,
                                index = index,
                                isTerminal = frame.isTerminal,
                                sinceLastArrivalMs = if (index == 0) -1.0 else ms(arrival.arrivedNs - previousArrivalNs),
                                queuedMs = ms(dequeuedNs - arrival.arrivedNs),
                                decodeLockWaitMs = ms(lockedNs - dequeuedNs),
                                decodeMs = ms(decodedNs - lockedNs),
                                writeMs = ms(writtenNs - decodedNs),
                                audioMs = audioMs,
                                // Audio handed to the track minus wall time since the first hand-off: what
                                // is left to play before the next chunk must land. At or below 0 is a gap.
                                bufferedAheadMs = audioWrittenMs - ms(writtenNs - firstWriteNs),
                                underruns = sink.underrunCount,
                            )
                        )
                    }
                    previousArrivalNs = arrival.arrivedNs
                    index++
                    if (frame.isTerminal) {                            // end-of-PTT
                        endedNaturally = true
                        break
                    }
                }
            } finally {
                if (endedNaturally) closeAfterPlayout() else closeInternal()
            }
        }
    }

    /** Enqueue an incoming encoded frame. Never blocks; dropped if the stream is already closed. */
    fun onFrame(frame: EncodedFrame) {
        incoming.trySend(Arrival(frame, System.nanoTime()))
    }

    /** Apply volume/mute live; forwarded to the sink, which applies it off the PCM path (R6). */
    fun setGain(gain: Gain) {
        sink.setGain(gain)
    }

    /** Externally evict this stream (registry over-budget reclaim). */
    fun close() {
        closeInternal()
    }

    /**
     * Natural end: let the track play out what it already holds before releasing it — closing right
     * after the last write dropped up to ~0.5 s of every received PTT.
     *
     * [onClosed] runs FIRST, before the play-out: it removes this stream from the registry, so the
     * sender's next burst gets a fresh stream instead of being fed into this one, which no longer reads
     * [incoming]. It also ends the receive store's record for this key before that next burst starts
     * one. A cancellation during the play-out (pipeline shutdown) still releases the track.
     */
    private suspend fun closeAfterPlayout() {
        if (!closed.compareAndSet(false, true)) return
        incoming.close()
        runCatching { decoder.close() }
        onClosed(key)
        try {
            sink.drain()
        } finally {
            runCatching { sink.close() }
        }
    }

    private fun closeInternal() {
        if (closed.compareAndSet(false, true)) {
            incoming.close()
            job?.cancel()
            runCatching { decoder.close() }
            runCatching { sink.close() }
            onClosed(key)
        }
    }

    private class Arrival(val frame: EncodedFrame, val arrivedNs: Long)

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
}

/**
 * Where one received frame's time went, from reaching [ReceiveStream.onFrame] to its PCM being handed
 * to the AudioTrack. All durations are in milliseconds.
 *
 * Reading it: [sinceLastArrivalMs] well above [audioMs] means the gap came over the air. A high
 * [decodeLockWaitMs] means another decode (another stream, or the send-side mirror) held the decoder.
 * A [decodeMs] close to [audioMs] means the model cannot keep up on this device. [bufferedAheadMs] at
 * or below 0, or a rising [underruns], is the gap actually being heard.
 */
data class ReceiveFrameTiming(
    val key: StreamKey,
    val codecId: CodecId,
    val index: Int,
    val isTerminal: Boolean,
    /** -1 for the first frame of the stream. */
    val sinceLastArrivalMs: Double,
    /** Waiting in the stream's queue behind the previous frame's decode + write. */
    val queuedMs: Double,
    /** Switching to the decode thread plus waiting for the codec's decode lock. */
    val decodeLockWaitMs: Double,
    val decodeMs: Double,
    /** Blocked in [PlaybackSink.write] — the track's buffer was full. */
    val writeMs: Double,
    /** Playback length of the decoded chunk. */
    val audioMs: Double,
    val bufferedAheadMs: Double,
    /** Cumulative for this stream; -1 when the sink cannot tell. */
    val underruns: Int,
)
