package com.commcrete.stardust.transport

import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.State
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Stage 3 of the connection refactor. Owns two things that used to be scattered across the codebase:
 *
 * 1. A single [connectionState] StateFlow — the one source of truth for "where is the connection",
 *    derived from the transport link flags and the [StardustInitConnectionHandler] state. It is fed
 *    by two one-line hooks: [onTransportChanged] (from `BleManager.updateStatus`) and
 *    [onInitStateChanged] (from the init-handler state setter).
 *
 * 2. A single reconnection policy ([requestReconnect]) with single-flight + exponential backoff,
 *    replacing the independent reconnect triggers that could fire concurrently (the BLE connection
 *    watchdog and the USB ping-timeout watchdog). Keepalive pings are unchanged; only the reconnect
 *    DECISION is centralised here.
 */
object ConnectionManager {

    private val TAG = ConnectionManager::class.java.simpleName

    private const val BASE_DELAY_MS = 1500L
    private const val MAX_DELAY_MS = 30_000L
    private const val MAX_BACKOFF_SHIFT = 20

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var lastInitState: State = State.DISCONNECTED

    private var reconnectJob: Job? = null
    private var attempt = 0

    // ───────────────────────── State derivation ─────────────────────────

    /** Called from `BleManager.updateStatus()` whenever a transport link flips. */
    fun onTransportChanged() = recompute()

    /** Called from the init-handler state setter whenever the handshake state changes. */
    fun onInitStateChanged(state: State) {
        lastInitState = state
        recompute()
    }

    private fun recompute() {
        val active = TransportRegistry.active()?.id
        val next = derive(active, lastInitState)
        _connectionState.value = next

        // Reaching a connected state means any pending reconnect is moot and backoff can reset.
        if (next is ConnectionState.LinkUp || next is ConnectionState.Syncing || next is ConnectionState.Ready) {
            reconnectJob?.cancel()
            reconnectJob = null
            attempt = 0
        }
    }

    private fun derive(active: TransportId?, s: State): ConnectionState = when {
        s == State.BLUETOOTH_OFF -> ConnectionState.BluetoothOff
        active == null -> if (s == State.SEARCHING) ConnectionState.Searching else ConnectionState.Disconnected
        s == State.SUCCESS -> ConnectionState.Ready(active)
        s == State.CANCELED || StardustInitConnectionHandler.hasUnsyncableError() -> ConnectionState.Failed(s)
        StardustInitConnectionHandler.isSyncing() -> ConnectionState.Syncing(active, s)
        else -> ConnectionState.LinkUp(active)
    }

    // ───────────────────────── Reconnection policy ─────────────────────────

    /**
     * Requests a reconnect over [transport] (each watchdog knows its own transport). Single-flight:
     * ignored if a reconnect is already scheduled/running, which is what collapses the previously
     * independent BLE and USB reconnect triggers into one coordinated policy. Backoff is exponential
     * (0ms on the first attempt to preserve prior immediacy, then 1.5s, 3s, 6s … capped at 30s) and
     * resets once a connected state is reached.
     */
    fun requestReconnect(transport: TransportId, reason: String) {
        if (reconnectJob?.isActive == true) {
            Timber.tag(TAG).d("reconnect already in progress; ignoring request ($reason)")
            return
        }
        val delayMs = backoffDelay()
        Timber.tag(TAG).d("scheduling reconnect over $transport in ${delayMs}ms (attempt ${attempt + 1}, reason=$reason)")

        reconnectJob = Scopes.getDefaultCoroutine().launch {
            if (delayMs > 0) delay(delayMs)
            attempt++
            try {
                TransportRegistry.of(transport).reconnect()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "reconnect over $transport failed")
            }
        }
    }

    private fun backoffDelay(): Long {
        if (attempt == 0) return 0L
        val shift = (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}
