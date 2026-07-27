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

    override fun close()
}
