package com.commcrete.stardust.audio.v2.framework

import android.content.Context
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.mapper.StardustPackageApiMapper
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.DataManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.commcrete.stardust.room.StardustStorage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Framework ring — the v2 receive-side app-integration that legacy `PlayerUtils` / `PttReceiveManager`
 * did inline: persist a PTT message row, fire the [com.commcrete.stardust.StardustAPICallbacks]
 * (startedReceivingPTT / receivePTT), and write the decoded audio to a WAV for replay. Without this,
 * received PTTs play live but never appear in history and never notify the app.
 *
 * Two producers feed it — the router (per received packet, has the [StardustPackage]) and the receive
 * stream (per decoded [PcmChunk]) — plus a stream-end signal. All events run through ONE actor
 * coroutine so ordering is deterministic: the first packet's row+callback always precedes later
 * callbacks, and the row is settled after the last decoded frame.
 *
 * The WAV is written through incrementally by [PttWavWriter] (a partial PTT survives a process death;
 * see that class), on a thread of its own — NOT the decoder thread that produced the chunk, and not
 * the actor thread either. Disk latency must not sit between a decoded chunk and the `receivePTT`
 * callback that hands it to the host. Appends are therefore fire-and-forget onto [writes], which is
 * FIFO and single-consumer, so chunks reach the file in decode order; only end-of-stream waits, so
 * that `stopReceivingPTT` still means "the file is complete".
 */
class PttReceiveStore(private val context: Context) {

    private val events = Channel<Ev>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Actor-owned, except that [shutdown] drains it from the caller's thread. */
    private val contexts = ConcurrentHashMap<String, StreamCtx>()

    private val writes = Channel<WriteCmd>(Channel.UNLIMITED)
    private val writerDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, WRITER_THREAD_NAME).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val writerScope = CoroutineScope(SupervisorJob() + writerDispatcher)
    private val writerJob = writerScope.launch { for (cmd in writes) runWrite(cmd) }

    init {
        scope.launch { for (ev in events) handle(ev) }
    }

    /** A received packet arrived for [key] (router; has the raw [pkg]). Drives first-packet setup. */
    fun onPacket(pkg: StardustPackage, key: StreamKey, encoderType: EncoderType) {
        events.trySend(Packet(pkg, key, encoderType))
    }

    /** A decoded PCM frame is available for [key] (receive stream). */
    fun onDecodedPcm(key: StreamKey, pcm: PcmChunk) {
        events.trySend(Pcm(key, pcm.samples, pcm.sampleRateHz))
    }

    /** The stream for [key] ended (terminal frame or idle eviction). */
    fun onEnd(key: StreamKey) {
        events.trySend(End(key))
    }

    /**
     * Stop accepting work and let both loops drain. Called by [PttV2Wiring.shutdown] — plugin unload,
     * logout — because this instance owns a thread and an open file per live stream, and a rebuilt
     * pipeline would otherwise leave both behind.
     *
     * Every still-open WAV is closed, so what was received stays playable. Their rows are left
     * RECEIVING: settling them means a suspending database write, and teardown cannot wait for one.
     * The startup sweep is what finishes them.
     */
    fun shutdown() {
        events.close()
        contexts.values.forEach { writes.trySend(Close(it.writer, CompletableDeferred())) }
        contexts.clear()
        writes.close()
        writerJob.invokeOnCompletion { writerDispatcher.close() }
    }

    private suspend fun handle(ev: Ev) {
        when (ev) {
            is Packet -> onPacketEvent(ev)
            is Pcm -> contexts[ev.key.value]?.let { ctx ->
                writes.trySend(Append(ctx.writer, ev.samples, ev.rate))
                // The first decoded chunk is represented by startedReceivingPTT; deliver the rest as
                // DECODED PCM16 bytes via receivePTT (matches the legacy codec receive behavior).
                if (ctx.firstPcmSeen) {
                    runCatching { DataManager.getCallbacks()?.receivePTT(ctx.apiPkg, shortsToLePcm16(ev.samples)) }
                } else {
                    ctx.firstPcmSeen = true
                }
            }
            is End -> onEndEvent(ev)
        }
    }

    /** The only code that touches a [PttWavWriter]; runs on [WRITER_THREAD_NAME] and nowhere else. */
    private fun runWrite(cmd: WriteCmd) {
        when (cmd) {
            is Append -> cmd.writer.append(cmd.samples, cmd.rate)
            is Close -> {
                cmd.writer.close()
                cmd.ack.complete(cmd.writer.hasAudio)
            }
        }
    }

    private suspend fun onPacketEvent(ev: Packet) {
        // Only the first packet of a stream sets up the row + startedReceivingPTT; per-chunk
        // receivePTT is driven by decoded PCM (see the Pcm branch), not by raw packets.
        if (contexts.containsKey(ev.key.value)) return
        val apiPkg = StardustPackageApiMapper.toStardustAPIPackage(ev.pkg) ?: return
        val ts = System.currentTimeMillis()
        val file = File(StardustStorage.chatDir(apiPkg.chatId), "$ts-${apiPkg.senderId}.wav")
        file.parentFile?.mkdirs()
        val ctx = StreamCtx(apiPkg, file)
        contexts[ev.key.value] = ctx
        ctx.messageId = runCatching {
            DataManager.getAppRepo().saveMessage(
                pkg = apiPkg,
                extraData = MessageExtraData.PTT(path = file.absolutePath, encoderType = ev.encoderType),
                state = MessageState.RECEIVING,
                epochTimeMs = ts,
            )
        }.getOrNull()
        runCatching { DataManager.getCallbacks()?.startedReceivingPTT(apiPkg, file) }
    }

    /**
     * Close the file, then settle the row on what actually reached disk: RECEIVED when there is audio,
     * FAILED when there is none. A stream that produced no decoded chunk leaves no file, and a row
     * pointing at a path that does not exist reads in the conversation as a playable PTT that cannot
     * be played.
     *
     * The wait is bounded, and falls back to asking the writer directly: a wedged disk must delay this
     * stream's settle, never every stream after it. [PttWavWriter.hasAudio] is safe to read from here.
     */
    private suspend fun onEndEvent(ev: End) {
        val ctx = contexts.remove(ev.key.value) ?: return
        val ack = CompletableDeferred<Boolean>()
        val hasAudio =
            if (writes.trySend(Close(ctx.writer, ack)).isSuccess) {
                withTimeoutOrNull(WRITER_CLOSE_TIMEOUT_MS) { ack.await() } ?: ctx.writer.hasAudio
            } else {
                ctx.writer.hasAudio
            }
        runCatching {
            ctx.messageId?.let { id ->
                if (hasAudio) DataManager.getAppRepo().updateMessageReceived(id)
                else DataManager.getAppRepo().updateMessageState(id, MessageState.FAILED)
            }
        }
        // Closes the pair started by startedReceivingPTT. Fired after the WAV is written, so a host that
        // reacts by opening the file sees a complete one. Separate runCatching: a persistence failure
        // above must not swallow the app's end-of-stream signal.
        runCatching { DataManager.getCallbacks()?.stopReceivingPTT(ctx.apiPkg) }
    }

    private class StreamCtx(val apiPkg: StardustAPIPackage, val file: File) {
        val writer = PttWavWriter(file)
        var messageId: Long? = null
        var firstPcmSeen = false
    }

    private sealed interface Ev
    private class Packet(
        val pkg: StardustPackage,
        val key: StreamKey,
        val encoderType: EncoderType,
    ) : Ev
    private class Pcm(val key: StreamKey, val samples: ShortArray, val rate: Int) : Ev
    private class End(val key: StreamKey) : Ev

    private sealed interface WriteCmd
    private class Append(val writer: PttWavWriter, val samples: ShortArray, val rate: Int) : WriteCmd
    private class Close(val writer: PttWavWriter, val ack: CompletableDeferred<Boolean>) : WriteCmd

    private companion object {
        const val WRITER_THREAD_NAME = "stardust-ptt-wav"

        /** Long enough that only a wedged disk hits it, short enough not to stall the receive path. */
        const val WRITER_CLOSE_TIMEOUT_MS = 5_000L
    }
}
