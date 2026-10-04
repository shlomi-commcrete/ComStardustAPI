package com.commcrete.stardust.audio.v2.application.port

import com.commcrete.stardust.audio.v2.domain.Gain
import com.commcrete.stardust.audio.v2.domain.PcmChunk

/**
 * Application layer — PORT. One output per receive stream (R6).
 *
 * There is one [PlaybackSink] per [com.commcrete.stardust.audio.v2.domain.StreamKey], each backed by
 * its own AudioTrack at the codec's native rate — NOT a per-codec singleton. [write] (the decode path)
 * and [setGain] (the UI path) are deliberately independent: gain is read off the PCM path, so muting
 * PTT 2 is instant and never sits behind PTT 2's decoded-audio backlog, while PTT 1 stays at unity.
 */
interface PlaybackSink : AutoCloseable {

    /** Lazily build the AudioTrack for this stream's [sampleRateHz]. */
    fun open(sampleRateHz: Int)

    /** Enqueue decoded PCM for playback. Never reads gain. */
    suspend fun write(pcm: PcmChunk)

    /** Apply volume/mute live (thread-safe; e.g. `AudioTrack.setVolume`). `Gain.MUTE` silences without pausing decode. */
    fun setGain(gain: Gain)

    /** How many times playout ran dry since [open] (`AudioTrack.getUnderrunCount`); -1 when unknown. Diagnostics only. */
    val underrunCount: Int get() = -1

    /**
     * End of stream: suspend until everything already [write]n has actually played, bounded by its own
     * duration plus slack. [write] returns once PCM is in the track's buffer, not once it is heard, so
     * closing straight after the last write drops up to a buffer's worth of the tail. Call [close]
     * afterwards; skip this on an eviction, where an immediate stop is the point.
     */
    suspend fun drain() {}

    override fun close()
}
