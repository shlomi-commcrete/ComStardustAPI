package com.commcrete.stardust.ai.codec


import android.util.Log
import com.commcrete.stardust.ai.codec.filter.PyTorchInitGate
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object AIModuleInitializer {

    private const val TAG = "AIModuleInitializer"

    /** Bounded wait for the PyTorch model-load jobs before wiring up PTT managers. */
    private const val MODEL_LOAD_TIMEOUT_MS = 10_000L

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
    fun initModules() {
        Scopes.getDefaultCoroutine().launch {
            initModulesSuspending()
        }
    }

    /** Suspend variant for callers that already run in a coroutine and want to await completion. */
    suspend fun initModulesSuspending() = initLock.withLock {
        if (initialized) return@withLock

        try {
            if (!resolveAiEnabled()) return@withLock

            createCodecs()
            joinModelLoad()
            initPttManagers()

            initialized = true
            Log.d(TAG, "AI modules initialized.")
        } catch (t: Throwable) {
            // Leave `initialized` false so a later call retries the whole
            // pipeline instead of getting permanently stuck behind this
            // mutex on a bad model asset / OOM / I/O error. Contained here
            // so it can't escape as an uncaught exception on the bare
            // Dispatchers.Default coroutine `initModules()` launches on.
            Log.e(TAG, "AI module init failed", t)
        }
    }

    private fun resolveAiEnabled(): Boolean {
        aiEnabled = PyTorchInitGate.isPrimaryInitializer()
        if (!aiEnabled) {
            Log.d(TAG, "AI Codec not enabled for this process.")
        }
        return aiEnabled
    }

    private fun createCodecs() {
        if (!::wavTokenizerEncoder.isInitialized) {
            wavTokenizerEncoder = WavTokenizerEncoder()
        }
        if (!::wavTokenizerDecoder.isInitialized) {
            wavTokenizerDecoder = WavTokenizerDecoder()
        }
    }

    /**
     * Wait for both PyTorch models to finish loading into RAM before
     * [initPttManagers] starts the encode/decode pipeline. Without this, the
     * first real PTT chunk can arrive before the lazy `module` has loaded,
     * so the encoder blocks synchronously on that lazy initializer's lock —
     * the "slow ML step inline on hot loop" latency bug, on the first press.
     * Bounded so a stalled load can't hang init forever; falls back to
     * proceeding anyway (logged) rather than failing the whole pipeline.
     */
    private suspend fun joinModelLoad() {
        val encoderJob: Job = wavTokenizerEncoder.initModule()
        val decoderJob: Job = wavTokenizerDecoder.initModule()
        val joined = withTimeoutOrNull(MODEL_LOAD_TIMEOUT_MS) {
            encoderJob.join()
            decoderJob.join()
        }
        if (joined == null) {
            Log.w(TAG, "joinModelLoad: timed out after ${MODEL_LOAD_TIMEOUT_MS}ms — proceeding anyway")
        }
    }

    private fun initPttManagers() {
        PttSendManager.init()
        PttReceiveManager.init()
    }
}
