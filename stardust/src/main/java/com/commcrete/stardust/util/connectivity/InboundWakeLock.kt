package com.commcrete.stardust.util.connectivity

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.commcrete.stardust.util.StardustLog

/**
 * Keeps the CPU awake for a short window after anything arrives from the radio: a package, a
 * link-state change, a USB attach/detach, a PTT-button edge.
 *
 * The arrival itself wakes the CPU, but nothing keeps it awake afterwards. With the screen off the
 * phone can suspend halfway through reassembly, a database write or a host callback, and the
 * host's notification for that event is then posted at the next wake-up or not at all.
 *
 * In Doze the system honors this lock only for a process that is battery-allowlisted or runs a
 * foreground service. ATAK does both; a host that does neither gets no effect from it.
 *
 * Timed, never held open: each event re-arms the window and silence lets it lapse, so there is no
 * release to miss. Separate from AudioRecordingKeepAlive so neither can drop the other's lock.
 */
internal object InboundWakeLock {
    private const val TAG = "InboundWakeLock"
    private const val WAKE_LOCK_TAG = "ComStardustAPI:Inbound"

    /**
     * How long the CPU stays up after the last inbound event. Must outlast the reassembly timers in
     * StardustPackageUtils (3000ms and 2000ms), so a stale partial package is still cleared on time.
     */
    private const val HOLD_MS = 5_000L

    /**
     * Re-arming is a binder call into PowerManager and inbound PTT arrives many times a second, so
     * a re-arm is skipped while the window was armed less than this long ago.
     */
    private const val REARM_AFTER_MS = 1_000L

    private var wakeLock: PowerManager.WakeLock? = null
    private var armedAtMs = 0L
    private var failureLogged = false

    /** Opens or extends the window. Cheap enough to call per packet; safe from any thread. */
    @Synchronized
    fun onInbound(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val lock = wakeLock ?: run {
            // A core system service; absent only in a broken environment, with nothing to hold.
            val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: return
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        }
        if (lock.isHeld && now - armedAtMs < REARM_AFTER_MS) return
        armedAtMs = now
        try {
            lock.acquire(HOLD_MS)
        } catch (e: RuntimeException) {
            // Silence is the right outcome for the caller: this runs on the receive path, and a
            // missing lock costs screen-off reliability, never the package. Logged once — this
            // is called per packet. SecurityException if the host process lacks WAKE_LOCK.
            if (!failureLogged) {
                failureLogged = true
                StardustLog.w(TAG, "Inbound wake lock unavailable; screen-off receive may stall", e)
            }
        }
    }
}
