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

/** Optional self-decoded local WAV mirror for one recording. Save directory is an adapter ctor arg. */
interface LocalMirror {
    suspend fun accept(pcm: PcmChunk)
    suspend fun finalizeMirror()
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
