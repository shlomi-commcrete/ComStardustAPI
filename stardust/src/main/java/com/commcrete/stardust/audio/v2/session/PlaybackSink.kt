package com.commcrete.stardust.audio.v2.session

import java.io.Closeable

/**
 * Sink for decoded PCM on the receive side.
 *
 * One playback sink per `(from|source)` stream key, created by
 * [PttReceiveCoordinator] when the first packet for a new stream
 * arrives, released when the stream goes idle for longer than the
 * eviction threshold.
 *
 * # Implementations
 *
 *  - [AiPlaybackSink] — wraps the existing
 *    [com.commcrete.aiaudio.media.PcmStreamPlayer] singleton (the AI
 *    playback path the legacy `PttReceiveManager` already uses).
 *  - [Codec2PlaybackSink] — wraps `AudioTrack` + `LoudnessEnhancer`
 *    (currently scattered through `PlayerUtils`). **TODO: port the
 *    AudioTrack setup from PlayerUtils when Phase 4 begins** —
 *    stubbed for now.
 */
interface PlaybackSink : Closeable {

    /**
     * Hand [pcm] to the playback engine. [sampleRateHz] is the codec's
     * decode rate (24000 for AI, 8000 for CODEC2). [from] and [source]
     * are the stream identifiers — used by [PcmStreamPlayer.enqueue]
     * to keep multiple talkers separated on the playback side.
     */
    fun enqueue(
        pcm: ShortArray,
        sampleRateHz: Int,
        from: String,
        source: String?,
    )

    /** Release the underlying audio engine for this stream. */
    override fun close()
}

