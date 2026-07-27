package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.application.port.LocalMirror
import com.commcrete.stardust.audio.v2.domain.PcmChunk

/**
 * Framework ring — the default "no local WAV mirror" [LocalMirror]. Swap for a WavLocalMirror when
 * `DataManager.getSavePTTFilesRequired()` is on.
 */
object NoOpLocalMirror : LocalMirror {
    override suspend fun accept(pcm: PcmChunk) = Unit
    override suspend fun finalizeMirror() = Unit
}
