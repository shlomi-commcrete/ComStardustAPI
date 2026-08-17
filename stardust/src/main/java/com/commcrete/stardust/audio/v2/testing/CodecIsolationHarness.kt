package com.commcrete.stardust.audio.v2.testing

import com.commcrete.stardust.audio.v2.application.port.EncoderSession
import com.commcrete.stardust.audio.v2.domain.EncodedFrame
import com.commcrete.stardust.audio.v2.domain.PcmChunk
import com.commcrete.stardust.audio.v2.domain.RecordingId

/**
 * The frame-interleave GOLDEN TEST harness — the gate for open-decision #1 (per-session state
 * isolation, R3).
 *
 * It encodes two inputs two ways and compares:
 *  - REFERENCE: each stream encoded fully in its own fresh session, alone.
 *  - INTERLEAVED: two fresh sessions alive at once, fed chunk-by-chunk A,B,A,B,…
 *
 * If a codec truly isolates per-recording state (CODEC2: a private JNI encoder per session;
 * WavTokenizer done right: continuity held in the session, or save/restore under the codec mutex),
 * each stream's interleaved output is BIT-IDENTICAL to its reference. If it leaks state through a
 * shared singleton (or a native module with hidden buffers not externalized into the session), the
 * two diverge and [Result.isolated] is false. Run this — and require `isolated == true` — before
 * trusting any `NativeModulePool` or shared-instance decode.
 *
 * Codec-agnostic: it drives only [EncoderSession], so the same harness serves a JVM unit test (with
 * fake codecs) and an on-device instrumented test (with the real JNI/PyTorch codecs).
 */
object CodecIsolationHarness {

    data class Result(
        val isolated: Boolean,
        val aMatches: Boolean,
        val bMatches: Boolean,
        val detail: String,
    )

    suspend fun run(
        sampleRateHz: Int,
        newEncoder: (RecordingId) -> EncoderSession,
        inputA: ShortArray,
        inputB: ShortArray,
        chunkSize: Int = 320,
    ): Result {
        val idA = RecordingId(1)
        val idB = RecordingId(2)
        val chunksA = chunk(inputA, chunkSize)
        val chunksB = chunk(inputB, chunkSize)

        // Reference — each stream alone, in a fresh session.
        val refA = encodeIsolated(newEncoder, idA, sampleRateHz, chunksA)
        val refB = encodeIsolated(newEncoder, idB, sampleRateHz, chunksB)

        // Interleaved — both sessions live simultaneously, chunks fed A,B,A,B,…
        val encA = newEncoder(idA)
        val encB = newEncoder(idB)
        val outA = ArrayList<EncodedFrame>()
        val outB = ArrayList<EncodedFrame>()
        val maxLen = maxOf(chunksA.size, chunksB.size)
        for (i in 0 until maxLen) {
            if (i < chunksA.size) outA += encA.encode(PcmChunk(chunksA[i], sampleRateHz, idA))
            if (i < chunksB.size) outB += encB.encode(PcmChunk(chunksB[i], sampleRateHz, idB))
        }
        outA += encA.drain(); encA.close()
        outB += encB.drain(); encB.close()

        val refABytes = concat(refA); val outABytes = concat(outA)
        val refBBytes = concat(refB); val outBBytes = concat(outB)
        val aMatches = refABytes.contentEquals(outABytes)
        val bMatches = refBBytes.contentEquals(outBBytes)
        val detail = "A: ref=${refABytes.size}B interleaved=${outABytes.size}B match=$aMatches | " +
            "B: ref=${refBBytes.size}B interleaved=${outBBytes.size}B match=$bMatches"
        return Result(aMatches && bMatches, aMatches, bMatches, detail)
    }

    private suspend fun encodeIsolated(
        newEncoder: (RecordingId) -> EncoderSession,
        id: RecordingId,
        sampleRateHz: Int,
        chunks: List<ShortArray>,
    ): List<EncodedFrame> {
        val enc = newEncoder(id)
        val out = ArrayList<EncodedFrame>()
        for (c in chunks) out += enc.encode(PcmChunk(c, sampleRateHz, id))
        out += enc.drain()
        enc.close()
        return out
    }

    private fun chunk(data: ShortArray, size: Int): List<ShortArray> {
        if (data.isEmpty() || size <= 0) return emptyList()
        val out = ArrayList<ShortArray>((data.size + size - 1) / size)
        var i = 0
        while (i < data.size) {
            val end = minOf(i + size, data.size)
            out += data.copyOfRange(i, end)
            i = end
        }
        return out
    }

    private fun concat(frames: List<EncodedFrame>): ByteArray {
        val out = ByteArray(frames.sumOf { it.payload.size })
        var p = 0
        for (f in frames) {
            System.arraycopy(f.payload, 0, out, p, f.payload.size)
            p += f.payload.size
        }
        return out
    }
}
