package com.commcrete.stardust.audio.v2.session

import timber.log.Timber

/**
 * CODEC2 playback sink — **TODO STUB**.
 *
 * The real implementation must port the [android.media.AudioTrack] +
 * [android.media.audiofx.LoudnessEnhancer] setup that lives in
 * [com.commcrete.stardust.util.audio.PlayerUtils.playStream] (1076-line
 * file; the audio-routing helpers `syncBleDevice` /
 * `removeSyncBleDevices` should stay in PlayerUtils, only the
 * decode + playback chain moves here).
 *
 * For Phase 0/1 scaffolding this is a no-op so the package compiles
 * and so [PttReceiveCoordinator] can hand it pcm without crashing in
 * unit tests. Phase 4 (when CODEC2 receive switches to the v2
 * coordinator) is when the port should land.
 *
 * Implementation checklist for Phase 4:
 *  1. Construct one `AudioTrack` per stream key with
 *     `AudioFormat.ENCODING_PCM_16BIT`, mono, sample rate 8000.
 *  2. Apply the playback route from `SharedPreferencesUtil.getOutputDevice`
 *     via `setPreferredDevice` (mirror of `MicCaptureSource`).
 *  3. Optionally attach `LoudnessEnhancer` driven by
 *     `SharedPreferencesUtil.getLoudnessEnhancer`.
 *  4. Use `AudioTrack.write` in [enqueue]; release in [close].
 *  5. Fire `DataManager.getCallbacks()?.receivePTT(...)` /
 *     `startedReceivingPTT` / `stopReceivingPTT` at the right
 *     transition points (currently done by `PlayerUtils`).
 */
class Codec2PlaybackSink : PlaybackSink {

    init {
        Timber.tag(TAG).w("Codec2PlaybackSink is a Phase-0 stub — playback will NOT be audible")
    }

    override fun enqueue(
        pcm: ShortArray,
        sampleRateHz: Int,
        from: String,
        source: String?,
    ) {
        // TODO Phase 4: write to AudioTrack.
        Timber.tag(TAG).v(
            "stub enqueue: %d samples @ %d Hz (from=%s source=%s)",
            pcm.size, sampleRateHz, from, source,
        )
    }

    override fun close() {
        // TODO Phase 4: release AudioTrack + LoudnessEnhancer.
    }

    companion object {
        private const val TAG = "Codec2PlaybackSink"
    }
}

