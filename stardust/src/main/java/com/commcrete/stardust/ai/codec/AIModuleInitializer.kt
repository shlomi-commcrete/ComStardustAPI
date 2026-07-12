package com.commcrete.stardust.ai.codec


import android.util.Log
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.stardust.ai.codec.filter.PyTorchInitGate
import com.commcrete.aiaudio.codecs.WavTokenizerEncoder
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AIModuleInitializer {

    private const val TAG = "AIModuleInitializer"

    /** Public lateinit fields kept for backwards-compatibility with existing callers. */
    lateinit var wavTokenizerEncoder: WavTokenizerEncoder
    lateinit var wavTokenizerDecoder: WavTokenizerDecoder

    var aiEnabled = false
    private const val TAG = "AIModuleInitializer"

    /**
     * Single-shot guards for the **construction** step (`WavTokenizerEncoder(...)` /
     * `WavTokenizerDecoder(...)`). Without these, two concurrent calls to [init]
     * could both pass the `lateinit` `isInitialized` check before either
     * assignment landed and end up constructing two ML model wrappers (each
     * with its own `by lazy { Module(...) }`, so the PyTorch model file would
     * be loaded into RAM twice — one instance becomes orphaned).
     *
     * The per-instance `initModule()` call is already idempotent because
     * `module` is `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`.
     */
    private val encoderConstructStarted = AtomicBoolean(false)
    private val decoderConstructStarted = AtomicBoolean(false)
    private val pipelineStarted = AtomicBoolean(false)

    /**
     * Completes when [wavTokenizerEncoder] and [wavTokenizerDecoder] have been
     * constructed and their lazy `module` triggered (via [initModule]).
     * Callers that need the codec ready (e.g. before the first PTT) can
     * `await()` this instead of guessing with `delay(...)`.
     *
     * Stays `null` until [initModules] is called for the first time.
     */
    @Volatile
    private var readiness: CompletableDeferred<Unit>? = null

    fun ready(): CompletableDeferred<Unit>? = readiness

    fun initModules(context: Context, pluginContext: Context) {
        // Idempotent at the pipeline level. Re-entry only re-fires if the
        // first attempt failed before completing readiness.
        if (!pipelineStarted.compareAndSet(false, true)) {
            Log.d(TAG, "initModules: already started — ignoring duplicate call")
            return
        }
        val signal = CompletableDeferred<Unit>().also { readiness = it }
        Scopes.getDefaultCoroutine().launch {
            try {
                init(context, pluginContext)
                // Wait until both constructors have actually landed before
                // touching their lazy modules. Bounded so we don't block
                // forever on a broken init.
                awaitConstructed(timeoutMs = 10_000L)
                // initModule() returns a cached Job that completes once the
                // PyTorch model has been loaded into the underlying lazy
                // `module`. Joining is now exact (no magic delay) — the
                // pipeline signals readiness the moment both models are
                // actually live in RAM.
                joinInitModuleJobs()
                PttSendManager.init(context)
                PttReceiveManager.init()
                signal.complete(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "initModules pipeline failed", t)
                signal.completeExceptionally(t)
                // Allow a future call to retry from scratch.
                pipelineStarted.set(false)
                readiness = null
            }
        }
    }

    /**
     * Public no-arg overload — kept for binary-compat with existing call
     * sites. Fire-and-forget; if you want to await, use [ready] /
     * [readinessAwait] instead.
     */
    fun initModules() {
        Scopes.getDefaultCoroutine().launch { triggerInitModuleJobs() }
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

        if (!resolveAiEnabled()) return@withLock

        createCodecs()
        warmUpCodecs()
        initPttManagers()

        initialized = true
        Log.d(TAG, "AI modules initialized.")
    }

    private fun resolveAiEnabled(): Boolean {
        aiEnabled = PyTorchInitGate.isPrimaryInitializer()
        if (!aiEnabled) {
            Log.d(TAG, "AI Codec not enabled for this process.")
            // IMPORTANT: do NOT instantiate or reference any org.pytorch.* here
            return
        }
        Scopes.getDefaultCoroutine().launch {
            // Race-safe single-shot construction. The CAS ensures only ONE
            // coroutine ever runs the constructor for each module, even if
            // [init] is invoked from multiple call sites concurrently.
            if (encoderConstructStarted.compareAndSet(false, true)) {
                try {
                    if (!::wavTokenizerEncoder.isInitialized) {
                        wavTokenizerEncoder = WavTokenizerEncoder(context, pluginContext)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "WavTokenizerEncoder construction failed", t)
                    encoderConstructStarted.set(false) // allow retry
                    throw t
                }
            }
            if (decoderConstructStarted.compareAndSet(false, true)) {
                try {
                    if (!::wavTokenizerDecoder.isInitialized) {
                        wavTokenizerDecoder = WavTokenizerDecoder(context, pluginContext)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "WavTokenizerDecoder construction failed", t)
                    decoderConstructStarted.set(false) // allow retry
                    throw t
                }
            }
        }
    }

    private suspend fun awaitConstructed(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (::wavTokenizerEncoder.isInitialized && ::wavTokenizerDecoder.isInitialized) return
            delay(50)
        }
        Log.w(TAG, "awaitConstructed: timed out after ${timeoutMs}ms — proceeding anyway")
    }

    /**
     * Trigger (without awaiting) the cached init jobs on both modules. Safe
     * to call repeatedly — `initModule()` returns the same lazy-cached Job.
     */
    private fun triggerInitModuleJobs() {
        if (::wavTokenizerEncoder.isInitialized) wavTokenizerEncoder.initModule()
        if (::wavTokenizerDecoder.isInitialized) wavTokenizerDecoder.initModule()
    }

    /**
     * Trigger AND await both init jobs. Joining the same lazy-cached Job
     * from a second call site is free if the first call has already
     * completed it.
     */
    private suspend fun joinInitModuleJobs() {
        val encoderJob = if (::wavTokenizerEncoder.isInitialized) wavTokenizerEncoder.initModule() else null
        val decoderJob = if (::wavTokenizerDecoder.isInitialized) wavTokenizerDecoder.initModule() else null
        encoderJob?.join()
        decoderJob?.join()
    }
}