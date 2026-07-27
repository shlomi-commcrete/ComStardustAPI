package com.commcrete.stardust.audio.v2.adapter

import com.commcrete.stardust.audio.v2.application.receive.PttReceiveCoordinator
import com.commcrete.stardust.audio.v2.domain.Gain
import com.commcrete.stardust.audio.v2.domain.StreamKey

/**
 * Adapter ring — thin bridge from UI actions (a per-PTT volume slider / mute button, keyed by the
 * sender/stream id string) to the receive coordinator's per-stream volume API (R6).
 */
class VolumeUiAdapter(private val receive: PttReceiveCoordinator) {

    /** [level] in 0f..1f. */
    fun setVolume(streamId: String, level: Float) =
        receive.setVolume(StreamKey(streamId), Gain(level))

    fun setMuted(streamId: String, muted: Boolean) =
        receive.setMuted(StreamKey(streamId), muted)
}
