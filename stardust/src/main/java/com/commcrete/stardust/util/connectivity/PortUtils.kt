package com.commcrete.stardust.util.connectivity


import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.transport.ConnectionManager
import com.commcrete.stardust.transport.TransportId
import com.commcrete.stardust.transport.TransportRegistry
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

object PortUtils {
    private var job: Job? = null
    private var jobPing: Job? = null

    private val handler : Handler = Handler(Looper.getMainLooper())
    private val connectionTimeout = 10000L
    /**
     * Fired when a keepalive ping goes unanswered for [connectionTimeout].
     *
     * The transport MUST be resolved at fire time. This used to be hard-coded to
     * [TransportId.USB], which meant a BLE session's missed ping asked for a *USB* reconnect:
     * `UsbTransport.reconnect()` → `initDataToUsb()` aborted immediately (no USB link), and because
     * [ConnectionManager.requestReconnect] is single-flight, that useless job occupied the slot and
     * the BLE reconnect that should have run was suppressed. Observed live firing every ~11–40 s
     * with no USB device attached at all.
     */
    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        val transport = TransportRegistry.active()?.id
        when {
            // Nothing is connected — there is no link to restore, and the entry points for a fresh
            // connection (host action / attach event / startup) own that case.
            transport == null ->
                Timber.tag("PortUtils").d("ping timeout with no active transport — nothing to reconnect")

            // Never interrupt an in-flight handshake. sendPing() no-ops while syncing, so the timer
            // would always expire mid-sync; with the transport now resolved correctly, firing here
            // would force-disconnect a BLE session that is still legitimately negotiating. (The old
            // hard-coded USB target made this harmless by accident.)
            StardustInitConnectionHandler.isSyncing() ->
                Timber.tag("PortUtils").d("ping timeout ignored — init handshake in progress over $transport")

            // A terminal error is not fixed by reconnecting; retrying every 10s would just loop.
            StardustInitConnectionHandler.hasUnsyncableError() ->
                Timber.tag("PortUtils").d("ping timeout ignored — terminal handshake error, reconnect would loop")

            else -> ConnectionManager.requestReconnect(transport, "$transport ping timeout")
        }
    }

    /**
     * Starts the periodic port-mode assertion and the keepalive ping.
     *
     * **Idempotent.** Both jobs are cancelled before being replaced: the previous version assigned
     * straight to [job] / [jobPing], so a second call (host `onResume`, a re-login, a reconnect)
     * started a second pair of loops and orphaned the first — the overwritten references could no
     * longer be cancelled, so [stopUpdatingPort] only ever stopped the newest pair while the older
     * ones kept sending `UPDATE_UART_PORT` every 20 s and a ping every 10 s for the life of the
     * process, doubling with every extra call.
     *
     * Note the port-mode `when` below has no `else`: with nothing connected it sends NOTHING. The
     * only cost while disconnected is the two timer wakeups, not radio traffic.
     */
    fun startUpdatingPort() {
        job?.cancel()
        jobPing?.cancel()
        job = Scopes.getMainCoroutine().launch {
            while (isActive) {
                // BLE and USB are mutually exclusive — pick the transport-specific port-mode
                // command explicitly rather than routing through a shared interface that used to
                // silently misdispatch (see BittelProtocol KDoc).
                when {
                    BleManager.isUsbEnabled() -> {
                        BittelUsbManager2.setUsbPortModeOnRadio()
                        Timber.tag("startUpdatingPort").d("USB active → setUsbPortModeOnRadio")
                    }
                    BleManager.isBluetoothConnected() -> {
                        DataManager.getClientConnection().setBlePortModeOnRadio()
                        Timber.tag("startUpdatingPort").d("BLE active → setBlePortModeOnRadio")
                    }
                }
                delay(20000)
            }
        }

        jobPing = Scopes.getDefaultCoroutine().launch {
            while (isActive) {
                DataManager.getClientConnection().sendPing()
                // Arm the timeout only when a ping could actually have gone out: sendPing() itself
                // returns early unless the handshake reached a connected/terminal state. Arming it
                // unconditionally is why the timeout fired repeatedly with nothing connected at all
                // (observed every ~11-40s in the 2026-08-26 capture).
                if (StardustInitConnectionHandler.isConnected()) resetConnectionTimer() else removeConnectionTimer()
                delay(connectionTimeout)
            }
        }
    }

    fun onPingReceived () {
        removeConnectionTimer()
//        DataManager.getUsbManager(DataManager.context).resetReconnect()
    }

    private fun resetConnectionTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, connectionTimeout)
    }

    fun removeConnectionTimer() {
        try {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        } catch (e : Exception) {
            e.printStackTrace()
        }
    }

    fun stopUpdatingPort() {
        job?.cancel() // Cancels the coroutine
        jobPing?.cancel() // Cancels the coroutine
        job = null
        jobPing = null
        // Also drop a pending ping-timeout, otherwise it can fire (and request a reconnect) after
        // the host has explicitly stopped the keepalive.
        removeConnectionTimer()
    }
}