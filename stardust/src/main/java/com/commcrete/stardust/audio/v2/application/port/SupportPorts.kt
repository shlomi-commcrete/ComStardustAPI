package com.commcrete.stardust.audio.v2.application.port

import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TerminalReason

/**
 * Application layer — PORTS. Supporting outward dependencies kept narrow and event-shaped so their
 * latency can never stall the capture/encode/transmit hot path.
 */

/**
 * Optional self-decoded local WAV mirror for one recording. Save directory is an adapter ctor arg.
 *
 * [accept] is fed the DECODED PCM of each transmitted frame (see
 * [com.commcrete.stardust.audio.v2.application.send.RecordingSession]), so the saved file is what the
 * receiver reconstructs, not the encoder's input.
 */
interface LocalMirror {

    /**
     * Whether this mirror actually persists anything. `false` for the no-op mirror, which lets the
     * send session skip creating a decoder session and running a decode pass per frame — for the AI
     * codec that is a full model forward per 500 ms of audio, so it must not run when nothing saves it.
     */
    val isActive: Boolean get() = true

    suspend fun accept(pcm: PcmChunk)
    suspend fun finalizeMirror()
}

/**
 * Announces that a recording hit the max-PTT watchdog, so the host can react the way it did on the
 * legacy path (`PttSendManager.enforceMaxPttTimeout` / `AudioRecorderCodec2.onPipelinePacketSent`):
 * the API callback, the end-of-PTT beep, and the `PttInterface` hook.
 *
 * Purely an announcement — stopping the capture is [com.commcrete.stardust.audio.v2.application.send.RecordingSession]'s
 * own job and does not depend on this. Called at most once per recording, from the capture path, so
 * the implementation must not block.
 */
interface MaxPttTimeoutNotifier {
    fun onMaxTimeoutReached(id: RecordingId)
}

/**
 * Persistence of PTT history/metadata (Room lives in the framework ring — no Room entity crosses into
 * this ring). All calls are best-effort and off the hot audio path.
 */
interface MessageStore {
    suspend fun onRecordingStarted(id: RecordingId, codecId: CodecId, peer: StreamKey)
    suspend fun onRecordingFinalized(id: RecordingId, frames: Int, reason: TerminalReason)
    suspend fun onStreamReceived(key: StreamKey, codecId: CodecId, frames: Int)
}

/**
 * Bounded checkout pool for a heavy stateful native runtime (e.g. the WavTokenizer PyTorch module).
 * Size ≥ 2 lets recording N drain on one module while recording N+1 encodes on another. [checkout]
 * suspends when exhausted, which bounds native memory — the "slowdown is memory, not storage" hazard.
 */
interface NativeModulePool<T> {
    suspend fun checkout(): T
    fun giveBack(module: T)
}

/** Injectable wall-clock source for the key-up and per-head transmit watchdogs (keeps sessions testable). */
interface Clock {
    fun nowMs(): Long
}

/**
 * Holds a partial wake-lock for a recording's lifetime so screen-off can't suspend the capture/encode
 * coroutines. Refcounted in the impl — every [acquire] must be balanced by exactly one [release].
 */
interface KeepAlive {
    fun acquire()
    fun release()
}
