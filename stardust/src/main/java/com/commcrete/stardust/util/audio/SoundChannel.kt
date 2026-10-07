package com.commcrete.stardust.util.audio

import android.media.AudioAttributes

/**
 * Which phone volume slider a [SoundPlayer] sound follows. Android has no per-app slider: the
 * [AudioAttributes] usage picks an existing stream, and that stream's volume applies.
 *
 * - [MEDIA]: media volume. Plays in silent mode.
 * - [SYSTEM]: system sounds. On phones the system stream is tied to the ringer, so it follows the
 *   ring volume and is silenced by silent/vibrate mode.
 * - [NOTIFICATION]: notification volume. Silenced by silent mode and Do Not Disturb.
 * - [ALARM]: alarm volume. Plays through silent mode and Do Not Disturb — meant for SOS.
 */
enum class SoundChannel(val usage: Int, val contentType: Int) {
    MEDIA(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC),
    SYSTEM(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AudioAttributes.CONTENT_TYPE_SONIFICATION),
    NOTIFICATION(AudioAttributes.USAGE_NOTIFICATION_EVENT, AudioAttributes.CONTENT_TYPE_SONIFICATION),
    ALARM(AudioAttributes.USAGE_ALARM, AudioAttributes.CONTENT_TYPE_SONIFICATION);

    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(contentType)
        .build()
}
