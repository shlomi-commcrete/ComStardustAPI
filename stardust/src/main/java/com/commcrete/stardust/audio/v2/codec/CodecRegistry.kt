package com.commcrete.stardust.audio.v2.codec

import com.commcrete.stardust.stardust.StardustPackageUtils.StardustOpCode
import com.commcrete.stardust.util.audio.RecorderUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-singleton registry of every PTT codec the SDK supports.
 *
 * **Owns one [Mutex] per codec.** The mutex serialises every interaction
 * with that codec's underlying singleton (encoder + decoder model) so:
 *
 *  - the **send-encode** loop in [com.commcrete.stardust.audio.v2.session.RecordingSession],
 *  - the **send-self-decode** in [com.commcrete.stardust.audio.v2.mirror.LocalMirror]
 *    (used to build the local WAV mirror), and
 *  - the **receive-decode** in [com.commcrete.stardust.audio.v2.session.PttReceiveCoordinator]
 *
 * can never interleave on the same codec — multiplexing happens via
 * per-stream save/restore inside [EncoderSession] / [DecoderSession]
 * impls under this single chokepoint.
 *
 * The lock is **per codec**, not global, so concurrent
 * `AI` send + `CODEC2` receive (or vice versa) never block each other.
 *
 * # Usage contract
 *
 *  - [register] is called once at SDK init from [CodecBootstrap].
 *    Idempotent; second registration of the same [AudioCodec.id] is
 *    silently ignored.
 *  - [withCodec] is the ONLY allowed entry point for invoking
 *    [EncoderSession.encode] / [DecoderSession.decode]. Treat the
 *    `block` body as the inside of a critical section.
 *  - [get] / [forOpCode] are lookups only; do NOT call encoder/decoder
 *    methods directly on the returned [AudioCodec] — go through
 *    [withCodec].
 */
object CodecRegistry {

    private val codecsById: MutableMap<RecorderUtils.CODE_TYPE, AudioCodec> = mutableMapOf()
    private val codecsByOpCode: MutableMap<StardustOpCode, AudioCodec> = mutableMapOf()
    private val mutexes: MutableMap<RecorderUtils.CODE_TYPE, Mutex> = mutableMapOf()

    /**
     * Register [codec]. Idempotent. Safe to call from any thread but
     * intended to be called once per codec at app/SDK init.
     */
    @Synchronized
    fun register(codec: AudioCodec) {
        codecsById.putIfAbsent(codec.id, codec)
        codec.opCodesReceive.forEach { codecsByOpCode.putIfAbsent(it, codec) }
        mutexes.putIfAbsent(codec.id, Mutex())
    }

    /**
     * Whether [codec] has been registered. Useful for the bootstrap
     * gate so consumers don't double-register if `initModules` is
     * called twice.
     */
    @Synchronized
    fun isRegistered(codec: RecorderUtils.CODE_TYPE): Boolean = codecsById.containsKey(codec)

    /**
     * Look up a registered codec by id. Throws if the bootstrap hasn't
     * registered it yet — that's a programmer error worth surfacing
     * loudly.
     */
    fun get(id: RecorderUtils.CODE_TYPE): AudioCodec =
        codecsById[id] ?: error("Codec $id not registered — call CodecBootstrap.bootstrap(ctx) at init")

    /**
     * Look up the codec that owns [opCode] on the receive side, or
     * `null` if no registered codec claims it. Returning `null` is
     * the right behavior for unknown opcodes — the receive
     * coordinator should drop the packet rather than crash.
     */
    fun forOpCode(opCode: StardustOpCode): AudioCodec? = codecsByOpCode[opCode]

    /**
     * Run [block] while holding [id]'s mutex. The lock is **suspending
     * fair** (kotlinx.coroutines [Mutex]), so callers in a coroutine
     * can `await()` without burning a thread.
     *
     * **This is the single source of truth for codec serialisation.**
     * If you find yourself calling `EncoderSession.encode()` or
     * `DecoderSession.decode()` outside of `withCodec(...)`, fix the
     * call site — the architecture's safety derives entirely from this
     * being the only entry point.
     */
    suspend fun <T> withCodec(id: RecorderUtils.CODE_TYPE, block: suspend (AudioCodec) -> T): T =
        mutexes.getValue(id).withLock { block(get(id)) }

    // ── Test/diagnostics only ────────────────────────────────────────

    /**
     * Drop all registrations. **Test-only.** Production code should
     * never call this — codecs are meant to live for the entire
     * process lifetime.
     */
    @Synchronized
    internal fun resetForTest() {
        codecsById.clear()
        codecsByOpCode.clear()
        mutexes.clear()
    }
}

