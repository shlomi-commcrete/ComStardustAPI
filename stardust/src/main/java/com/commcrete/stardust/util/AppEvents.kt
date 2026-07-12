package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import timber.log.Timber

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

    fun updateAppEvents (bittelAppEventPackage: StardustAppEventPackage) {
        DataManager.getCallbacks()?.onAppEvent(bittelAppEventPackage)
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
        Timber.tag("AppEvent").d("SNR expired after ${SNR_EXPIRY_TIMEOUT_MS}ms with no refresh")
        updateRssiSignalChanged(StardustAppEventPackage.RSSIPackage())
    }

    fun updateBattery (percent: Int) {
        DataManager.getCallbacks()?.onBatteryChanged(percent)
    }
}