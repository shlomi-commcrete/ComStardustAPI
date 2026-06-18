package com.commcrete.stardust.audio.v2.session

import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.audio.v2.codec.CodecRegistry
import com.commcrete.stardust.audio.v2.codec.DecoderSession
import com.commcrete.stardust.stardust.model.StardustPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Codec-agnostic replacement for
 * [com.commcrete.stardust.ai.codec.PttReceiveManager] AND the CODEC2
 * receive half currently scattered across
 * [com.commcrete.stardust.util.audio.PlayerUtils.testPlayPackage] /
 * `audio/Codec2Decoder.kt`.
 *
 * # What's preserved from the AI v1 path
 *
 *  - **Per-stream `(from|source)` keying.** Multiple concurrent
 *    talkers on different carriers each get their own
 *    [DecoderSession] and [PlaybackSink]. Their decoder continuity
 *    cannot bleed into each other.
 *  - **Gap-reset.** Streams silent for longer than
 *    [STREAM_GAP_RESET_MS] are reset on their next packet — the
 *    coordinator drops the stream's stale decoder continuity
 *    (`DecoderSession.reset()`) so the next PTT on that channel
 *    starts clean.
 *  - **Idle eviction.** Streams idle longer than
 *    [STREAM_IDLE_EVICT_MS] are evicted from the map on the next
 *    incoming packet so the map can't grow unbounded.
 *  - **Codec mutex serialisation.** Every [DecoderSession.decode]
 *    runs under [CodecRegistry.withCodec], so receive cannot
 *    interleave with send-side encode OR send-side mirror decode for
 *    the same codec.
 *
 * # What's NEW (vs AI v1)
 *
 *  - **CODEC2 receive is now serialised.** The legacy
 *    `PlayerUtils.mCodec2Decoder` was a shared singleton hit without
 *    any lock; under v2 the per-stream `Codec2DecoderSession` runs
 *    under the same per-codec mutex as everything else.
 *  - **Per-stream playback sinks**, not the implicit
 *    `PcmStreamPlayer.enqueue(from, source)` keying — playback
 *    lifetime is now tied to stream lifetime.
 */
object PttReceiveCoordinator {

    private const val TAG = "PttReceiveCoordinator"

    /**
     * Idle gap that triggers a per-stream decoder reset on the next
     * packet. Matches `PttReceiveManager.STREAM_GAP_RESET_MS` so AI
     * behavior is preserved bit-for-bit.
     */
    private const val STREAM_GAP_RESET_MS = 2_000L

    /**
     * Idle eviction threshold. Streams silent for this long are
     * removed from the map (and their decoder + playback closed) on
     * the next incoming packet. Matches `PttReceiveManager`.
     */
    private const val STREAM_IDLE_EVICT_MS = 30_000L

    /**
     * Initial buffering before the FIRST packet of a stream hits
     * playback. Matches `PttReceiveManager.BUFFERING_TIME_MS`. Some
     * jitter absorption — the legacy code added it on the first chunk
     * only.
     */
    private const val BUFFERING_TIME_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Per-stream state keyed by `(from|source)`. */
    private val streams = ConcurrentHashMap<String, ReceiveStream>()

    /**
     * Optional override for the [PlaybackSink] factory. Test fixtures
     * can swap in a recording sink; default returns the codec's
     * built-in sink ([AiPlaybackSink] / [Codec2PlaybackSink]).
     */
    @Volatile
    var playbackSinkFactory: (AudioCodec) -> PlaybackSink = ::defaultPlaybackSink

    /**
     * Entry point — called from `StardustPackageHandler.handlePTT` /
     * `handlePTTAI` (under the [com.commcrete.stardust.audio.v2.flag.PttPipelineFeatureFlag]
     * branch).
     *
     * Routes [pkg] to the codec that owns its opcode, queues the
     * payload onto the per-stream channel, and lazily starts the
     * stream's decode coroutine.
     *
     * Returns `true` if the packet was accepted, `false` if no
     * registered codec claims this opcode (caller can fall back to
     * v1 dispatch).
     */
    fun onPacket(pkg: StardustPackage): Boolean {
        val codec = CodecRegistry.forOpCode(pkg.stardustOpCode) ?: run {
            Timber.tag(TAG).v("onPacket: no codec for opcode ${pkg.stardustOpCode} — ignoring")
            return false
        }
        val payload = extractPayload(codec, pkg) ?: return false
        val streamId = streamKey(pkg)
        val now = System.currentTimeMillis()

        // Evict idle streams opportunistically (only when we have
        // more than one stream — single-talker case stays fast).
        if (streams.size > 1) pruneIdleStreams(now)

        val stream = streams.getOrPut(streamId) {
            val sink = playbackSinkFactory(codec)
            val decoder = codec.newDecoderSession()
            ReceiveStream(
                key = streamId,
                from = pkg.from(),
                source = pkg.source(),
                codec = codec,
                decoder = decoder,
                sink = sink,
            )
        }

        // Cross-thread offer of this packet — never blocks the
        // dispatcher even when the decoder is slow.
        val send = stream.queue.trySend(IncomingPacket(payload, now))
        if (send.isFailure) {
            Timber.tag(TAG).w("Stream $streamId: queue trySend failed (closed?)")
            return false
        }
        // Ensure the stream's consumer coroutine is running.
        ensureConsumer(stream)
        return true
    }

    /**
     * Shut everything down — release all decoders, close all playback
     * sinks. Test/teardown only.
     */
    internal fun resetForTest() {
        val keys = streams.keys().toList()
        for (k in keys) {
            val s = streams.remove(k) ?: continue
            s.close()
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private fun ensureConsumer(stream: ReceiveStream) {
        synchronized(stream) {
            if (stream.job?.isActive == true) return
            stream.job = scope.launch {
                runConsumer(stream)
            }
        }
    }

    private suspend fun runConsumer(stream: ReceiveStream) {
        Timber.tag(TAG).d("Stream ${stream.key}: consumer started")
        try {
            for (packet in stream.queue) {
                if (!currentCoroutineContext().isActive) break
                val isContinuation =
                    stream.lastPacketTimeMs > 0L &&
                        (packet.receivedAtMs - stream.lastPacketTimeMs) < STREAM_GAP_RESET_MS
                val isFirst = stream.lastPacketTimeMs == 0L
                stream.lastPacketTimeMs = packet.receivedAtMs

                val pcm = try {
                    CodecRegistry.withCodec(stream.codec.id) {
                        // Reset decoder continuity if this is the
                        // start of a fresh stream OR a >2s gap on the
                        // same channel — matches the AI v1 path's
                        // `if (!isContinuation) { decoderState = INITIAL }`.
                        if (!isContinuation) stream.decoder.reset()
                        stream.decoder.decode(packet.payload)
                    }
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Stream ${stream.key}: decode failed")
                    continue
                }

                if (pcm.isEmpty()) continue

                // Match `PttReceiveManager.handleTokenizerChunk`:
                // add jitter buffer ONCE on the first packet.
                if (isFirst) delay(BUFFERING_TIME_MS)

                runCatching {
                    stream.sink.enqueue(
                        pcm = pcm,
                        sampleRateHz = stream.codec.sampleRateHz,
                        from = stream.from,
                        source = stream.source,
                    )
                }.onFailure { Timber.tag(TAG).w(it, "Stream ${stream.key}: sink enqueue failed") }
            }
        } finally {
            Timber.tag(TAG).d("Stream ${stream.key}: consumer ended")
        }
    }

    private fun pruneIdleStreams(now: Long) {
        val it = streams.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val age = now - entry.value.lastPacketTimeMs
            if (entry.value.lastPacketTimeMs > 0L && age > STREAM_IDLE_EVICT_MS) {
                Timber.tag(TAG).d("Evicting idle stream ${entry.key} (age=${age}ms)")
                it.remove()
                runCatching { entry.value.close() }
            }
        }
    }

    /**
     * Strip the codec's prefix from the raw packet payload. AI's
     * `0x00` byte is at index 0 (and would corrupt token unpacking
     * if fed through `BitPacking12.unpack12` as-is); CODEC2 has no
     * prefix so the payload is returned as-is.
     */
    private fun extractPayload(codec: AudioCodec, pkg: StardustPackage): ByteArray? {
        val raw = pkg.payloadBytes() ?: return null
        val prefixLen = codec.sendPayloadPrefix.size
        if (prefixLen == 0) return raw
        if (raw.size <= prefixLen) {
            Timber.tag(TAG).w("Packet too small (${raw.size} bytes) for ${codec.id.name} prefix")
            return null
        }
        return raw.copyOfRange(prefixLen, raw.size)
    }

    /**
     * Best-effort extraction of the byte payload from a [StardustPackage].
     * Falls back to `null` when the payload isn't representable as bytes —
     * caller drops the packet.
     *
     * TODO: replace the reflective/extension-guess approach with the
     * actual `StardustPackage` byte accessor (this codebase exposes
     * the payload via the int-array `data` field; the v1 receive
     * paths reach in directly). Once the production wiring lands,
     * swap this for the right accessor and delete the helper.
     */
    private fun StardustPackage.payloadBytes(): ByteArray? {
        // This is the slot for the real payload extraction. The v1
        // dispatcher hands the packet's `data` int-array through to
        // `PttReceiveManager.addNewData`/`PlayerUtils.testPlayPackage`
        // already as a `ByteArray` — see `StardustPackageHandler.handlePTT`/
        // `handlePTTAI`. The production wire-up patch should call
        // `PttReceiveCoordinator.onPacket(pkg, payloadBytes)` directly
        // with the bytes the dispatcher already has, removing the
        // need for this helper. Leaving as a TODO so the scaffolding
        // compiles standalone.
        return null
    }

    private fun StardustPackage.from(): String = ""   // TODO same wire-up note
    private fun StardustPackage.source(): String? = null

    private fun streamKey(pkg: StardustPackage): String = "${pkg.from()}|${pkg.source().orEmpty()}"

    private fun defaultPlaybackSink(codec: AudioCodec): PlaybackSink = when (codec.id) {
        com.commcrete.stardust.util.audio.RecorderUtils.CODE_TYPE.AI -> AiPlaybackSink()
        com.commcrete.stardust.util.audio.RecorderUtils.CODE_TYPE.CODEC2 -> Codec2PlaybackSink()
    }

    // ── data classes ─────────────────────────────────────────────────

    private data class IncomingPacket(
        val payload: ByteArray,
        val receivedAtMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IncomingPacket) return false
            return receivedAtMs == other.receivedAtMs &&
                payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * payload.contentHashCode() + receivedAtMs.hashCode()
    }

    /**
     * Per-stream container. Holds the consumer coroutine job, the
     * decoder session, the playback sink, and last-packet timestamp.
     *
     * Closing it cancels the consumer, closes the decoder, and
     * closes the sink. Idempotent.
     */
    private class ReceiveStream(
        val key: String,
        val from: String,
        val source: String?,
        val codec: AudioCodec,
        val decoder: DecoderSession,
        val sink: PlaybackSink,
    ) : AutoCloseable {
        val queue: Channel<IncomingPacket> = Channel(Channel.UNLIMITED)
        @Volatile var job: Job? = null
        @Volatile var lastPacketTimeMs: Long = 0L

        override fun close() {
            queue.close()
            job?.cancel()
            runCatching { decoder.close() }
            runCatching { sink.close() }
        }
    }
}



