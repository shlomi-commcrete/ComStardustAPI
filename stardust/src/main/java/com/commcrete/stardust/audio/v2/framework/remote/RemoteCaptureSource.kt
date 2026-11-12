package com.commcrete.stardust.audio.v2.framework.remote

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.commcrete.stardust.PttRecordingError
import com.commcrete.stardust.R
import com.commcrete.stardust.audio.v2.application.port.CaptureSource
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.framework.MicCaptureSource
import com.commcrete.stardust.audio.v2.framework.PcmFramer
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.StardustLog
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.audio.SoundChannel
import com.commcrete.stardust.util.audio.SoundPlayer
import com.commcrete.stardust.util.audio.filters.configs.AudioCaptureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.io.InputStream

/**
 * Framework ring — the [CaptureSource] for an armed [RemoteMic]: the microphone is opened in the consumer's
 * own process by [RemoteMicService] and its PCM arrives through a pipe.
 *
 * Mirrors [MicCaptureSource] on everything the host sees: the start beep plays here, in the host process,
 * before the microphone opens; the capture plan (rate, audio source, input device) is built here from this
 * process's settings and only executed remotely; STARTED / STOPPED / failure / mute are reported through the
 * same four callbacks, from the capture thread.
 */
internal class RemoteCaptureSource(
    private val context: Context,
    private val remote: IRemoteMic,
    private val requestedRateHz: Int,
    private val audioSource: Int,
    private val onCaptureStarted: () -> Unit = {},
    private val onCaptureStopped: () -> Unit = {},
    private val onCaptureFailed: (PttRecordingError) -> Unit = {},
    private val onCaptureMuted: (PttRecordingError) -> Unit = {},
) : CaptureSource {

    @Volatile private var stopRequested = false

    /** Published so [stop] can unblock a read already parked on the pipe. */
    @Volatile private var input: InputStream? = null

    /** Linked to death on the remote side: if this process dies mid-capture, the remote microphone stops. */
    private val clientToken = Binder()

    override fun start(id: RecordingId): Flow<PcmChunk> = flow {
        // As MicCaptureSource: "you may speak", waited out before the microphone opens so it is never captured.
        if (!stopRequested) {
            SoundPlayer.playAndAwait(context, R.raw.ptt_started_beep, channel = SoundChannel.SYSTEM)
        }

        val plan = AudioCaptureConfig.buildCapturePlan(context, requestedRateHz, audioSource)
        val rate = plan.captureRate
        val frameSamples = ((rate * FRAME_MS) / 1000).coerceAtLeast(160)

        val pipe: ParcelFileDescriptor? = try {
            remote.open(rate, plan.audioSource, plan.preferredInputDevice?.id ?: -1, clientToken)
        } catch (e: Exception) {
            // DeadObjectException: the remote process died since arming. RemoteMic sees the binding drop.
            StardustLog.w(LogType.PTT, RecorderUtils.PTT_TRACE, "remote mic ${id.value} open failed: ${e.javaClass.simpleName}")
            null
        }
        if (pipe == null) {
            val failure = runCatching { remote.lastFailure() }.getOrDefault(RemoteMicFailure.MIC_UNAVAILABLE)
            StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "remote mic ${id.value} not opened: $failure")
            onCaptureFailed(RemoteMicFailure.toError(failure))
            return@flow
        }

        val stream = ParcelFileDescriptor.AutoCloseInputStream(pipe)
        input = stream
        // A key-up that landed during the open: close at once, after reporting the start it did make.
        if (stopRequested) runCatching { remote.close() }
        StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "remote mic ${id.value} opened at $rate Hz")
        onCaptureStarted()

        val bytes = ByteArray(frameSamples * 2)
        val samples = ShortArray(frameSamples + 1)
        val decoder = LePcmDecoder()
        val framer = PcmFramer(frameSamples)
        try {
            while (true) {
                val n = try {
                    stream.read(bytes)
                } catch (e: IOException) {
                    -1 // the pipe was closed under the read by stop(): end of capture
                }
                if (n < 0) break
                framer.offer(samples, decoder.decode(bytes, n, samples))
                while (true) {
                    val frame = framer.nextFrame() ?: break
                    emit(PcmChunk(frame, rate, id))
                }
            }
            // The tail under one frame, as MicCaptureSource sends it.
            framer.drain()?.let { emit(PcmChunk(it, rate, id)) }
        } finally {
            input = null
            runCatching { stream.close() }
            // The pipe ending with no key-up means the remote stopped by itself: a mute, the device going
            // away, or its process dying.
            val failure = if (stopRequested) RemoteMicFailure.NONE
            else runCatching { remote.lastFailure() }.getOrDefault(RemoteMicFailure.MIC_UNAVAILABLE)
            StardustLog.d(LogType.PTT, RecorderUtils.PTT_TRACE, "remote mic ${id.value} released, failure=$failure")
            onCaptureStopped()
            if (RemoteMicFailure.isMute(failure)) onCaptureMuted(RemoteMicFailure.toError(failure))
        }
    }.flowOn(Dispatchers.IO)

    /** Ends the capture: the remote stops its microphone, and the pipe reaches end of stream. */
    override suspend fun stop() {
        stopRequested = true
        runCatching { remote.close() }
            // The remote is gone; closing our end is what returns a read parked on the pipe.
            .onFailure { runCatching { input?.close() } }
    }

    private companion object {
        /** Must match RemoteMicService's frame size and MicCaptureSource's. */
        const val FRAME_MS = 40
    }
}
