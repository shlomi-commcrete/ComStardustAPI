package com.commcrete.stardust.util.connectivity


import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.transport.ConnectionManager
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
     * Consecutive silence windows: incremented every time [connectionTimeout] elapses with no
     * incoming package at all, reset by [onTrafficReceived].
     */
    private var silentWindows = 0

    /**
     * How many unanswered keepalive pings make the radio dead, on EITHER transport.
     *
     * This is the only detector for "the link is up and nothing is running behind it", and both
     * stacks need it:
     *  - **USB**: if the radio's battery dies or its MCU hangs while the FTDI bridge stays
     *    enumerated and bus-powered, the UART stays open and `isUSBConnected` stays true forever.
     *  - **BLE**: the LE supervision timeout only fires if the radio's *controller* stops answering.
     *    A radio whose application firmware hangs keeps the link alive at the controller level, so
     *    no GATT callback ever arrives.
     *
     * Either way the state sat on Syncing/Ready while the handshake was silently retried in a loop,
     * and the user saw a dead radio reported as connected.
     *
     * Three windows ≈ 30s. A window counts only TOTAL silence — ANY inbound traffic resets the
     * counter via [onTrafficReceived], a ping reply or otherwise — so a link that is carrying
     * anything at all (handshake traffic, an unsolicited event, a message) can never reach the
     * verdict, however long it goes without answering a ping specifically.
     */
    private const val DEAD_AFTER_UNANSWERED_PINGS = 3

    /**
     * Called for every inbound chunk from the radio, from
     * [com.commcrete.stardust.stardust.StardustPackageUtils.handlePackageReceived] — the single
     * entry point both transports feed. A ping reply is the usual proof of life, but it is not the
     * only one: any traffic at all counts, including packages that are not responses to anything we
     * sent, repeated frames, and bytes that do not complete a package.
     */
    fun onTrafficReceived() {
        silentWindows = 0
    }

    /**
     * Fired when a keepalive ping goes unanswered for [connectionTimeout]; [DEAD_AFTER_UNANSWERED_PINGS]
     * of these in a row is the dead-link verdict.
     *
     * The transport MUST be resolved at fire time. This used to be hard-coded to USB, which meant a
     * BLE session's missed ping asked for a *USB* reconnect: `UsbTransport.reconnect()` →
     * `initDataToUsb()` aborted immediately (no USB link), and because
     * [ConnectionManager.requestReconnect] is single-flight, that useless job occupied the slot and
     * the BLE reconnect that should have run was suppressed. Observed live firing every ~11–40 s
     * with no USB device attached at all.
     */
    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        // Nothing is connected — there is no link to probe or restore, and the entry points for a
        // fresh connection (host action / attach event / startup) own that case.
        val transport = TransportRegistry.active()?.id ?: run {
            Timber.tag("PortUtils").d("ping timeout with no active transport — nothing to reconnect")
            silentWindows = 0
            return@Runnable
        }
        silentWindows++

        // A terminal error is not fixed by reconnecting; retrying every 10s would just loop.
        if (StardustInitConnectionHandler.hasUnsyncableError()) {
            Timber.tag("PortUtils").d("ping timeout ignored — terminal handshake error, reconnect would loop")
            return@Runnable
        }

        if (silentWindows < DEAD_AFTER_UNANSWERED_PINGS) {
            Timber.tag("PortUtils").d(
                "unanswered ping $silentWindows/$DEAD_AFTER_UNANSWERED_PINGS over $transport"
            )
            return@Runnable
        }

        // Three windows of TOTAL silence: the link is up and nothing is answering on it. Declare it
        // down (honestly — the user is not shown a reconnect that has already been failing for 30s)
        // and let ConnectionManager restore it: re-probe the still-attached device on USB, redial a
        // paired radio on BLE.
        //
        // Deliberately NOT exempted while the init handshake is in flight. sendPing() no-ops during
        // a sync, so a radio that dies mid-handshake sends nothing and would otherwise never be
        // noticed; the counter is what makes that safe, since any incoming package — handshake
        // traffic included — resets it, so a slow-but-alive sync cannot reach this line.
        val silenceMs = silentWindows * connectionTimeout
        Timber.tag("PortUtils").w(
            "$DEAD_AFTER_UNANSWERED_PINGS unanswered pings over $transport (${silenceMs}ms of silence) — declaring the link dead"
        )
        android.util.Log.w("ConfigDebug",
            "$transport link declared dead after ${silenceMs}ms of silence — disconnecting and retrying")
        silentWindows = 0
        ConnectionManager.onKeepaliveLost(transport, "$DEAD_AFTER_UNANSWERED_PINGS unanswered pings (${silenceMs}ms)")
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
                // Armed whenever a transport is up, not only once the handshake has finished: a
                // radio that dies DURING the handshake sends no pings (sendPing no-ops while
                // syncing) and would otherwise never be noticed. The silence counter is what makes
                // this safe — incoming handshake traffic resets it, so a slow-but-alive sync is
                // never mistaken for a dead link.
                if (TransportRegistry.active() != null) resetConnectionTimer() else {
                    removeConnectionTimer()
                    silentWindows = 0
                }
                delay(connectionTimeout)
            }
        }
    }

    fun onPingReceived () {
        onTrafficReceived()
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