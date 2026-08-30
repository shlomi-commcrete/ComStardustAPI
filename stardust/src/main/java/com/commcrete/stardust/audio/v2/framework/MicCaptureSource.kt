package com.commcrete.stardust.audio.v2.framework

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.util.audio.filters.configs.AudioCaptureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Framework ring — the single physical microphone behind the [CaptureSource] port.
 *
 * Ported from `AudioRecorderCodec2`: uses [AudioCaptureConfig] to pick a device-native rate (USB
 * preferred), then emits ~40 ms PcmChunks at that native rate. Gain/DSP/resample happen downstream
 * in the per-session [ResampleGainDsp], so this class stays a pure capture edge.
 *
 * [requestedRateHz] is the OWNING CODEC's native rate, not a constant: the AI codec needs 24 kHz
 * capture (legacy `AudioRecorderAI.RECORDER_SAMPLE_RATE`) and CODEC2 needs 8 kHz. Requesting 8 kHz for
 * the AI path would band-limit the audio to 4 kHz and then upsample it, which makes the WavTokenizer
 * encoder emit meaningless tokens. [audioSource] likewise differs per codec (`getCodecAudioSource` vs
 * `getAIAudioSource`).
 *
 * One instance per recording; [stop] flips the loop off (mic released on key-up), and collector
 * cancellation also tears the [AudioRecord] down via the `finally`.
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
    /** Invoked instead of [onCaptureStarted] when the microphone could not be opened. */
    private val onCaptureFailed: () -> Unit = {},
) : CaptureSource {

    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    override fun start(id: RecordingId): Flow<PcmChunk> = flow {
        val plan = AudioCaptureConfig.buildCapturePlan(
            context = context,
            requestedRate = requestedRateHz,
            defaultAudioSource = audioSource,
        )
        val rate = plan.captureRate
        val frameSamples = ((rate * FRAME_MS) / 1000).coerceAtLeast(160)
        val minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
        val bufferBytes = maxOf(minBuffer * 2, frameSamples * 2)

        val recorder = AudioRecord(plan.audioSource, rate, CHANNEL, ENCODING, bufferBytes)
        AudioCaptureConfig.applyInputRoute(context, recorder, plan.preferredInputDevice)
        // Judged by the real device state rather than "startRecording() returned": a mic held by another
        // app or an in-progress call surfaces as an uninitialized record or a non-RECORDING state, not as
        // an exception. Announcing a recording that is not actually capturing is what this guards.
        val capturing = recorder.state == AudioRecord.STATE_INITIALIZED &&
            runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        running = capturing
        if (capturing) onCaptureStarted() else onCaptureFailed()

        val read = ShortArray(frameSamples)
        val pending = ArrayList<Short>(frameSamples * 2)
        try {
            while (running) {
                val n = recorder.read(read, 0, read.size)
                if (n > 0) {
                    for (i in 0 until n) pending.add(read[i])
                    while (pending.size >= frameSamples) {
                        val frame = ShortArray(frameSamples) { pending[it] }
                        repeat(frameSamples) { pending.removeAt(0) }
                        emit(PcmChunk(frame, rate, id))
                    }
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            // Balances applyInputRoute above (setCommunicationDevice / startBluetoothSco). Legacy did
            // this in AudioRecorderCodec2.stopRecordingNow's finally; without it the phone stays pinned
            // to the PTT communication device and later capture/playback is silent or misrouted.
            runCatching { AudioCaptureConfig.clearInputRoute(context) }
            // The mic is only now genuinely released — that is the host's "recording stopped". Skipped
            // when it never opened, since that recording was already reported as failed.
            if (capturing) onCaptureStopped()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stop() {
        running = false
    }

    private companion object {
        const val FRAME_MS = 40
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
