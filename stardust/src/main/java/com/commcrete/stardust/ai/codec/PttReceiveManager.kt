package com.commcrete.stardust.ai.codec

import android.media.MediaCodec
import android.util.Log
import com.commcrete.aiaudio.codecs.BitPacking12
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.media.PcmStreamPlayer
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Decoder for incoming PTT packets.
 *
 * **Multi-stream aware.** A user can receive PTTs from several different
 * channels at the same time (e.g. multiple talkers on different carriers),
 * and every stream needs its own decoder continuity — `previousTokens`,
 * `previousSamples` AND the [WavTokenizerDecoder]'s internal `index` /
 * `cutTokens` / `loop`. Mixing those across streams causes audible
 * head-cut and progressive desynchronisation.
 *
 * The decoder is a heavy ML singleton, so we **multiplex** N streams over
 * one decoder instance by:
 *  1. attaching `(from, source, modelType)` to every queued chunk so the
 *     consumer always knows which stream it belongs to,
 *  2. tracking per-stream state (last unpack, last samples, last
 *     timestamp + a [WavTokenizerDecoder.InternalState] snapshot) keyed
 *     by `(from, source)`,
 *  3. **save → restore → decode → save** of decoder internal state for
 *     every chunk under the shared [PttSendManager.codecMutex] so neither
 *     concurrent receives nor a parallel send can corrupt each other.
 *
 * Streams that go silent for [STREAM_GAP_RESET_MS] are treated as ended;
 * their entry is purged on the next chunk by the periodic prune.
 */
object PttReceiveManager {
    private const val TAG = "PttManager"
    private const val BUFFERING_TIME_MS = 500L

    /**
     * If no chunk has arrived within this many milliseconds, we treat the
     * next packet as the start of a brand-new receive stream and reset the
     * shared [wavTokenizerDecoder]. Without this, decoder internal state
     * (`index`, `cutTokens`) accumulates across independent receive
     * sessions and progressively degrades audio over many PTTs.
     */
    private const val STREAM_GAP_RESET_MS = 2_000L

    /**
     * Idle streams older than this are evicted from [streams] on the next
     * incoming chunk so the map can't grow unbounded as channels come and
     * go over a long-lived session.
     */
    private const val STREAM_IDLE_EVICT_MS = 30_000L

    private var coroutineScope = CoroutineScope(Dispatchers.Default) // Scope for decoding and frame dropping
    private var decodingJob: Job? = null
    private var toDecodeQueue = Channel<IncomingChunk>(Channel.UNLIMITED)

    private var wavTokenizerDecoder: WavTokenizerDecoder = AIModuleInitializer.wavTokenizerDecoder

    /** Per-stream continuity, keyed by `(from|source)`. */
    private val streams = HashMap<String, ReceiveStream>()

    fun init() {
        // Idempotent: previous job (if any) is cancelled so we don't end up
        // with two consumers racing on the same channel after re-init.
        decodingJob?.cancel()
        startDecodingJob()
    }

    data class AIDecodeData (
        val data: ByteArray,
        val from: String = "",
        val source: String = "",
        val modelType: WavTokenizerDecoder.ModelType = WavTokenizerDecoder.ModelType.General,
        val onPcmReady: ((ShortArray) -> Unit)? = null
    )

    fun addNewData(data: ByteArray, from : String, source : String? = null, modelType: WavTokenizerDecoder.ModelType? = null,
                   onPcmReady: ((ShortArray) -> Unit)? = null) {
        toDecodeQueue.trySend(
            IncomingChunk(
                data = data,
                from = from,
                source = source,
                modelType = modelType ?: WavTokenizerDecoder.ModelType.General,
            )
        )
    }

    fun addNewData(aiDecodeData: AIDecodeData) {
        toDecodeQueue.trySend(
            IncomingChunk(
                data = aiDecodeData.data,
                from = aiDecodeData.from,
                source = aiDecodeData.source,
                modelType = aiDecodeData.modelType,
            )
        )
    }

    /**
     * One queued incoming chunk. Carries the stream identity with it so
     * the decoder loop never has to consult mutable shared state — that
     * was the bug that mixed concurrent channels' continuity together.
     */
    private data class IncomingChunk(
        val data: ByteArray,
        val from: String,
        val source: String?,
        val modelType: WavTokenizerDecoder.ModelType,
    )

    /**
     * Mutable per-channel continuity. The decoder is shared, so we save
     * and restore [decoderState] around every decode call.
     */
    private class ReceiveStream(
        val key: String,
        val from: String,
        val source: String?,
    ) {
        var modelType: WavTokenizerDecoder.ModelType = WavTokenizerDecoder.ModelType.General
        var lastUnpack: List<Long>? = null
        var lastDecodedSamples: ShortArray? = null
        var lastUnpackTime: Long = 0L
        /** Snapshot of decoder internal state captured AFTER this stream's most recent decode. */
        var decoderState: WavTokenizerDecoder.InternalState = WavTokenizerDecoder.InternalState.INITIAL
    }

    private fun streamKey(from: String, source: String?): String = "$from|${source ?: ""}"

    private fun startDecodingJob() {
        decodingJob = coroutineScope.launch {
            while (isActive) { // Keep the decoding loop active
                try {
                    val chunk = toDecodeQueue.tryReceive().getOrNull()

                    if (chunk != null) {
                        Log.d(TAG, "Data received of size: ${chunk.data.size} (from=${chunk.from}, source=${chunk.source})")
                        val decodedData = chunk.data.sliceArray(1 until chunk.data.size)
                        Log.d(TAG, "Received data: ${decodedData.toHexString()}")

                        handleTokenizerChunk(decodedData, chunk)
                    }

                } catch (e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec exception during decoding: ${e.diagnosticInfo}", e)
                    break // Exit the decoding loop on error
                } catch (e: Exception) {
                    Log.e(TAG, "Error in decoding loop: ${e.message}", e)
                    break // Exit the decoding loop on other errors
                }

                // Small delay to prevent a tight loop from consuming too much CPU if no buffers are available
                delay(1) // Adjust delay as needed
            }
            Log.d(TAG, "Decoding job finished.")
        }
    }

    private suspend fun handleTokenizerChunk(decodedData: ByteArray, chunk: IncomingChunk) {
        val unpack = BitPacking12.unpack12(decodedData)

        val key = streamKey(chunk.from, chunk.source)
        val now = System.currentTimeMillis()

        // Evict any streams that have gone fully idle so the map can't
        // grow unbounded as channels come and go over a long session.
        if (streams.size > 1) pruneIdleStreams(now)

        val stream = streams.getOrPut(key) {
            ReceiveStream(key = key, from = chunk.from, source = chunk.source)
        }
        stream.modelType = chunk.modelType

        // A gap longer than [STREAM_GAP_RESET_MS] within the SAME stream
        // is treated as the start of a fresh PTT — drop continuity for
        // this stream only (other streams keep theirs).
        val isContinuation = stream.lastUnpack != null && (now - stream.lastUnpackTime) < STREAM_GAP_RESET_MS
        val previousUnpack = if (isContinuation) stream.lastUnpack else null
        val previousSample = if (isContinuation) stream.lastDecodedSamples else null

        // Acquire the shared codec mutex BEFORE touching the decoder.
        //
        // Decoder is a singleton with mutable per-stream state
        // (`index`, `cutTokens`, `loop`). We restore THIS stream's
        // snapshot before decoding and capture it again afterwards so
        // every other stream — including the encoder's send session
        // running on PttSendManager — can multiplex over the same
        // decoder without their continuity bleeding into ours.
        val finalPcmData = PttSendManager.codecMutex.withLock {
            if (!isContinuation) {
                // Fresh stream → start from a clean slate.
                stream.decoderState = WavTokenizerDecoder.InternalState.INITIAL
            }
            wavTokenizerDecoder.restoreInternalState(stream.decoderState)
            val pcm = wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, stream.modelType)
            stream.decoderState = wavTokenizerDecoder.snapshotInternalState()
            pcm
        }
        Log.d(TAG, "Decoded tokenizer unpack size ${unpack.size} , PCM data: ${finalPcmData.size} samples (key=$key)")

        // Per-stream continuity for the next chunk on THIS channel.
        stream.lastUnpack = unpack
        stream.lastDecodedSamples = finalPcmData
        stream.lastUnpackTime = now

        // Add buffering delay only if there was no previous unpack (first packet)
        if (previousUnpack == null)
            delay(BUFFERING_TIME_MS)

        PcmStreamPlayer.enqueue(finalPcmData, 24000, chunk.from, chunk.source)
    }

    /**
     * Drop streams that have been silent for longer than
     * [STREAM_IDLE_EVICT_MS]. Their continuity tokens / samples are
     * irrelevant by definition; clearing the entry keeps the map small
     * and gives the next PTT on that channel a clean start.
     */
    private fun pruneIdleStreams(now: Long) {
        val it = streams.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if ((now - entry.value.lastUnpackTime) > STREAM_IDLE_EVICT_MS) {
                Log.d(TAG, "Evicting idle receive stream ${entry.key}")
                it.remove()
            }
        }
    }

    /**
     * Test-only entry that bypasses the queue. Same multi-stream
     * semantics as [handleTokenizerChunk] but takes already-unpacked
     * tokens and a synthetic stream key.
     */
    suspend fun handleTokenizerChunkForTest(unpack: List<Long>) {
        val key = "test|"
        val now = System.currentTimeMillis()
        val stream = streams.getOrPut(key) {
            ReceiveStream(key = key, from = "from", source = "source")
        }
        val isContinuation = stream.lastUnpack != null && (now - stream.lastUnpackTime) < STREAM_GAP_RESET_MS
        val previousUnpack = if (isContinuation) stream.lastUnpack else null
        val previousSample = if (isContinuation) stream.lastDecodedSamples else null
        val modelTypeSelected = SharedPreferencesUtil.getAudioModelType(DataManager.context)

        val finalPcmData = PttSendManager.codecMutex.withLock {
            if (!isContinuation) {
                stream.decoderState = WavTokenizerDecoder.InternalState.INITIAL
            }
            wavTokenizerDecoder.restoreInternalState(stream.decoderState)
            val pcm = wavTokenizerDecoder.decode(unpack, previousUnpack, previousSample, modelTypeSelected)
            stream.decoderState = wavTokenizerDecoder.snapshotInternalState()
            pcm
        }
        Log.d(TAG, "Decoded tokenizer unpack size ${unpack.size} , PCM data: ${finalPcmData.size} samples (test)")

        stream.lastUnpack = unpack
        stream.lastDecodedSamples = finalPcmData
        stream.lastUnpackTime = now

        if (previousUnpack == null)
            delay(BUFFERING_TIME_MS)

        PcmStreamPlayer.enqueue(finalPcmData, 24000, "from", "source")
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x ".format(it) }
}