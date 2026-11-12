package com.commcrete.stardust.audio.v2.framework

import android.media.AudioManager
import com.commcrete.stardust.PttRecordingError
import org.junit.Assert.assertEquals
import org.junit.Test

class MicSilenceWatchTest {

    private fun cause(mode: Int, onScreen: Boolean, backgroundCanMute: Boolean = true) =
        MicSilenceWatch.causeOf(mode, onScreen, backgroundCanMute)

    @Test
    fun `off screen with no call is the background mute`() {
        assertEquals(PttRecordingError.MIC_MUTED_IN_BACKGROUND, cause(AudioManager.MODE_NORMAL, onScreen = false))
    }

    @Test
    fun `a phone call wins over the background`() {
        assertEquals(PttRecordingError.MIC_TAKEN_BY_CALL, cause(AudioManager.MODE_IN_CALL, onScreen = false))
    }

    @Test
    fun `a VoIP call and call screening are calls too`() {
        assertEquals(PttRecordingError.MIC_TAKEN_BY_CALL, cause(AudioManager.MODE_IN_COMMUNICATION, onScreen = true))
        assertEquals(PttRecordingError.MIC_TAKEN_BY_CALL, cause(4 /* MODE_CALL_SCREENING */, onScreen = true))
    }

    @Test
    fun `muted on screen with no call is blocked access`() {
        assertEquals(PttRecordingError.MIC_ACCESS_BLOCKED, cause(AudioManager.MODE_NORMAL, onScreen = true))
    }

    @Test
    fun `a process with a microphone service is never muted for being in the background`() {
        assertEquals(
            PttRecordingError.MIC_ACCESS_BLOCKED,
            cause(AudioManager.MODE_NORMAL, onScreen = false, backgroundCanMute = false),
        )
    }

    @Test
    fun `ringing is not a call`() {
        assertEquals(PttRecordingError.MIC_ACCESS_BLOCKED, cause(AudioManager.MODE_RINGTONE, onScreen = true))
    }
}
