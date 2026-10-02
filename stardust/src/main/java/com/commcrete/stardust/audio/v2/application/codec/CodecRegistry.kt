package com.commcrete.stardust.audio.v2.application.codec

import com.commcrete.stardust.audio.v2.application.port.AudioCodec
import com.commcrete.stardust.audio.v2.domain.CodecId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Application layer — the single dispatch + synchronization seam (R1).
 *
 * Depends only on the [AudioCodec] port + domain. Every codec self-registers here once (via
 * [CodecBootstrap]); coordinators, the transmit gate, and the receive router resolve codecs ONLY
 * through this object, never by a concrete type or a hardcoded opcode `when`. Adding a codec is
 * therefore a registration, not an edit.
 *
 * [withEncode] is the ONLY place [com.commcrete.stardust.audio.v2.application.port.EncoderSession.encode]
 * / `drain` may run, and [withDecode] the only place
 * [com.commcrete.stardust.audio.v2.application.port.DecoderSession.decode] may. Each holds a per-codec
 * [Mutex] of its own: decodes serialize against decodes (the WavTokenizer decoder is one shared
 * instance whose continuity every session restores and snapshots) and encodes against encodes, but an
 * encode never waits for a decode. Encoder and decoder are separate native modules with no shared
 * state, so one lock for both bought nothing but queuing — a receive-decode stuck behind a send-encode
 * plays out as a gap. Once open-decision #1 (full state externalization +
 * [com.commcrete.stardust.audio.v2.application.port.NativeModulePool]) is resolved, these locks can
 * relax to a no-op for codecs that are provably instance-isolated.
 */
object CodecRegistry {

    private val idTable = ConcurrentHashMap<CodecId, AudioCodec>()
    private val opcodeTable = ConcurrentHashMap<Int, AudioCodec>()
    private val encodeMutexes = ConcurrentHashMap<CodecId, Mutex>()
    private val decodeMutexes = ConcurrentHashMap<CodecId, Mutex>()

    /** Register a codec. Idempotent per [CodecId]; rejects a second codec claiming an opcode already in use. */
    fun register(codec: AudioCodec) {
        val opcodeOwner = opcodeTable[codec.sendOpcode]
        require(opcodeOwner == null || opcodeOwner.codecId == codec.codecId) {
            "opcode 0x${codec.sendOpcode.toString(16)} already registered to '${opcodeOwner?.codecId?.value}'"
        }
        idTable[codec.codecId] = codec
        opcodeTable[codec.sendOpcode] = codec
        encodeMutexes.putIfAbsent(codec.codecId, Mutex())
        decodeMutexes.putIfAbsent(codec.codecId, Mutex())
    }

    /** Send-path lookup. Throws if [id] was never registered (a wiring bug, not runtime data). */
    fun byCodecId(id: CodecId): AudioCodec =
        idTable[id] ?: error("no codec registered for CodecId('${id.value}')")

    /** Receive-path dispatch. Returns null for an unknown opcode (untrusted wire data → caller drops the packet). */
    fun byOpcode(opcode: Int): AudioCodec? = opcodeTable[opcode]

    /** All registered codecs (e.g. for diagnostics / a settings picker). */
    val registered: Collection<AudioCodec> get() = idTable.values

    /** Run [block] under [id]'s encode mutex — the single serialized entry to native encode. */
    suspend fun <T> withEncode(id: CodecId, block: suspend () -> T): T = locked(encodeMutexes, id, block)

    /** Run [block] under [id]'s decode mutex — the single serialized entry to native decode. */
    suspend fun <T> withDecode(id: CodecId, block: suspend () -> T): T = locked(decodeMutexes, id, block)

    private suspend fun <T> locked(table: Map<CodecId, Mutex>, id: CodecId, block: suspend () -> T): T {
        val mutex = table[id] ?: error("no codec registered for CodecId('${id.value}')")
        return mutex.withLock { block() }
    }
}
