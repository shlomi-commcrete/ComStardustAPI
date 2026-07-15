package com.commcrete.stardust.transport

import android.bluetooth.BluetoothAdapter
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.State
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private const val WATCHDOG_INTERVAL_MS = 10_000L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var lastInitState: State = State.DISCONNECTED

    private var reconnectJob: Job? = null
    private var attempt = 0

    // Whether the app WANTS a BLE link kept alive. Set once a BLE link is established; cleared only
    // on an intentional teardown (manual disconnect / unpair via [disableAutoReconnect]). This is
    // what distinguishes an unexpected drop (device died / out of range → reconnect) from a
    // deliberate disconnect (→ stay down), since both otherwise look like Disconnected+paired.
    @Volatile
    private var autoReconnectDesired = false
    private var watchdogJob: Job? = null

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

        // Once a BLE link is established, arm the auto-reconnect watchdog. We only do this for BLE
        // (USB has its own attach-driven lifecycle) and never for a USB-active state.
        if (isBleConnectedState(next)) {
            autoReconnectDesired = true
            startReconnectWatchdog()
        }
    }

    private fun isBleConnectedState(state: ConnectionState): Boolean = when (state) {
        is ConnectionState.LinkUp -> state.transport == TransportId.BLE
        is ConnectionState.Syncing -> state.transport == TransportId.BLE
        is ConnectionState.Ready -> state.transport == TransportId.BLE
        else -> false
    }

    private fun derive(active: TransportId?, s: State): ConnectionState = when {
        s == State.BLUETOOTH_OFF -> ConnectionState.BluetoothOff
        // Terminal errors are surfaced regardless of transport presence — evaluated before the
        // active==null gate so a failure isn't lost as plain Disconnected when the link drops.
        s == State.CANCELED || StardustInitConnectionHandler.hasUnsyncableError() ->
            ConnectionState.Error(toConnectionError(s))
        active == null -> if (s == State.SEARCHING) ConnectionState.Searching else ConnectionState.Disconnected
        s == State.SUCCESS -> ConnectionState.Ready(active)
        StardustInitConnectionHandler.isSyncing() -> ConnectionState.Syncing(active)
        else -> ConnectionState.LinkUp(active)
    }

    /** Maps the internal handshake state to the stable public [ConnectionError]. */
    private fun toConnectionError(s: State): ConnectionError = when (s) {
        State.NO_LICENSE -> ConnectionError.NoLicense
        State.ENCRYPTION_KEY_ERROR -> ConnectionError.EncryptionKey
        State.PRESET_ERROR -> ConnectionError.Preset
        State.CANCELED -> ConnectionError.Canceled
        else -> ConnectionError.Unknown
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

    // ───────────────────────── Auto-reconnect watchdog ─────────────────────────

    /**
     * Periodically restores a BLE link that dropped unexpectedly (e.g. the radio's battery died and
     * it was later switched back on), giving a deterministic fallback for when Android's autoConnect
     * doesn't fire. It deliberately does NOT fire for intentional GATT tear-downs:
     *  - manual disconnect / unpair → [autoReconnectDesired] is cleared (see [disableAutoReconnect]),
     *    and unpair also drops [BleManager.isPaired];
     *  - USB takeover → guarded by `!isUSBConnected` (we don't fight USB while it's connected);
     *  - Bluetooth off → the state is [ConnectionState.BluetoothOff], not [ConnectionState.Disconnected],
     *    and the adapter-state observer handles re-connect when BT returns.
     * The actual reconnect goes through [requestReconnect], so its single-flight + backoff throttle
     * repeated attempts.
     */
    @Synchronized
    private fun startReconnectWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = Scopes.getDefaultCoroutine().launch {
            while (isActive && autoReconnectDesired) {
                delay(WATCHDOG_INTERVAL_MS)
                if (shouldAutoReconnect()) {
                    requestReconnect(TransportId.BLE, "auto-reconnect watchdog")
                }
            }
        }
    }

    private fun shouldAutoReconnect(): Boolean =
        autoReconnectDesired &&
            BleManager.isPaired.value == true &&
            !BleManager.isUSBConnected &&
            isBluetoothOn() &&
            _connectionState.value is ConnectionState.Disconnected

    /**
     * False when the adapter is off or absent. Pauses auto-reconnect while Bluetooth is disabled —
     * there's no point retrying until it's back on. Re-enabling BT is handled by the adapter-state
     * observer in ClientConnection (which reconnects), and the watchdog resumes once that lands.
     */
    private fun isBluetoothOn(): Boolean =
        @Suppress("DEPRECATION") (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true)

    /**
     * Disables auto-reconnect and stops the watchdog. Call this on INTENTIONAL disconnects (the user
     * tapped disconnect, or unpaired) so we don't immediately fight the tear-down. Battery-death /
     * out-of-range drops do NOT call this, so those still auto-reconnect.
     */
    @Synchronized
    fun disableAutoReconnect() {
        autoReconnectDesired = false
        watchdogJob?.cancel()
        watchdogJob = null
        reconnectJob?.cancel()
        reconnectJob = null
    }
}
