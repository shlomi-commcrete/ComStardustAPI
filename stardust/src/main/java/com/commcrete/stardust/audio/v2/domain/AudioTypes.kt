package com.commcrete.stardust.audio.v2.domain

/**
 * Domain layer — the audio payload entities that flow through both passes.
 * Pure Kotlin: plain arrays + primitives, no Android buffers, no codec specifics.
 */

/**
 * A block of raw PCM16 mono samples tagged with its rate and owning recording.
 *
 * Used on the capture→DSP→encode path (send) and the decode→playback path (receive).
 * [sampleRateHz] travels with the samples so the receive [PlaybackSink] can pick the right
 * AudioTrack rate without a global constant (CODEC2 8 kHz vs WavTokenizer 24 kHz).
 */
class PcmChunk(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val owner: RecordingId? = null, // set on the send path; null on the receive (decode) path
)

/**
 * One encoded, wire-ready frame. The byte layout (77-byte nibble-packed CODEC2, 31-byte 12-bit
 * WavTokenizer, …) is an adapter concern — this ring only knows it is opaque [payload] bytes
 * belonging to [owner] at index [seq], and whether it is the terminal frame of the recording.
 *
 * There is no transmit ticket here: the [owner]'s `OutboundBuffer` carries the ticket, and the gate
 * drains per-buffer, so the frame itself never needs it.
 */
class EncodedFrame(
    val codecId: CodecId,
    val payload: ByteArray,
    val isTerminal: Boolean,
    val owner: RecordingId? = null,   // send-side only (which recording produced it)
    val seq: SequenceNumber? = null,  // send-side only (frame index within the recording)
)

/**
 * Per-stream playback gain (R6). `0f` == muted; `1f` == unity. Values > 1 are the sink adapter's
 * concern (e.g. CODEC2's LoudnessEnhancer boost). Held in an AtomicReference at the sink so a
 * volume/mute change applies live, off the decode path.
 */
data class Gain(val value: Float) {
    val muted: Boolean get() = value <= 0f

    companion object {
        val UNITY = Gain(1f)
        val MUTE = Gain(0f)
    }
}
