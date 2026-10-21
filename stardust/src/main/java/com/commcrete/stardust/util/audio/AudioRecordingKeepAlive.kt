package com.commcrete.stardust.util.audio

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared partial wake-lock helper for recorder sessions.
 *
 * Keeps CPU active while audio capture / encoding is running so screen-off
 * does not suspend the JBOX/USB audio pipeline.
 *
 * Every [acquire] returns its own [Hold], and the lock is held while any hold is
 * outstanding. A bare counter let one holder's extra release() drop the lock out
 * from under another (the legacy codec2 stop path could run twice when key-up
 * raced the max-PTT stop), so a holder can now only ever release itself.
 */
internal object AudioRecordingKeepAlive {
    private const val TAG = "AudioRecordingKeepAlive"
    private const val WAKE_LOCK_TAG = "ComStardustAPI:AudioRecording"

    /** One holder's claim on the wake lock. [release] is idempotent. */
    class Hold internal constructor() {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) drop(this)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val holds = HashSet<Hold>()

    @Synchronized
    fun acquire(context: Context): Hold {
        val hold = Hold()
        holds += hold
        if (wakeLock?.isHeld == true) return hold

        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: run {
            Log.w(TAG, "PowerManager unavailable; cannot hold wake lock")
            return hold
        }

        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "Wake lock acquired")
        return hold
    }

    @Synchronized
    private fun drop(hold: Hold) {
        // Absent after a reset(): that hold's claim was already cleared.
        if (!holds.remove(hold)) return
        if (holds.isEmpty()) releaseLock()
    }

    /** Releases the lock regardless of outstanding holds; their later release() is a no-op. */
    @Synchronized
    fun reset() {
        holds.clear()
        releaseLock()
    }

    private fun releaseLock() {
        wakeLock?.let {
            if (it.isHeld) {
                runCatching { it.release() }
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
}
