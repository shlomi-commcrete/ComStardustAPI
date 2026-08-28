package com.commcrete.stardust.audio.v2.framework

import android.content.Context
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ai.codec.WavHelper
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.mapper.StardustPackageApiMapper
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Framework ring — the v2 receive-side app-integration that legacy `PlayerUtils` / `PttReceiveManager`
 * did inline: persist a PTT message row, fire the [com.commcrete.stardust.StardustAPICallbacks]
 * (startedReceivingPTT / receivePTT), and write the decoded audio to a WAV for replay. Without this,
 * received PTTs play live but never appear in history and never notify the app.
 *
 * Two producers feed it — the router (per received packet, has the [StardustPackage]) and the receive
 * stream (per decoded [PcmChunk]) — plus a stream-end signal. All events run through ONE actor
 * coroutine so ordering is deterministic: the first packet's row+callback always precedes later
 * callbacks, and finalize (WAV write) happens after the last decoded frame.
 */
class PttReceiveStore(private val context: Context) {

    private val events = Channel<Ev>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contexts = HashMap<String, StreamCtx>()

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

    private suspend fun handle(ev: Ev) {
        when (ev) {
            is Packet -> onPacketEvent(ev)
            is Pcm -> contexts[ev.key.value]?.let { ctx ->
                if (ctx.rate == 0) ctx.rate = ev.rate
                ev.samples.forEach { ctx.samples.add(it) }
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

    private suspend fun onPacketEvent(ev: Packet) {
        // Only the first packet of a stream sets up the row + startedReceivingPTT; per-chunk
        // receivePTT is driven by decoded PCM (see the Pcm branch), not by raw packets.
        if (contexts.containsKey(ev.key.value)) return
        val apiPkg = StardustPackageApiMapper.toStardustAPIPackage(ev.pkg) ?: return
        val ts = System.currentTimeMillis()
        val file = File(context.filesDir, "${apiPkg.chatId}/$ts-${apiPkg.senderId}.wav")
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

    private suspend fun onEndEvent(ev: End) {
        val ctx = contexts.remove(ev.key.value) ?: return
        runCatching {
            if (ctx.samples.isNotEmpty() && ctx.rate > 0) {
                WavHelper.createWavFile(ShortArray(ctx.samples.size) { ctx.samples[it] }, ctx.rate, ctx.file)
            }
            ctx.messageId?.let { DataManager.getAppRepo().updateMessageReceived(it) }
        }
        // Closes the pair started by startedReceivingPTT. Fired after the WAV is written, so a host that
        // reacts by opening the file sees a complete one. Separate runCatching: a persistence failure
        // above must not swallow the app's end-of-stream signal.
        runCatching { DataManager.getCallbacks()?.stopReceivingPTT(ctx.apiPkg) }
    }

    private fun shortsToLePcm16(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            out[i++] = (s.toInt() and 0xFF).toByte()
            out[i++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private class StreamCtx(val apiPkg: StardustAPIPackage, val file: File) {
        val samples = ArrayList<Short>()
        var rate = 0
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
}
