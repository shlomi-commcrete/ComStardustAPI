package com.commcrete.stardust.transport

import android.os.PowerManager
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.StardustLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the CPU awake for as long as a radio link is up, or a reconnect is under way.
 *
 * Every timer the link depends on (the PortUtils keepalive ping and port-mode loops, the ping
 * timeout, reassembly and file-gap timers, the direct-connect escalation) is a `delay` or a
 * `postDelayed`, and both stop while the CPU is suspended. With the screen off that stalled the
 * keepalive and left transfers half-settled until the next wake-up. One lock across the whole link
 * lifetime covers all of them, and every receive and send path, without each holding its own.
 *
 * Cost: the CPU never suspends while a radio is connected, and that is billed to the host's UID.
 * Honored in Doze only because the host (ATAK) is battery-allowlisted and runs a foreground service.
 *
 * Driven by [ConnectionManager.recompute], the single place connection state changes, and by login
 * and logout (SharedPreferencesUtil.setAppUser / removeAppUser), which change no link. The lock is
 * timed and renewed rather than held open, and each renewal re-checks the link itself, so a
 * transition that never reaches recompute cannot keep the CPU up for longer than [HOLD_MS].
 * Separate from InboundWakeLock and AudioRecordingKeepAlive so none can drop another's lock.
 */
internal object LinkWakeLock {
    private const val TAG = "LinkWakeLock"
    private const val WAKE_LOCK_TAG = "ComStardustAPI:Link"

    /** Ceiling on how long the lock outlives a missed release. */
    private const val HOLD_MS = 10 * 60_000L

    /** Renewed well inside [HOLD_MS], so a slow renewal never lets the lock lapse mid-link. */
    private const val RENEW_MS = 4 * 60_000L

    private var wakeLock: PowerManager.WakeLock? = null
    private var renewJob: Job? = null

    /**
     * A user is logged in, and a transport carries a link or the state machine is still trying to
     * form one: the post-drop reconnect grace, or a scan — both bounded (30s each).
     *
     * Logged in, because a logged-out user can neither send nor receive: a USB radio left plugged
     * in after logout keeps its link up with nothing to keep awake for. Read from the physical link,
     * not the public state, because [ConnectionState.Error] is published with the link still up.
     */
    private fun linkWanted(): Boolean =
        RegisteredUserUtils.isUserLoggedIn() && (
            TransportRegistry.active() != null ||
                ConnectionManager.connectionState.value == ConnectionState.Searching
            )

    /** Takes or drops the lock to match the link. Idempotent; called on every state recompute. */
    @Synchronized
    fun refresh() {
        if (linkWanted()) hold() else release()
    }

    @Synchronized
    private fun hold() {
        if (wakeLock?.isHeld == true) return
        val lock = wakeLock ?: run {
            // A core system service; absent only in a broken environment, with nothing to hold.
            val pm = DataManager.appContext.getSystemService(PowerManager::class.java) ?: return
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        }
        try {
            lock.acquire(HOLD_MS)
        } catch (e: RuntimeException) {
            // Not returned to anyone: the link works without the lock, it only stalls with the
            // screen off. SecurityException if the host process lacks WAKE_LOCK.
            StardustLog.w(TAG, "Link wake lock unavailable; screen-off keepalive will stall", e)
            return
        }
        StardustLog.d(TAG, "Link wake lock held")
        renewJob?.cancel()
        renewJob = Scopes.getDefaultCoroutine().launch {
            while (isActive) {
                delay(RENEW_MS)
                renew()
            }
        }
    }

    @Synchronized
    private fun renew() {
        if (!linkWanted()) {
            release()
            return
        }
        runCatching { wakeLock?.acquire(HOLD_MS) }
    }

    /** Drops the lock. Idempotent. */
    @Synchronized
    fun release() {
        renewJob?.cancel()
        renewJob = null
        wakeLock?.let {
            if (it.isHeld) {
                runCatching { it.release() }
                StardustLog.d(TAG, "Link wake lock released")
            }
        }
    }
}
