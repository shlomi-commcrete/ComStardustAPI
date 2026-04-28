package com.commcrete.rnnoise

import android.util.Log

/**
 * Thin Kotlin wrapper around xiph/rnnoise via a JNI shim (`librnnoise_jni.so`).
 *
 * Auto-discovered by `com.example.chunkrecorder.RnNoiseProcessor` through
 * reflection — no direct dependency between the two classes.
 *
 * Lifecycle:
 * ```
 * val rn = RnNoise()
 * rn.initialize()                        // creates native DenoiseState
 * val cleaned = rn.processFrame(frame)   // 480 int16 samples @ 48 kHz
 * rn.deinitialize()                      // releases native state
 * ```
 *
 * The native library is loaded lazily on first [initialize]. If the .so is
 * missing (e.g. RNNoise sources weren't fetched / CMake didn't build), the
 * load failure is captured and re-thrown from [initialize], which causes the
 * upstream [com.example.chunkrecorder.RnNoiseProcessor] to gracefully fall
 * back to pass-through.
 *
 * Build: see `stardust/src/main/cpp/CMakeLists.txt` and
 * `stardust/scripts/setup_rnnoise.sh`.
 */
class RnNoise {

    private var handle: Long = 0L

    companion object {
        private const val TAG = "RnNoise"
        private const val LIB_NAME = "rnnoise_jni"

        @Volatile private var loaded: Boolean = false
        @Volatile private var loadError: Throwable? = null

        @Synchronized
        private fun ensureLibraryLoaded() {
            if (loaded || loadError != null) return
            try {
                System.loadLibrary(LIB_NAME)
                loaded = true
                Log.i(TAG, "lib$LIB_NAME.so loaded")
            } catch (t: Throwable) {
                loadError = t
                Log.w(TAG, "Failed to load lib$LIB_NAME.so: ${t.message}")
            }
        }

        /** RNNoise's hard-coded frame size: 240 samples @ 24 kHz (10 ms). */
        const val FRAME_SIZE: Int = 240
    }

    /** Allocates the native DenoiseState. Idempotent. */
    fun initialize() {
        ensureLibraryLoaded()
        loadError?.let { throw IllegalStateException("RNNoise native lib unavailable", it) }
        if (handle == 0L) {
            handle = nativeCreate()
            if (handle == 0L) throw IllegalStateException("rnnoise_create returned NULL")
        }
    }

    /**
     * Denoises a 480-sample int16 frame at 48 kHz mono.
     *
     * Returns a NEW [ShortArray] of size 480 with the cleaned samples.
     * If the input is shorter than 480 samples or the native state is not
     * initialized, returns the input unchanged.
     */
    fun processFrame(frame: ShortArray): ShortArray {
        if (handle == 0L || frame.size < FRAME_SIZE) return frame
        return nativeProcessFrame(handle, frame) ?: frame
    }

    /** Releases the native DenoiseState. Idempotent. */
    fun deinitialize() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    // --- JNI ---

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcessFrame(handle: Long, frame: ShortArray): ShortArray?
}

