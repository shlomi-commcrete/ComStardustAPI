package com.commcrete.stardust.audio.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [Gain.boostMb], the one number both playback sinks hand their LoudnessEnhancer: nothing at or
 * below unity (that part is `AudioTrack.setVolume`'s), and exactly `2000·log10` above it.
 */
class GainTest {

    @Test
    fun noBoost_atOrBelowUnity() {
        assertEquals(0, Gain.MUTE.boostMb())
        assertEquals(0, Gain(0.5f).boostMb())
        assertEquals(0, Gain.UNITY.boostMb())
    }

    @Test
    fun boost_isTwentyLog10InMillibels() {
        // ×2 amplitude is +6.02 dB.
        assertEquals(602, Gain(2f).boostMb())
        // ×10 amplitude is +20 dB.
        assertEquals(2000, Gain(10f).boostMb())
    }
}
