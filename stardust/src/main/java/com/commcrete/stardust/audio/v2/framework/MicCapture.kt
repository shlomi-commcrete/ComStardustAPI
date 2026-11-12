package com.commcrete.stardust.audio.v2.framework

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.commcrete.stardust.util.audio.filters.configs.AudioCaptureConfig

/**
 * Framework ring — one physical microphone for one recording: open, route, read, stop, release.
 *
 * Split out of [MicCaptureSource] so the same open/route/teardown runs wherever the microphone is
 * opened. It knows nothing about PTT: no beep, no framing, no lifecycle events — those stay with the
 * caller.
 *
 * Threading: [open], [read] and [release] run on one capture thread; [stop] may be called from any
 * thread, at any time, including before [open] has finished.
 */
internal class MicCapture(
    private val context: Context,
    private val plan: AudioCaptureConfig.CapturePlan,
    frameSamples: Int,
) {
    val rate: Int get() = plan.captureRate

    private val bufferBytes = maxOf(AudioRecord.getMinBufferSize(plan.captureRate, CHANNEL, ENCODING) * 2, frameSamples * 2)

    /**
     * The live record, published so [stop] can reach it from another thread. Guarded by [recordLock] for
     * its stop/release transitions only — never held across a read, which is where the time goes.
     */
    @Volatile private var activeRecord: AudioRecord? = null
    private val recordLock = Any()

    /** Set by [stop] even before the record exists, so a key-up that beats the mic open still wins. */
    @Volatile var stopRequested = false
        private set

    /**
     * Opens and starts the microphone. True only when it is really capturing.
     *
     * Judged by the real device state rather than "startRecording() returned": a mic held by another app
     * or an in-progress call surfaces as an uninitialized record or a non-RECORDING state, not as an
     * exception. Announcing a recording that is not actually capturing is what this guards.
     */
    @SuppressLint("MissingPermission")
    fun open(): Boolean {
        val recorder = AudioRecord(plan.audioSource, plan.captureRate, CHANNEL, ENCODING, bufferBytes)
        // Published before startRecording(), so a stop() racing the mic open can already reach it.
        activeRecord = recorder
        AudioCaptureConfig.applyInputRoute(context, recorder, plan.preferredInputDevice)
        return recorder.state == AudioRecord.STATE_INITIALIZED &&
            runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    /**
     * Blocking read into [into]. A negative return is an error code (ERROR_DEAD_OBJECT when the device
     * goes away, ERROR_INVALID_OPERATION on a stopped record), returned immediately and forever after.
     */
    fun read(into: ShortArray): Int = activeRecord?.read(into, 0, into.size) ?: AudioRecord.ERROR_INVALID_OPERATION

    /** The record's audio session, to match it in AudioManager's recording configurations. */
    val audioSessionId: Int? get() = activeRecord?.audioSessionId

    /**
     * Stopping the record from here is what makes a read already blocked in native code return, so the
     * caller's loop reaches its `finally` (release + route teardown) instead of waiting for audio that may
     * never come. [release] stops and releases again under the same lock; a second stop on an
     * already-stopped record is a no-op, and nulling [activeRecord] there is what stops a late call here
     * from touching a released one.
     */
    fun stop() {
        stopRequested = true
        synchronized(recordLock) {
            runCatching { activeRecord?.stop() }
        }
    }

    /** Releases the record and undoes the routing. Idempotent. */
    fun release() {
        synchronized(recordLock) {
            val recorder = activeRecord
            activeRecord = null
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
        // Balances applyInputRoute in open (setCommunicationDevice / startBluetoothSco). Legacy did this in
        // AudioRecorderCodec2.stopRecordingNow's finally; without it the phone stays pinned to the PTT
        // communication device and later capture/playback is silent or misrouted.
        runCatching { AudioCaptureConfig.clearInputRoute(context) }
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
