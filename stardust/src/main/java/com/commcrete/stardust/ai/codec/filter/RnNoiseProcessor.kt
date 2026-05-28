package com.commcrete.stardust.ai.codec.filter

import android.util.Log
import com.commcrete.stardust.ai.codec.filter.NoiseProcessor
import java.lang.reflect.Method

/**
 * RNNoise-based [NoiseProcessor] adapter.
 *
 * RNNoise (xiph) operates on **480-sample int16 frames at 48 kHz mono**.
 * This adapter:
 *  - upsamples the recorder's PCM to 48 kHz (linear; cheap 1:2 when sampleRate=24000)
 *  - accumulates 480-sample frames and feeds them to the native denoiser
 *  - downsamples cleaned audio back to the original sample rate
 *  - keeps a residual queue for the partial frame at the end of each buffer
 *
 * ## Why reflection?
 * Multiple Android RNNoise wrappers exist with slightly different package /
 * method names. To avoid pinning :stardust to one specific artifact, this
 * adapter discovers the native API at runtime. If no wrapper is on the
 * classpath, it transparently falls back to pass-through — recording is
 * never broken by a denoiser issue.
 *
 * ## How to enable real denoising
 * Add ONE of these to `stardust/build.gradle.kts`:
 * ```
 * // JitPack wrapper (drop-in, no NDK setup):
 * implementation("com.github.theeasiestway:android-rnnoise:1.0.4")
 * // …or your own JNI shim around xiph/rnnoise (place .so under jniLibs/<abi>/)
 * ```
 *
 * ## Custom integration
 * If your wrapper class/methods aren't auto-discovered, set the override
 * fields before [init]:
 * ```
 * val np = RnNoiseProcessor().apply {
 *     classNameOverride   = "my.pkg.MyRnNoise"
 *     processMethodOverride = "denoise"
 * }
 * ```
 *
 * Auto-discovery candidates:
 *  - class:   `com.theeasiestway.rnnoise.Rnnoise`,
 *             `com.commcrete.rnnoise.RnNoise`,
 *             `org.xiph.rnnoise.RNNoise`
 *  - init:    `initialize` | `init` (optional)
 *  - process: `processFrame(short[]) : short[]` | `process` | `denoise`
 *  - release: `deinitialize` | `release` | `destroy` | `deInit` (optional)
 */
class RnNoiseProcessor : NoiseProcessor {

    companion object {
        private const val TAG = "RnNoiseProcessor"
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

    // Reflected native handles
    private var nativeInstance: Any? = null
    private var processMethod: Method? = null
    private var releaseMethod: Method? = null

    override fun init(sampleRate: Int) {
        this.sampleRate = sampleRate
        pending48k = ShortArray(0)
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

        // 1) Upsample to 48 kHz.
        val up = if (sampleRate == NATIVE_RATE) buffer.copyOf(length)
        else resample(buffer, length, sampleRate, NATIVE_RATE)

        // 2) Concatenate residual + new samples.
        val combined = ShortArray(pending48k.size + up.size)
        System.arraycopy(pending48k, 0, combined, 0, pending48k.size)
        System.arraycopy(up, 0, combined, pending48k.size, up.size)

        // 3) Run RNNoise frame-by-frame.
        var off = 0
        val frame = ShortArray(FRAME_SIZE)
        while (off + FRAME_SIZE <= combined.size) {
            try {
                System.arraycopy(combined, off, frame, 0, FRAME_SIZE)
                val cleaned = proc.invoke(instance, frame) as? ShortArray
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
        else resample(processed, processed.size, NATIVE_RATE, sampleRate)

        val toCopy = minOf(down.size, length)
        if (toCopy > 0) System.arraycopy(down, 0, buffer, 0, toCopy)
        // Trailing [length - toCopy] samples retain their pre-NS values
        // (sub-frame shortfall); they get denoised on the next call.
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
    }

    /** Linear-interpolation resampler. Adequate for ASR; swap for polyphase FIR for max fidelity. */
    private fun resample(input: ShortArray, length: Int, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || length <= 0) return input.copyOf(length)
        val outLen = ((length.toLong() * toRate) / fromRate).toInt()
        val out = ShortArray(outLen)
        val step = fromRate.toDouble() / toRate.toDouble()
        var pos = 0.0
        for (i in 0 until outLen) {
            val idx = pos.toInt()
            val frac = pos - idx
            val a = input[idx.coerceAtMost(length - 1)].toInt()
            val b = input[(idx + 1).coerceAtMost(length - 1)].toInt()
            val v = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
            pos += step
        }
        return out
    }
}