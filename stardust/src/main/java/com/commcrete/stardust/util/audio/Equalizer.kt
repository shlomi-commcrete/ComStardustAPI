package com.commcrete.stardust.util.audio

import android.content.Context
import android.media.audiofx.Equalizer
import com.commcrete.stardust.util.SharedPreferencesUtil

class Equalizer {

    fun getEq(audioSessionId: Int, context: Context): Equalizer {
        return Equalizer(0, audioSessionId).apply {
            enabled = true
            setBandLevel(0, SharedPreferencesUtil.getEqBand(0).toShort())
            setBandLevel(1, SharedPreferencesUtil.getEqBand(1).toShort())
            setBandLevel(2, SharedPreferencesUtil.getEqBand(2).toShort())
            setBandLevel(3, SharedPreferencesUtil.getEqBand(3).toShort())
            setBandLevel(4, SharedPreferencesUtil.getEqBand(4).toShort())
        }
    }
}