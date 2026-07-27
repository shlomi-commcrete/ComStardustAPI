package com.commcrete.stardust.audio.v2.framework

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.util.SharedPreferencesUtil
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
 * One instance per recording; [stop] flips the loop off (mic released on key-up), and collector
 * cancellation also tears the [AudioRecord] down via the `finally`.
 */
class MicCaptureSource(private val context: Context) : CaptureSource {

    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    override fun start(id: RecordingId): Flow<PcmChunk> = flow {
        val plan = AudioCaptureConfig.buildCapturePlan(
            context = context,
            requestedRate = REQUESTED_RATE,
            defaultAudioSource = SharedPreferencesUtil.getCodecAudioSource(),
        )
        val rate = plan.captureRate
        val frameSamples = ((rate * FRAME_MS) / 1000).coerceAtLeast(160)
        val minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
        val bufferBytes = maxOf(minBuffer * 2, frameSamples * 2)

        val recorder = AudioRecord(plan.audioSource, rate, CHANNEL, ENCODING, bufferBytes)
        AudioCaptureConfig.applyInputRoute(context, recorder, plan.preferredInputDevice)
        recorder.startRecording()
        running = true

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
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stop() {
        running = false
    }

    private companion object {
        const val REQUESTED_RATE = 8_000
        const val FRAME_MS = 40
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
