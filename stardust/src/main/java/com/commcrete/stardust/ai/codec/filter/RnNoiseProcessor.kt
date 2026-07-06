package com.commcrete.stardust.ai.codec.filter

import android.util.Log
import com.commcrete.stardust.util.audio.StreamingPolyphaseResampler
import java.lang.reflect.Method

class RnNoiseProcessor : NoiseProcessor {

    companion object {
        private const val TAG = "RnNoiseProcessor"
        private const val TAG_LATENCY = "RnNoise_Latency"
        private const val NATIVE_RATE = 48_000
        private const val FRAME_SIZE = 480 // 10 ms @ 48 kHz, RNNoise's required frame size

        private val CANDIDATE_CLASSES = listOf(
            "com.commcrete.rnnoise.RnNoise",            // bundled in :stardust
            "com.theeasiestway.rnnoise.Rnnoise",        // public JitPack wrapper
            "org.xiph.rnnoise.RNNoise",                 // alternative naming
        )
        private val INIT_METHODS = listOf("initialize", "init")
        private val PROCESS_METHODS = listOf("processFrame", "process", "denoise")
        private val RELEASE_METHODS = listOf("deinitialize", "release", "destroy", "deInit")
    }

    /** Override class lookup if your wrapper isn't auto-discovered. */
    var classNameOverride: String? = null
    var initMethodOverride: String? = null
    var processMethodOverride: String? = null
    var releaseMethodOverride: String? = null

    private var sampleRate: Int = 0
    private var pending48k: ShortArray = ShortArray(0)

    // Stateful resamplers for the native<->48kHz round-trip: carry history across process()
    // calls so this doesn't get a truncated-kernel gain dip at every chunk boundary the way a
    // fresh AudioDsp.resamplePolyphase() call per chunk would. Rebuilt in init() for each session.
    private var upsampler: StreamingPolyphaseResampler? = null
    private var downsampler: StreamingPolyphaseResampler? = null

    // Reflected native handles
    private var nativeInstance: Any? = null
    private var processMethod: Method? = null
    private var releaseMethod: Method? = null

    override fun init(sampleRate: Int) {
        this.sampleRate = sampleRate
        pending48k = ShortArray(0)
        upsampler = if (sampleRate == NATIVE_RATE) null else StreamingPolyphaseResampler(sampleRate, NATIVE_RATE)
        downsampler = if (sampleRate == NATIVE_RATE) null else StreamingPolyphaseResampler(NATIVE_RATE, sampleRate)
        nativeInstance = null
        processMethod = null
        releaseMethod = null

        try {
            val klass = resolveClass()
            if (klass == null) {
                Log.i(TAG, "RNNoise wrapper not on classpath; pass-through mode")
                return
            }

            val instance = klass.getDeclaredConstructor().newInstance()

            // init (optional)
            val initName = initMethodOverride
            val initMethod = if (initName != null) {
                runCatching { klass.getMethod(initName) }.getOrNull()
            } else {
                INIT_METHODS.firstNotNullOfOrNull {
                    runCatching { klass.getMethod(it) }.getOrNull()
                }
            }
            initMethod?.invoke(instance)

            // process (required)
            val procName = processMethodOverride
            val proc = if (procName != null) {
                runCatching { klass.getMethod(procName, ShortArray::class.java) }.getOrNull()
            } else {
                PROCESS_METHODS.firstNotNullOfOrNull {
                    runCatching { klass.getMethod(it, ShortArray::class.java) }.getOrNull()
                }
            }
            if (proc == null) {
                Log.w(TAG, "No compatible process(short[]) on ${klass.name}; pass-through")
                return
            }

            // release (optional)
            val relName = releaseMethodOverride
            val rel = if (relName != null) {
                runCatching { klass.getMethod(relName) }.getOrNull()
            } else {
                RELEASE_METHODS.firstNotNullOfOrNull {
                    runCatching { klass.getMethod(it) }.getOrNull()
                }
            }

            nativeInstance = instance
            processMethod = proc
            releaseMethod = rel
            Log.d(TAG, "init: ready via ${klass.name}#${proc.name} (sampleRate=$sampleRate)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize RNNoise; falling back to pass-through", t)
            nativeInstance = null
            processMethod = null
            releaseMethod = null
        }
    }

    private fun resolveClass(): Class<*>? {
        classNameOverride?.let {
            return runCatching { Class.forName(it) }.getOrNull()
        }
        for (name in CANDIDATE_CLASSES) {
            runCatching { Class.forName(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    override fun process(buffer: ShortArray, length: Int) {
        if (length <= 0) return
        val instance = nativeInstance ?: return
        val proc = processMethod ?: return

        val processStartMs = System.currentTimeMillis()

        // 1) Upsample to 48 kHz.
        val up = if (sampleRate == NATIVE_RATE) buffer.copyOf(length)
        else upsampler?.process(buffer.copyOf(length)) ?: return

        // 2) Concatenate residual + new samples.
        val combined = ShortArray(pending48k.size + up.size)
        System.arraycopy(pending48k, 0, combined, 0, pending48k.size)
        System.arraycopy(up, 0, combined, pending48k.size, up.size)

        // 3) Run RNNoise frame-by-frame.
        var off = 0
        var frameCount = 0
        var nativeCallMs = 0L
        val frame = ShortArray(FRAME_SIZE)
        while (off + FRAME_SIZE <= combined.size) {
            try {
                System.arraycopy(combined, off, frame, 0, FRAME_SIZE)
                val frameStartMs = System.currentTimeMillis()
                val cleaned = proc.invoke(instance, frame) as? ShortArray
                nativeCallMs += System.currentTimeMillis() - frameStartMs
                frameCount++
                if (cleaned != null && cleaned.size >= FRAME_SIZE) {
                    System.arraycopy(cleaned, 0, combined, off, FRAME_SIZE)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "RNNoise frame call failed; disabling for this session", t)
                runCatching { releaseMethod?.invoke(instance) }
                nativeInstance = null
                processMethod = null
                releaseMethod = null
                return
            }
            off += FRAME_SIZE
        }

        // 4) Save tail for next call.
        pending48k = if (combined.size > off) combined.copyOfRange(off, combined.size)
        else ShortArray(0)

        // 5) Downsample processed prefix back and write into [buffer].
        val processed = combined.copyOfRange(0, off)
        val down = if (sampleRate == NATIVE_RATE) processed
        else downsampler?.process(processed) ?: return

        val toCopy = minOf(down.size, length)
        if (toCopy > 0) System.arraycopy(down, 0, buffer, 0, toCopy)
        // Trailing [length - toCopy] samples retain their pre-NS values
        // (sub-frame shortfall); they get denoised on the next call.

        val totalMs = System.currentTimeMillis() - processStartMs
        Log.d(
            TAG_LATENCY,
            "process(): $frameCount frame(s) of $FRAME_SIZE, total ${totalMs}ms " +
                "(native RNNoise calls ${nativeCallMs}ms, resample+overhead ${totalMs - nativeCallMs}ms)"
        )
    }

    override fun release() {
        val inst = nativeInstance
        val rel = releaseMethod
        if (inst != null && rel != null) {
            runCatching { rel.invoke(inst) }
        }
        nativeInstance = null
        processMethod = null
        releaseMethod = null
        pending48k = ShortArray(0)
        upsampler = null
        downsampler = null
    }

    /**
     * `true` if the native denoiser was successfully resolved AND initialised,
     * i.e. [process] will actually run RNNoise on incoming samples. Returns
     * `false` when the wrapper class is missing from the classpath, the
     * native `.so` failed to load, or initialisation threw — in which case
     * [process] is a no-op (pass-through). Callers can use this to surface
     * a loud warning instead of silently shipping unfiltered audio.
     */
    fun isActive(): Boolean = nativeInstance != null && processMethod != null

    /** Name of the resolved native wrapper class, or `null` in pass-through mode. */
    fun activeClassName(): String? = nativeInstance?.javaClass?.name

}