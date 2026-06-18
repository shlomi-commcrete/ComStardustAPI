package com.commcrete.stardust.audio.v2.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.commcrete.stardust.util.audio.AudioCaptureConfig
import com.commcrete.stardust.util.audio.AudioRecordingKeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone [CaptureSource] backed by Android [AudioRecord].
 *
 * Replaces the `AudioRecord` lifecycle that lives in two places today:
 *  - [com.commcrete.stardust.ai.codec.AudioRecorderAI] (24 kHz, 500 ms
 *    chunks for the AI path),
 *  - [com.commcrete.stardust.util.audio.AudioRecorderCodec2] (8 kHz, 40 ms
 *    chunks for the CODEC2 path).
 *
 * Same class drives both — only [requestedSampleRateHz] and
 * `chunkDurationMs` (passed to [start]) differ. The constructor takes
 * an [audioSourceProvider] lambda so the live SDK can read user-prefs
 * (`SharedPreferencesUtil.getAIAudioSource` /
 * `getCodecAudioSource`) without this class depending on prefs
 * directly — easier to test.
 *
 * # Behavior preserved from the legacy recorders
 *
 *  - [AudioCaptureConfig.buildCapturePlan] picks the actual capture
 *    rate (may differ from [requestedSampleRateHz] if the device
 *    doesn't support it) and the audio source enum.
 *  - [AudioCaptureConfig.applyInputRoute] routes
 *    `AudioRecord.setPreferredDevice` to the user's pick.
 *  - [AudioRecordingKeepAlive] is acquired before
 *    `audioRecord.startRecording()` and released in the Flow's
 *    `awaitClose` cleanup. The session also tries to acquire it; that
 *    class is reference-counted so duplicate acquires are harmless.
 *  - Reads happen in ~20 ms slices (`captureRate / 50`) into a small
 *    `shortBuffer` and are then accumulated into the
 *    `captureSamplesPerChunk` output buffer — matches
 *    `AudioRecorderAI.recordLoop`. Larger reads were observed to
 *    increase pops on some devices.
 *  - On cancellation/stop the in-progress chunk (if any) is emitted
 *    once with [CaptureChunk.isPartial] = true so the encoder can
 *    flush.
 *
 * # NOT preserved (deliberately)
 *
 *  - The `processSamples` tanh soft-clip / `targetGain` `coerceIn`
 *    inline make-up gain from the legacy recorders is **not** applied
 *    here. v2 routes gain through [com.commcrete.stardust.audio.v2.dsp.PttAudioProcessorV2]'s
 *    `MakeupGainConfig` stage so every codec can choose its policy.
 *  - The legacy `StreamingAudioStatsLogger` and `DebugRawWavWriter`
 *    diagnostic side-channels are not wired in yet. TODO: re-attach
 *    in Phase 2 if they prove useful in production — they're
 *    independent of correctness.
 */
@SuppressLint("MissingPermission")
class MicCaptureSource(
    private val context: Context,
    private val requestedSampleRateHz: Int,
    /**
     * `MediaRecorder.AudioSource` constant. Lambda so the consumer can
     * pick a different source per codec (the legacy code uses
     * `SharedPreferencesUtil.getAIAudioSource` for AI and
     * `getCodecAudioSource` for CODEC2).
     */
    private val audioSourceProvider: () -> Int,
) : CaptureSource {

    override val nativeSampleRateHz: Int
        get() = resolvedCaptureRate

    override val deviceTypeHint: Int?
        get() = resolvedDeviceType

    @Volatile private var resolvedCaptureRate: Int = requestedSampleRateHz
    @Volatile private var resolvedDeviceType: Int? = null

    @Volatile private var audioRecord: AudioRecord? = null
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    override fun start(chunkDurationMs: Int): Flow<CaptureChunk> {
        check(started.compareAndSet(false, true)) {
            "MicCaptureSource may only be started once — construct a new instance per recording."
        }
        return callbackFlow {
            AudioRecordingKeepAlive.acquire(context)

            val plan = AudioCaptureConfig.buildCapturePlan(
                context = context,
                requestedRate = requestedSampleRateHz,
                defaultAudioSource = audioSourceProvider(),
            )
            resolvedCaptureRate = plan.captureRate

            val minBuffer = AudioRecord.getMinBufferSize(
                plan.captureRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBuffer > 0) {
                "AudioRecord.getMinBufferSize failed for rate=${plan.captureRate} — unsupported"
            }
            // Buffer at least ~250 ms at capture rate to absorb USB jitter
            // (matches AudioRecorderAI.recordLoop).
            val recordBufferBytes = maxOf(minBuffer * 2, plan.captureRate * 2 / 4)

            val record = AudioRecord(
                plan.audioSource,
                plan.captureRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                close(IllegalStateException(
                    "AudioRecord init failed (rate=${plan.captureRate}, source=${plan.audioSource})"
                ))
                return@callbackFlow
            }
            audioRecord = record

            AudioCaptureConfig.applyInputRoute(context, record, plan.preferredInputDevice)
            resolvedDeviceType = record.routedDevice?.type ?: plan.preferredInputDevice?.type

            val captureSamplesPerChunk =
                (plan.captureRate.toLong() * chunkDurationMs / 1000L).toInt()
            // Read size: ~20 ms at capture rate. Larger reads caused
            // audible pops on some devices in the legacy code.
            val readSamples = (plan.captureRate / 50).coerceAtLeast(16)
            val readBuffer = ShortArray(readSamples)
            val chunkBuffer = ShortArray(captureSamplesPerChunk)
            var chunkSampleIndex = 0
            var chunkIndex = 0

            try {
                record.startRecording()
            } catch (t: Throwable) {
                Timber.e(t, "MicCaptureSource: startRecording failed")
                close(t)
                return@callbackFlow
            }

            // Drive the read loop in this coroutine. callbackFlow buffers
            // emissions in a Channel so the producer can block on read()
            // without back-pressuring the consumer.
            while (!stopped.get() && currentCoroutineContext().isActive) {
                val n = try {
                    record.read(readBuffer, 0, readBuffer.size)
                } catch (t: Throwable) {
                    Timber.w(t, "MicCaptureSource: read failed")
                    -1
                }
                if (n <= 0) {
                    if (stopped.get()) break
                    continue
                }
                var consumed = 0
                while (consumed < n) {
                    val remaining = captureSamplesPerChunk - chunkSampleIndex
                    val toCopy = minOf(remaining, n - consumed)
                    System.arraycopy(readBuffer, consumed, chunkBuffer, chunkSampleIndex, toCopy)
                    chunkSampleIndex += toCopy
                    consumed += toCopy
                    if (chunkSampleIndex == captureSamplesPerChunk) {
                        // Copy out so consumer sees an immutable snapshot
                        // — chunkBuffer is reused for the next chunk.
                        val send = trySend(CaptureChunk(
                            pcm = chunkBuffer.copyOf(),
                            index = chunkIndex++,
                            isPartial = false,
                        ))
                        if (send.isFailure) {
                            Timber.w("MicCaptureSource: downstream closed, ending capture")
                            stopped.set(true)
                            break
                        }
                        chunkSampleIndex = 0
                    }
                }
            }

            // Emit any in-progress partial chunk so the encoder can flush.
            if (chunkSampleIndex > 0) {
                trySend(CaptureChunk(
                    pcm = chunkBuffer.copyOf(chunkSampleIndex),
                    index = chunkIndex,
                    isPartial = true,
                ))
            }

            awaitClose {
                // Awaitclose handler — runs on consumer cancel or producer
                // close(). All teardown lives here so both paths converge.
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching { AudioCaptureConfig.clearInputRoute(context) }
                AudioRecordingKeepAlive.release()
                audioRecord = null
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun stop() {
        if (stopped.compareAndSet(false, true)) {
            // Best-effort unblock of any in-progress read(). callbackFlow's
            // awaitClose handler does the actual teardown — calling stop()
            // here just makes the read() return so the loop exits sooner.
            runCatching { audioRecord?.stop() }
        }
    }
}



