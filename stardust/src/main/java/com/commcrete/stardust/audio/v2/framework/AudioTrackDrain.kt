package com.commcrete.stardust.audio.v2.framework

import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Poll interval while waiting for the playback head. */
private const val DRAIN_POLL_MS = 20L

/** Headroom over the computed remaining duration — output latency, scheduling jitter. */
private const val DRAIN_SLACK_MS = 250L

/**
 * Lets a MODE_STREAM track play out the [framesWritten] frames it was given, then leaves it stopped
 * (the caller still releases it). Shared by [Codec2PlaybackSink] and [AiPlaybackSink].
 *
 * `stop()` on a stream track plays out what is already buffered — including a buffer too short to have
 * reached the start threshold — whereas `release()` right after it discards it. So: stop, then wait for
 * the head to reach [framesWritten]. Some devices reset the head to 0 once playout completes, so a head
 * that moves backwards also counts as done. The wait is bounded by the remaining audio's duration plus
 * slack, so a track that never advances costs no more than what it had left to play.
 */
internal suspend fun AudioTrack.playOutAndStop(framesWritten: Long, sampleRateHz: Int) {
    val remaining = framesWritten - headFrames()
    runCatching { stop() }
    if (remaining <= 0 || sampleRateHz <= 0) return
    val budgetMs = remaining * 1000 / sampleRateHz + DRAIN_SLACK_MS
    withTimeoutOrNull(budgetMs) {
        var last = headFrames()
        while (last < framesWritten) {
            delay(DRAIN_POLL_MS)
            val head = headFrames()
            if (head < last) break
            last = head
        }
    }
}

/** `playbackHeadPosition` is an unsigned 32-bit frame counter. */
private fun AudioTrack.headFrames(): Long =
    runCatching { playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(Long.MAX_VALUE)
