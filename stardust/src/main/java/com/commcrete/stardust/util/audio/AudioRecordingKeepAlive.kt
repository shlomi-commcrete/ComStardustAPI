package com.commcrete.stardust.util.audio

import android.content.Context
import android.os.PowerManager
import timber.log.Timber

/**
 * Shared partial wake-lock helper for recorder sessions.
 *
 * Keeps CPU active while audio capture / encoding is running so screen-off
 * does not suspend the JBOX/USB audio pipeline.
 */
internal object AudioRecordingKeepAlive {
    private const val TAG = "AudioRecordingKeepAlive"
    private const val WAKE_LOCK_TAG = "ComStardustAPI:AudioRecording"

    private var wakeLock: PowerManager.WakeLock? = null
    private var refCount: Int = 0

    @Synchronized
    fun acquire(context: Context) {
        refCount++
        if (wakeLock?.isHeld == true) return

        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: run {
            Timber.tag(TAG).w("PowerManager unavailable; cannot hold wake lock")
            refCount = maxOf(0, refCount - 1)
            return
        }

        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        Timber.tag(TAG).d("Wake lock acquired")
    }

    @Synchronized
    fun release() {
        if (refCount > 0) refCount--
        if (refCount > 0) return

        wakeLock?.let {
            if (it.isHeld) {
                runCatching { it.release() }
                Timber.tag(TAG).d("Wake lock released")
            }
        }
        wakeLock = null
        refCount = 0
    }

    @Synchronized
    fun reset() {
        wakeLock?.let {
            if (it.isHeld) runCatching { it.release() }
        }
        wakeLock = null
        refCount = 0
    }
}

