package com.commcrete.stardust.audio.v2.application.codec

import com.commcrete.stardust.audio.v2.application.port.AudioCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application layer — the single, idempotent wiring point for codecs.
 *
 * The adapter/host ring constructs the concrete [AudioCodec] instances (e.g. `Codec2Codec`,
 * `WavTokenizerCodec`) — after `AIModuleInitializer` signals the PyTorch models are ready — and
 * passes them in. Bootstrap depends only on the [AudioCodec] port, so the dependency rule holds:
 * the application ring never references an adapter type; concrete codecs are injected inward.
 *
 * Call once at SDK init:
 * ```
 * CodecBootstrap.bootstrap(Codec2Codec(...), WavTokenizerCodec(...))
 * ```
 */
object CodecBootstrap {

    private val done = AtomicBoolean(false)

    /** Register [codecs] exactly once; subsequent calls are no-ops. */
    fun bootstrap(vararg codecs: AudioCodec) {
        if (!done.compareAndSet(false, true)) return
        codecs.forEach(CodecRegistry::register)
    }

    /** Test hook: allow a fresh [bootstrap] in a new test. Not for production use. */
    internal fun resetForTest() = done.set(false)
}
