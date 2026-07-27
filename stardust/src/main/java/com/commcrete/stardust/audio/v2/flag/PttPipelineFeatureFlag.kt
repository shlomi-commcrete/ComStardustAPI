package com.commcrete.stardust.audio.v2.flag

import android.content.Context

/**
 * The single switch that routes PTT through the v2 pipeline instead of the legacy path.
 *
 * Default is **false** — with the flag off, `RecorderUtils` and `StardustPackageHandler` behave
 * exactly as before and none of the v2 code runs, so shipping this delegation is a no-op in
 * production until someone opts in (staging / QA / a dev toggle).
 *
 * Scope note: only the CODEC2 path is wired to v2 today. The AI (WavTokenizer) path stays on the
 * legacy code regardless of this flag until its adapter lands.
 */
object PttPipelineFeatureFlag {

    private const val PREFS = "ptt_v2_flags"
    private const val KEY_ENABLED = "ptt_pipeline_v2_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
