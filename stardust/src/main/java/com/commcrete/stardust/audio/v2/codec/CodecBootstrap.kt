package com.commcrete.stardust.audio.v2.codec

import android.content.Context
import com.commcrete.stardust.ai.codec.AIModuleInitializer
import com.commcrete.stardust.audio.v2.codec.ai.AiAudioCodec
import com.commcrete.stardust.audio.v2.codec.codec2.Codec2AudioCodec
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.audio.RecorderUtils
import timber.log.Timber

/**
 * One-shot registration entry point for the v2 codec layer.
 *
 * **Call once at SDK init, AFTER [AIModuleInitializer.initModules]
 * has signalled readiness** — the AI codec wrapper holds references
 * to the underlying `wavTokenizerEncoder` / `wavTokenizerDecoder`
 * singletons, which must be constructed before we capture them.
 *
 * Idempotent: calling [bootstrap] more than once is safe — the
 * [CodecRegistry] silently ignores duplicate registrations.
 *
 * # Wire-up snippet (NOT yet applied — see README)
 *
 * ```kotlin
 * AIModuleInitializer.initModules(ctx, pluginCtx)
 * AIModuleInitializer.ready()?.invokeOnCompletion {
 *     CodecBootstrap.bootstrap(ctx)
 * }
 * ```
 *
 * # What registers
 *
 *  - [Codec2AudioCodec] — always registers, no async dependency.
 *  - [AiAudioCodec] — only if the AI module is enabled AND the
 *    encoder/decoder singletons are live. Otherwise skipped with a
 *    warning (AI PTT silently unavailable; CODEC2 PTT still works).
 */
object CodecBootstrap {

    private const val TAG = "CodecBootstrap"

    /**
     * Register every codec the SDK supports. Idempotent.
     *
     * @return list of [RecorderUtils.CODE_TYPE]s that ended up
     *         registered after this call — caller can verify the AI
     *         codec landed before exposing AI as a public option.
     */
    fun bootstrap(context: Context): List<RecorderUtils.CODE_TYPE> {
        val registered = mutableListOf<RecorderUtils.CODE_TYPE>()

        // CODEC2 — no async dependency, always safe.
        if (!CodecRegistry.isRegistered(RecorderUtils.CODE_TYPE.CODEC2)) {
            CodecRegistry.register(Codec2AudioCodec())
            registered.add(RecorderUtils.CODE_TYPE.CODEC2)
            Timber.tag(TAG).d("Registered Codec2AudioCodec")
        }

        // AI — only if the ML modules are actually live. Guarded so
        // a host that disabled AI (e.g. secondary process via
        // PyTorchInitGate.isPrimaryInitializer == false) doesn't NPE
        // on uninitialized `lateinit var wavTokenizerEncoder`.
        val aiReady = runCatching { aiSingletonsReady() }.getOrDefault(false)
        if (aiReady && !CodecRegistry.isRegistered(RecorderUtils.CODE_TYPE.AI)) {
            CodecRegistry.register(
                AiAudioCodec(
                    encoder = AIModuleInitializer.wavTokenizerEncoder,
                    decoder = AIModuleInitializer.wavTokenizerDecoder,
                    modelTypeProvider = { readAudioModelType(context) },
                )
            )
            registered.add(RecorderUtils.CODE_TYPE.AI)
            Timber.tag(TAG).d("Registered AiAudioCodec")
        } else if (!aiReady) {
            Timber.tag(TAG).w("AI codec not registered — AIModuleInitializer singletons not ready")
        }

        return registered
    }

    /**
     * Wrapper around [SharedPreferencesUtil.getAudioModelType] —
     * isolates the deprecation warning to one suppressed function.
     *
     * The legacy accessor is `@Deprecated` ("returns
     * ModelType.General always") but it remains the canonical way to
     * pass the AI model type into the decoder. v2 preserves the v1
     * behavior verbatim until a real model-type selector is wired in.
     */
    @Suppress("DEPRECATION")
    private fun readAudioModelType(context: Context): com.commcrete.aiaudio.codecs.WavTokenizerDecoder.ModelType =
        SharedPreferencesUtil.getAudioModelType(context)

    /**
     * Quick check whether [AIModuleInitializer]'s singletons are
     * constructed. Uses [AIModuleInitializer.aiEnabled] to short-
     * circuit when AI was explicitly disabled (secondary process)
     * so we don't throw `UninitializedPropertyAccessException` when
     * touching the `lateinit` properties.
     */
    private fun aiSingletonsReady(): Boolean {
        if (!AIModuleInitializer.aiEnabled) return false
        // Touch the lateinit var via the public reference — throws
        // UninitializedPropertyAccessException if not initialized.
        return runCatching {
            AIModuleInitializer.wavTokenizerEncoder
            AIModuleInitializer.wavTokenizerDecoder
            true
        }.getOrDefault(false)
    }
}



