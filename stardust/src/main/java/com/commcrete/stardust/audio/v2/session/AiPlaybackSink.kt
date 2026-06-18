package com.commcrete.stardust.audio.v2.session

import com.commcrete.aiaudio.media.PcmStreamPlayer

/**
 * AI playback sink — thin wrapper over the existing
 * [PcmStreamPlayer] singleton.
 *
 * v1's [com.commcrete.stardust.ai.codec.PttReceiveManager.handleTokenizerChunk]
 * ends with:
 *
 * ```
 * PcmStreamPlayer.enqueue(finalPcmData, 24000, chunk.from, chunk.source)
 * ```
 *
 * — v2 keeps the exact same call. The buffering delay
 * (`delay(BUFFERING_TIME_MS)` for the first chunk of a stream) lives
 * in [PttReceiveCoordinator] now, not here, so this sink stays
 * codec-agnostic.
 *
 * **Close is a no-op.** `PcmStreamPlayer` is a process-wide singleton
 * keyed by `(from|source)`; the player itself decides when to release
 * its [android.media.AudioTrack] (typically after an idle timeout
 * inside the player). Per-stream sinks come and go — the underlying
 * playback survives.
 */
class AiPlaybackSink : PlaybackSink {

    override fun enqueue(
        pcm: ShortArray,
        sampleRateHz: Int,
        from: String,
        source: String?,
    ) {
        PcmStreamPlayer.enqueue(pcm, sampleRateHz, from, source)
    }

    override fun close() {
        // No-op — see class kdoc.
    }
}

