package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.stardust.model.StardustAppEventPackage

object AppEvents {

    private const val SNR_EXPIRY_TIMEOUT_MS = 15_000L

    private val snrExpiryHandler = Handler(Looper.getMainLooper())
    private val snrLock = Any()

    // Bumped on every call; a delayed expiry only acts if its captured
    // generation still matches. Avoids the Handler.removeCallbacks TOCTOU
    // race, where a runnable already dequeued by the Looper can't be
    // cancelled but must still be prevented from firing on stale data.
    private var snrGeneration = 0
    private var pendingSnrExpiry: StardustAppEventPackage.RSSIPackage? = null

    /** Radio TX/RX events from arrival to the host callback; same tag the plugin uses. */
    internal const val TX_RX_TRACE = "TxRxTrace"

    /**
     * The "no host callbacks" drop logs once per run of drops; the run ends when callbacks are
     * back, which logs how many more were dropped. [droppingEvents] keeps that check to one
     * volatile read per delivered event.
     */
    private val logOnce = LogOnce()
    @Volatile private var droppingEvents = false

    fun updateAppEvents (bittelAppEventPackage: StardustAppEventPackage) {
        val callbacks = DataManager.getCallbacks()
        if (callbacks == null) {
            droppingEvents = true
            if (logOnce.first("no-callbacks")) {
                StardustLog.w(LogType.PACKETS, TX_RX_TRACE, "sdk event ${bittelAppEventPackage.eventType} xcvr=${bittelAppEventPackage.xcvr} DROPPED: no host callbacks")
            }
            return
        }
        if (droppingEvents) {
            droppingEvents = false
            val more = logOnce.reset()["no-callbacks"]
            if (more != null) StardustLog.w(LogType.PACKETS, TX_RX_TRACE, "$more more sdk events DROPPED: no host callbacks")
        }
        // Per-radio-event (TX/RX, signal reports): keep at d.
        StardustLog.d(LogType.PACKETS, TX_RX_TRACE) { "sdk event ${bittelAppEventPackage.eventType} xcvr=${bittelAppEventPackage.xcvr} -> host" }
        callbacks.onAppEvent(bittelAppEventPackage)
    }

    /**
     * Forwards [data] to the app callback. When [data.snr] is non-null, arms
     * a 15s expiry: if no further non-null-SNR update arrives within that
     * window, this is called again with `snr = null` (rssi/signalRssi kept
     * as last reported) so consumers know the reading has gone stale. Each
     * non-null-SNR call refreshes the 15s window.
     */
    fun updateRssiSignalChanged(data: StardustAppEventPackage.RSSIPackage) {
        synchronized(snrLock) {
            val generation = ++snrGeneration
            if (data.snr != null) {
                pendingSnrExpiry = data
                snrExpiryHandler.postDelayed({ expireSnr(generation) }, SNR_EXPIRY_TIMEOUT_MS)
            } else {
                pendingSnrExpiry = null
            }
        }
        DataManager.getCallbacks()?.onSignalRSSIChanged(data)
    }

    private fun expireSnr(expectedGeneration: Int) {
        synchronized(snrLock) {
            if (expectedGeneration != snrGeneration) return // superseded by a newer call
            pendingSnrExpiry ?: return
            pendingSnrExpiry = null
        }
        StardustLog.d(LogType.PACKETS, "AppEvent", "SNR expired after ${SNR_EXPIRY_TIMEOUT_MS}ms with no refresh")
        updateRssiSignalChanged(StardustAppEventPackage.RSSIPackage())
    }

    fun updateBattery (percent: Int) {
        DataManager.getCallbacks()?.onBatteryChanged(percent)
    }
}