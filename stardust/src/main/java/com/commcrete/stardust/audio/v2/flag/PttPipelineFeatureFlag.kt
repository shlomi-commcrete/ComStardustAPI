package com.commcrete.stardust.audio.v2.flag

import android.content.Context

/**
 * The single switch that routes PTT through the v2 pipeline instead of the legacy path.
 *
 * Default is **true** — v2 is the live pipeline for BOTH codecs. Calling `setEnabled(context, false)`
 * puts `RecorderUtils` and `StardustPackageHandler` back on the legacy path (`AudioRecorderCodec2` /
 * `PttSendManager` / `PlayerUtils`), which is the fallback if a v2 regression shows up in the field.
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
