package com.commcrete.stardust.audio.v2.mirror

import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.audio.v2.codec.DecoderSession
import java.io.File

/**
 * [LocalMirror] that does nothing.
 *
 * Used when local WAV mirroring is disabled at the session level so
 * the coordinator can write `mirror.onEncoded(...)` unconditionally
 * without a null check on every encode iteration.
 */
object NoOpLocalMirror : LocalMirror {
    override suspend fun onEncoded(
        codec: AudioCodec,
        decoder: DecoderSession,
        payload: ByteArray,
    ) {
        // No-op.
    }

    override suspend fun finalize(codec: AudioCodec, targetFile: File): File? = null

    override fun close() {
        // No-op.
    }
}

