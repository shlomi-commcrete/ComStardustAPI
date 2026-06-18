package com.commcrete.stardust.audio.v2.flag

import android.content.Context
import android.content.SharedPreferences

/**
 * Single switch the SDK consumer flips to route PTT through the v2
 * pipeline instead of the legacy [com.commcrete.stardust.ai.codec.PttSendManager]
 * + [com.commcrete.stardust.util.audio.AudioRecorderCodec2] paths.
 *
 * # Why a runtime flag and not a build flag
 *
 *  - Lets QA toggle per device for A/B comparison without rebuilds.
 *  - Lets the SDK fall back to v1 in production while v2 bakes.
 *  - Lets the host app expose a developer-options toggle.
 *
 * # Wire-up (NOT yet applied — see v2 README)
 *
 *  - In `RecorderUtils.startRecording(...)`:
 *    ```kotlin
 *    if (PttPipelineFeatureFlag.isEnabled(ctx)) {
 *        // build CaptureSource, BleSendTransport, WavLocalMirror, profileV2
 *        PttSendCoordinator.restart(...)
 *    } else {
 *        // existing AudioRecorderAI / AudioRecorderCodec2 path
 *    }
 *    ```
 *  - In `StardustPackageHandler.handlePTT` / `handlePTTAI`:
 *    ```kotlin
 *    if (PttPipelineFeatureFlag.isEnabled(ctx) &&
 *        PttReceiveCoordinator.onPacket(pkg)) return
 *    // existing v1 dispatch
 *    ```
 *
 * # Default
 *
 *  - **`false`** until v2 is fully bench-tested. Flip via
 *    [setEnabled] (e.g. from a settings screen or instrumented test).
 *
 * # Storage
 *
 * Uses a dedicated prefs file (`v2_audio_pipeline`) instead of the
 * default shared preferences so the toggle is isolated from
 * everything else in the SDK and can be wiped independently for QA.
 */
object PttPipelineFeatureFlag {

    private const val PREFS_NAME = "v2_audio_pipeline"
    private const val PREFS_KEY = "ptt_pipeline_v2_enabled"

    /** Last value passed to [setEnabled] — cached so the hot path doesn't hit SharedPreferences. */
    @Volatile private var cached: Boolean? = null

    /**
     * Whether the v2 pipeline is currently selected. Reads
     * [SharedPreferences] on first call, caches thereafter.
     */
    fun isEnabled(context: Context): Boolean {
        cached?.let { return it }
        val v = prefs(context).getBoolean(PREFS_KEY, false)
        cached = v
        return v
    }

    /**
     * Toggle the flag. Persists immediately and updates the cache so
     * subsequent [isEnabled] calls reflect the new value without
     * re-reading prefs.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREFS_KEY, enabled).apply()
        cached = enabled
    }

    /** Drop the cache so the next [isEnabled] re-reads prefs. Test-only. */
    internal fun invalidateForTest() {
        cached = null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}


