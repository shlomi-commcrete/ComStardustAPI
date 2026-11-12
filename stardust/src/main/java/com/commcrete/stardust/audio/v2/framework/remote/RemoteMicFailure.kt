package com.commcrete.stardust.audio.v2.framework.remote

import com.commcrete.stardust.PttRecordingError

/**
 * Wire codes for IRemoteMic.lastFailure. Explicit numbers, never ordinals: they cross a process boundary.
 * [toError] and [toCode] must stay inverse for every mic error.
 */
internal object RemoteMicFailure {
    const val NONE = 0
    const val MIC_UNAVAILABLE = 1
    const val MIC_TAKEN_BY_CALL = 2
    const val MIC_ACCESS_BLOCKED = 3
    const val MIC_MUTED_IN_BACKGROUND = 4

    /** The caller is not an ATAK package. */
    const val CALLER_REJECTED = 10

    /** The service's package has no RECORD_AUDIO grant. */
    const val NO_PERMISSION = 11

    fun toCode(error: PttRecordingError): Int = when (error) {
        PttRecordingError.MIC_TAKEN_BY_CALL -> MIC_TAKEN_BY_CALL
        PttRecordingError.MIC_ACCESS_BLOCKED -> MIC_ACCESS_BLOCKED
        PttRecordingError.MIC_MUTED_IN_BACKGROUND -> MIC_MUTED_IN_BACKGROUND
        else -> MIC_UNAVAILABLE
    }

    /** What the host is told; anything that is not a mute reads as an unavailable microphone. */
    fun toError(code: Int): PttRecordingError = when (code) {
        MIC_TAKEN_BY_CALL -> PttRecordingError.MIC_TAKEN_BY_CALL
        MIC_ACCESS_BLOCKED -> PttRecordingError.MIC_ACCESS_BLOCKED
        MIC_MUTED_IN_BACKGROUND -> PttRecordingError.MIC_MUTED_IN_BACKGROUND
        else -> PttRecordingError.MIC_UNAVAILABLE
    }

    fun isMute(code: Int): Boolean =
        code == MIC_TAKEN_BY_CALL || code == MIC_ACCESS_BLOCKED || code == MIC_MUTED_IN_BACKGROUND
}
