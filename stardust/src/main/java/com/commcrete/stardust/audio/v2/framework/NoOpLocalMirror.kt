package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.domain.PcmChunk

/**
 * Framework ring — the default "no local WAV mirror" [LocalMirror]. Swap for a WavLocalMirror when
 * `DataManager.getSavePTTFilesRequired()` is on.
 */
object NoOpLocalMirror : LocalMirror {
    /** Nothing is saved, so the send session skips the per-frame self-decode entirely. */
    override val isActive: Boolean get() = false
    override suspend fun accept(pcm: PcmChunk) = Unit
    override suspend fun finalizeMirror() = Unit
}
