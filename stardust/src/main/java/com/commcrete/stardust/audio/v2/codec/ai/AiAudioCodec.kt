package com.commcrete.stardust.audio.v2.codec.ai

import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.stardust.ai.codec.WavTokenizerEncoder
import com.commcrete.stardust.audio.v2.codec.AudioCodec
import com.commcrete.stardust.audio.v2.codec.DecoderSession
import com.commcrete.stardust.audio.v2.codec.EncoderSession
import com.commcrete.stardust.stardust.StardustPackageUtils.StardustOpCode
import com.commcrete.stardust.util.audio.RecorderUtils

/**
 * v2 wrapper around the existing [WavTokenizerEncoder] /
 * [WavTokenizerDecoder] singletons.
 *
 * The actual ML model construction and lifecycle is **unchanged** —
 * [com.commcrete.stardust.ai.codec.AIModuleInitializer] still owns the
 * singletons, and [com.commcrete.stardust.audio.v2.codec.CodecBootstrap]
 * just registers this codec once the singletons exist. The singletons
 * are passed in by reference so this wrapper is testable with mocks.
 *
 * # Per-stream multiplex
 *
 * The underlying decoder has internal mutable state (`index`,
 * `cutTokens`, `loop`, `listEnergy`). The receive side wants to run
 * multiple concurrent streams over the same decoder instance — see
 * [AiDecoderSession.decode] for the save → restore → decode → save
 * pattern that makes this safe under the [com.commcrete.stardust.audio.v2.codec.CodecRegistry]
 * mutex.
 *
 * # Wire format quirks
 *
 *  - **Payload prefix `0x00`**: the AI model emits a model-type byte
 *    at index 0 of every packet so the receiver can dispatch into
 *    the right tokenizer variant. We don't run multiple variants
 *    today but the byte is on the wire for forward compatibility.
 */
class AiAudioCodec(
    private val encoder: WavTokenizerEncoder,
    private val decoder: WavTokenizerDecoder,
    /**
     * Model-type selector for the decoder. Reads
     * `SharedPreferencesUtil.getAudioModelType(ctx)` in the legacy
     * code; v2 takes it as a lambda so the session can read it lazily
     * per chunk (the user setting can change between sessions).
     */
    private val modelTypeProvider: () -> WavTokenizerDecoder.ModelType,
) : AudioCodec {

    override val id: RecorderUtils.CODE_TYPE = RecorderUtils.CODE_TYPE.AI
    override val sampleRateHz: Int = 24_000
    override val opCodeSend: StardustOpCode = StardustOpCode.SEND_PTT_AI
    override val opCodesReceive: Set<StardustOpCode> = setOf(StardustOpCode.SEND_PTT_AI)

    /**
     * Model-type prefix byte. Matches `PttSendManager.sendData`:
     * `fullData[0] = 0x00; arraycopy(data, 0, fullData, 1, data.size)`.
     */
    override val sendPayloadPrefix: ByteArray = byteArrayOf(0x00)

    override fun newEncoderSession(): EncoderSession = AiEncoderSession(encoder)

    override fun newDecoderSession(): DecoderSession =
        AiDecoderSession(decoder, modelTypeProvider)
}

