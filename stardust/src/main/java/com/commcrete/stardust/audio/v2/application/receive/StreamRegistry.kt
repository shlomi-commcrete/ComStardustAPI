package com.commcrete.stardust.audio.v2.application.receive

import com.commcrete.stardust.audio.v2.application.port.AudioCodec
import com.commcrete.stardust.audio.v2.domain.Gain
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.StreamKey
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Application layer — the R6 mechanism: a map of live [ReceiveStream]s keyed by [StreamKey], plus the
 * per-stream volume/mute state.
 *
 * Volume and mute are tracked SEPARATELY: [volume] is the user's chosen level and [muted] is an
 * independent flag, so muting then un-muting restores the prior level instead of snapping to unity.
 * Both persist across stream lifetime, so a level set before a stream's first packet applies the
 * moment the stream is created ([effective]).
 *
 * Stream creation is atomic ([java.util.concurrent.ConcurrentHashMap.computeIfAbsent]) so two packets
 * racing for a brand-new key can't spawn two streams (which would fork the decoder + double the
 * AudioTrack). [start] is called OUTSIDE the atomic block, by the creating caller only.
 */
class StreamRegistry(
    private val scope: CoroutineScope,
    private val onDecoded: (StreamKey, PcmChunk) -> Unit = { _, _ -> },
    private val onEvicted: (StreamKey) -> Unit = {},
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val maxSinks: Int = DEFAULT_MAX_SINKS,
) {
    private val streams = ConcurrentHashMap<StreamKey, ReceiveStream>()
    private val volume = ConcurrentHashMap<StreamKey, Gain>()
    private val muted = ConcurrentHashMap.newKeySet<StreamKey>()

    /** Look up or atomically create the stream for [key] under [codec]. */
    fun getOrCreate(key: StreamKey, codec: AudioCodec): ReceiveStream {
        streams[key]?.let { return it }
        if (streams.size >= maxSinks) reclaimIfOverBudget()

        var built: ReceiveStream? = null
        val stream = streams.computeIfAbsent(key) { k ->
            ReceiveStream(
                key = k,
                codecId = codec.codecId,
                decoder = codec.newDecoderSession(k),
                sink = codec.newPlaybackSink(k),
                sampleRateHz = codec.sampleRateHz,
                initialGain = effective(k),
                onClosed = ::onStreamClosed,
                onDecoded = onDecoded,
                idleTimeoutMs = idleTimeoutMs,
                scope = scope,
            ).also { built = it }
        }
        if (built === stream) stream.start() // start only the instance we just created
        return stream
    }

    /** Set [key]'s playback level (does not un-mute). Applies live if the stream exists. */
    fun setVolume(key: StreamKey, gain: Gain) {
        volume[key] = gain
        apply(key)
    }

    /** Mute/un-mute [key] without losing its chosen level. Applies live if the stream exists. */
    fun setMuted(key: StreamKey, isMuted: Boolean) {
        if (isMuted) muted.add(key) else muted.remove(key)
        apply(key)
    }

    /** Drop and close a stream (its sink + decoder are released). */
    fun evict(key: StreamKey) {
        streams.remove(key)?.close()
    }

    val activeStreams: Set<StreamKey> get() = streams.keys.toSet()

    private fun effective(key: StreamKey): Gain =
        if (muted.contains(key)) Gain.MUTE else volume[key] ?: Gain.UNITY

    private fun apply(key: StreamKey) {
        streams[key]?.setGain(effective(key))
    }

    private fun onStreamClosed(key: StreamKey) {
        streams.remove(key)
        onEvicted(key)
    }

    private fun reclaimIfOverBudget() {
        // TODO(lru): evict the true least-recently-used stream. Skeleton: prefer a muted stream, else any.
        val victim = streams.keys.firstOrNull { muted.contains(it) } ?: streams.keys.firstOrNull()
        victim?.let { evict(it) }
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_SINKS = 8
    }
}
