package com.commcrete.stardust.audio.v2.framework

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import com.commcrete.stardust.PttRecordingError
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.StardustLog
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.R
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.audio.SoundChannel
import com.commcrete.stardust.util.audio.SoundPlayer
import com.commcrete.stardust.util.audio.filters.configs.AudioCaptureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Framework ring — the single physical microphone behind the [CaptureSource] port.
 *
 * Ported from `AudioRecorderCodec2`: uses [AudioCaptureConfig] to pick a device-native rate (USB
 * preferred), then emits ~40 ms PcmChunks at that native rate, plus one final short chunk carrying
 * whatever did not fill a whole frame. Gain/DSP/resample happen downstream in the per-session
 * [ResampleGainDsp], so this class stays a pure capture edge.
 *
 * [requestedRateHz] is the OWNING CODEC's native rate, not a constant: the AI codec needs 24 kHz
 * capture (legacy `AudioRecorderAI.RECORDER_SAMPLE_RATE`) and CODEC2 needs 8 kHz. Requesting 8 kHz for
 * the AI path would band-limit the audio to 4 kHz and then upsample it, which makes the WavTokenizer
 * encoder emit meaningless tokens. [audioSource] likewise differs per codec (`getCodecAudioSource` vs
 * `getAIAudioSource`).
 *
 * One instance per recording; [stop] releases the mic on key-up, and collector cancellation also tears
 * the [AudioRecord] down via the `finally`.
 *
 * Opening the mic is preceded by the PTT start beep (`R.raw.ptt_started_beep`), which this flow waits
 * out — so the beep is never part of the audio, at the cost of its own duration in key-down-to-capture
 * latency.
 *
 * [stop] is authoritative rather than advisory: flipping [running] off is not enough, because this loop
 * spends nearly all of its time parked inside a blocking [AudioRecord.read] and only sees the flag when
 * that read returns. So [stop] also calls [AudioRecord.stop] on the live record, which is what makes the
 * blocked read return, and it is honoured even when it arrives before the mic has finished opening.
 * Without that, a device that stops delivering audio (mic stolen by a call, USB audio unplugged) parks
 * this loop forever: the flow never completes, and the recording that owns it never finalizes.
 */
class MicCaptureSource(
    private val context: Context,
    private val requestedRateHz: Int,
    private val audioSource: Int,
    /**
     * Invoked once, on the capture thread, when the `AudioRecord` is confirmed to be recording — the
     * host-visible "PTT recording started" moment. It is NOT the key-down: the bridge only enqueues that,
     * and it may still be rejected or fail before the mic opens.
     */
    private val onCaptureStarted: () -> Unit = {},
    /** Invoked once the microphone has actually been released (key-up, watchdog, or cancellation). */
    private val onCaptureStopped: () -> Unit = {},
    /**
     * Invoked instead of [onCaptureStarted] when the microphone could not be opened
     * ([PttRecordingError.MIC_UNAVAILABLE]) or opened already muted by Android (one of the MIC_MUTED /
     * MIC_TAKEN / MIC_ACCESS reasons).
     */
    private val onCaptureFailed: (PttRecordingError) -> Unit = {},
    /**
     * Invoked once, after [onCaptureStopped], when Android muted the microphone mid-recording: capture was
     * stopped there, and the audio captured before the mute still goes out.
     */
    private val onCaptureMuted: (PttRecordingError) -> Unit = {},
) : CaptureSource {

    /** Set from [MicSilenceWatch]'s callback when Android muted this recording mid-way. */
    @Volatile private var mutedBy: PttRecordingError? = null

    @Volatile private var running = false

    /** Set by [stop] even before the record exists, so a key-up that beats the mic open still wins. */
    @Volatile private var stopRequested = false

    /** The microphone, published so [stop] can reach it from another thread; see [MicCapture.stop]. */
    @Volatile private var capture: MicCapture? = null

    /** For [stop]'s trace line only. */
    @Volatile private var traceId: Long? = null

    @SuppressLint("MissingPermission")
    override fun start(id: RecordingId): Flow<PcmChunk> = flow {
        traceId = id.value
        // "You may speak", before the microphone exists. Playing it here rather than at key-down is what
        // makes the ordering a guarantee instead of a race: [SoundPlayer.playAndAwait] suspends until the
        // beep has finished, so it cannot be captured, encoded, transmitted, or written into the local
        // mirror WAV. It also plays before applyInputRoute below, so it comes out of whatever the user is
        // listening to rather than through the PTT communication device.
        //
        // Skipped when the key-up already landed — a tap released inside the beep should not delay its
        // own teardown by playing one.
        if (!stopRequested) {
            SoundPlayer.playAndAwait(context, R.raw.ptt_started_beep, channel = SoundChannel.SYSTEM)
        }

        val plan = AudioCaptureConfig.buildCapturePlan(
            context = context,
            requestedRate = requestedRateHz,
            defaultAudioSource = audioSource,
        )
        val rate = plan.captureRate
        val frameSamples = ((rate * FRAME_MS) / 1000).coerceAtLeast(160)

        val mic = MicCapture(context, plan, frameSamples)
        // Published before the open, so a stop() racing it can already reach the record.
        capture = mic
        val capturing = mic.open()
        // A record that opens RECORDING may still be fed silence; then the recording is a failure, not a start.
        val silence = MicSilenceWatch(context, backgroundCanMute = true)
        val mutedAtOpen = if (capturing) mic.audioSessionId?.let(silence::currentCause) else null
        val started = capturing && mutedAtOpen == null
        // A stop() that landed while the mic was opening must not be overwritten back to `true` here —
        // that is the race that leaves the microphone live after a key-up the user already made.
        running = started && !stopRequested
        StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "mic v2 ${id.value} opened: capturing=$capturing muted=$mutedAtOpen running=$running")
        when {
            !capturing -> onCaptureFailed(PttRecordingError.MIC_UNAVAILABLE)
            mutedAtOpen != null -> onCaptureFailed(mutedAtOpen)
            else -> {
                onCaptureStarted()
                // A mute that arrives later (screen locks, a call starts) ends capture here, as a key-up would.
                mic.audioSessionId?.let { sessionId ->
                    silence.watch(sessionId) { cause ->
                        mutedBy = cause
                        running = false
                        mic.stop()
                    }
                }
            }
        }

        val read = ShortArray(frameSamples)
        val framer = PcmFramer(frameSamples)
        var lastRead = 0
        try {
            while (running) {
                val n = mic.read(read)
                lastRead = n
                // A negative return is an error code (ERROR_DEAD_OBJECT when the device goes away,
                // ERROR_INVALID_OPERATION on a stopped record), and it is returned immediately and
                // forever after — treating it as "no data yet" would spin this loop on a CPU for the
                // rest of the recording. End the flow instead and let the session flush its tail.
                if (n < 0) break
                framer.offer(read, n)
                while (true) {
                    val frame = framer.nextFrame() ?: break
                    emit(PcmChunk(frame, rate, id))
                }
            }
            // The tail — under one frame, so ~40 ms (959 samples at 24 kHz, 319 at 8 kHz). Dropping it
            // silently truncated every recording: the session's dsp.flush() → encoder.drain() flush only
            // what they were GIVEN, so nothing downstream can recover audio this loop never emitted.
            // Legacy sent it from the equivalent spot in its own capture loops. Emitted short rather
            // than zero-padded, because padding to the CODEC2 frame size is `Codec2EncoderSession.drain`'s
            // job and the AI path wants no silence appended at all.
            //
            // [PcmFramer.drain] is single-shot, so this cannot re-send audio already emitted above.
            framer.drain()?.let { emit(PcmChunk(it, rate, id)) }
        } finally {
            silence.stop()
            val muted = mutedBy
            if (muted != null) {
                StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "mic v2 ${id.value} muted mid-transmission: $muted")
            } else if (lastRead < 0 && !stopRequested) {
                // The read failed with no key-up behind it: the device went away mid-transmission.
                StardustLog.e(RecorderUtils.PTT_TRACE, "mic v2 ${id.value} lost mid-transmission: read error $lastRead")
            } else {
                StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "mic v2 ${id.value} loop ended: running=$running lastRead=$lastRead")
            }
            running = false
            capture = null
            // Stops and releases the record, then undoes the routing (MicCapture.release).
            mic.release()
            // The mic is only now genuinely released — that is the host's "recording stopped". Skipped
            // when it never started, since that recording was already reported as failed.
            StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "mic v2 ${id.value} released")
            if (started) onCaptureStopped()
            // After STOPPED, so the host sees why this recording ended early.
            if (muted != null) onCaptureMuted(muted)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stopping the record from here is what makes a read already blocked in native code return, so the
     * loop reaches its `finally` (release + route teardown) instead of waiting for audio that may never
     * come. See [MicCapture.stop].
     */
    override suspend fun stop() {
        StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "mic v2 $traceId stop(): record=${capture != null} running=$running")
        stopRequested = true
        running = false
        capture?.stop()
    }

    private companion object {
        /** Must match RemoteMicService's and RemoteCaptureSource's frame size. */
        const val FRAME_MS = 40
    }
}
