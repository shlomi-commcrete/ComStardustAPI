package com.commcrete.stardust.ai.codec

import android.content.Context
import android.util.Log
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.codecs.WavTokenizerEncoder
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AIModuleInitializer {

    private const val TAG = "AIModuleInitializer"

    /** Public lateinit fields kept for backwards-compatibility with existing callers. */
    lateinit var wavTokenizerEncoder: WavTokenizerEncoder
    lateinit var wavTokenizerDecoder: WavTokenizerDecoder

    var aiEnabled = false
        private set

    private val initLock = Mutex()
    @Volatile private var initialized = false

    /**
     * Fire-and-forget initialization entry point.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun initModules(context: Context, pluginContext: Context) {
        Scopes.getDefaultCoroutine().launch {
            initModulesSuspending(context, pluginContext)
        }
    }

    /** Suspend variant for callers that already run in a coroutine and want to await completion. */
    suspend fun initModulesSuspending(context: Context, pluginContext: Context) = initLock.withLock {
        if (initialized) return@withLock

        if (!resolveAiEnabled(context)) return@withLock

        createCodecs(context, pluginContext)
        warmUpCodecs()
        initPttManagers(context)

        initialized = true
        Log.d(TAG, "AI modules initialized.")
    }

    private fun resolveAiEnabled(context: Context): Boolean {
        aiEnabled = PyTorchInitGate.isPrimaryInitializer(context)
        if (!aiEnabled) {
            Log.d(TAG, "AI Codec not enabled for this process.")
        }
        return aiEnabled
    }

    private fun createCodecs(context: Context, pluginContext: Context) {
        if (!::wavTokenizerEncoder.isInitialized) {
            wavTokenizerEncoder = WavTokenizerEncoder(context, pluginContext)
        }
        if (!::wavTokenizerDecoder.isInitialized) {
            wavTokenizerDecoder = WavTokenizerDecoder(context, pluginContext)
        }
    }

    private fun warmUpCodecs() {
        if (::wavTokenizerEncoder.isInitialized) wavTokenizerEncoder.initModule()
        if (::wavTokenizerDecoder.isInitialized) wavTokenizerDecoder.initModule()
    }

    private fun initPttManagers(context: Context) {
        PttSendManager.init(context)
        PttReceiveManager.init()
    }
}