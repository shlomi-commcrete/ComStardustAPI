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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application layer — one incoming PTT stream (R5) with its own volume (R6).
 *
 * Owns its OWN [decoder] (per-stream continuity — no shared singleton save/restore) and its OWN
 * [sink] (one AudioTrack), so simultaneous streams on different channels decode and play in parallel
 * without colliding. A fresh instance is created per burst by [StreamRegistry], so a sender's second
 * PTT never inherits stale decoder continuity.
 *
 * [onFrame] never blocks the caller (unbounded ingress of tiny encoded frames); playout jitter/drop
 * is the [sink]'s concern. The stream self-closes on the terminal frame or after [idleTimeoutMs] of
 * silence, notifying the registry via [onClosed] so decoders and AudioTracks don't leak.
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
) {
    private val incoming = Channel<EncodedFrame>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    /** Open the sink and start the per-stream decode→play loop. Idempotent. */
    fun start() {
        if (job != null) return
        sink.open(sampleRateHz)
        sink.setGain(initialGain)
        job = scope.launch {
            try {
                while (isActive) {
                    // No frame for idleTimeoutMs → treat the stream as ended and evict.
                    val result = withTimeoutOrNull(idleTimeoutMs) { incoming.receiveCatching() } ?: break
                    val frame = result.getOrNull() ?: break            // channel closed
                    val pcm = CodecRegistry.withCodec(codecId) { decoder.decode(frame) }
                    onDecoded(key, pcm)                                // notify integration (persist/replay)
                    sink.write(pcm)
                    if (frame.isTerminal) break                        // end-of-PTT
                }
            } finally {
                closeInternal()
            }
        }
    }

    /** Enqueue an incoming encoded frame. Never blocks; dropped if the stream is already closed. */
    fun onFrame(frame: EncodedFrame) {
        incoming.trySend(frame)
    }

    /** Apply volume/mute live; forwarded to the sink, which applies it off the PCM path (R6). */
    fun setGain(gain: Gain) {
        sink.setGain(gain)
    }

    /** Externally evict this stream (registry over-budget reclaim). */
    fun close() {
        closeInternal()
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
}
