package com.commcrete.stardust.transport

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.PairingRepository
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.State
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

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
    /**
     * How often the auto-reconnect watchdog re-checks. Deliberately slow, because it is a safety
     * net rather than the primary recovery path: a failed direct connect escalates to Android's
     * background connect (`autoConnect = true`), which attaches by itself the moment the radio
     * starts advertising. Each accepted tick begins with a force-disconnect, so a short interval
     * destroys that pending background connect over and over — the thing most likely to succeed.
     *
     * Ticking rarely therefore improves recovery as well as battery. The cost is only how long a
     * radio that the background connect somehow misses stays unnoticed.
     */
    private const val WATCHDOG_INTERVAL_MS = 60_000L

    /**
     * How long after an unexpected drop the state keeps reading [ConnectionState.Searching] instead
     * of [ConnectionState.Disconnected], while auto-reconnect works on it.
     *
     * A radio that is power-cycled reliably costs one failed attempt: its controller accepts the LE
     * connection while the application firmware is still booting, serves discovery and the CCCD
     * write, then stops responding — and the link dies on a ~5s supervision timeout (captured
     * 2026-09-15 17:57:53: `on_le_disconnect Reason : 8` / `GATT_CONN_TIMEOUT`). The next attempt
     * connects and syncs in ~2s. Publishing `Disconnected` across that window is what makes a
     * self-healing blip look to the user like a failed reconnect. A sustained outage still falls
     * through to `Disconnected` once the grace expires.
     */
    private const val RECONNECT_GRACE_MS = 30_000L

    /**
     * How long an accepted reconnect request is assumed to still be working before another is
     * allowed. [requestReconnect]'s single-flight guard cannot do this on its own: `reconnect()`
     * returns as soon as the attempt is *launched*, so its job completes in microseconds and every
     * later request passes the guard. Without this window, the drop-triggered retry, the 10s
     * watchdog and the ping-timeout watchdog each start an attempt that force-disconnects the one
     * before it, and nothing is ever given time to land.
     *
     * Sized for the slowest legitimate attempt: 2s reconnect delay + up to 6s direct connect +
     * margin. It is a ceiling, not a schedule — a successful connect clears it immediately.
     */
    private const val RECONNECT_ATTEMPT_WINDOW_MS = 15_000L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var lastInitState: State = State.DISCONNECTED

    // reconnectJob is only ever read/assigned inside @Synchronized methods (recompute /
    // requestReconnect / disableAutoReconnect) so its check-then-assign is atomic. attempt is
    // AtomicInteger because it is also incremented from the launched reconnect coroutine, outside
    // any monitor.
    private var reconnectJob: Job? = null
    private val attempt = AtomicInteger(0)

    // Whether the app WANTS a BLE link kept alive. Set once a BLE link is established; cleared only
    // on an intentional teardown (manual disconnect / unpair via [disableAutoReconnect]). This is
    // what distinguishes an unexpected drop (device died / out of range → reconnect) from a
    // deliberate disconnect (→ stay down), since both otherwise look like Disconnected+paired.
    @Volatile
    private var autoReconnectDesired = false
    private var watchdogJob: Job? = null

    /**
     * Set by [disableAutoReconnect], cleared by [allowAutoConnect]: "the user intentionally
     * disconnected or unpaired, and has not asked to connect since".
     *
     * Distinct from [autoReconnectDesired], which only becomes true once a BLE session has actually
     * reached Syncing/Ready. That makes it useless as a gate for the paths that connect *without* a
     * prior session — the Bluetooth-on observer being the important one, since gating it on
     * autoReconnectDesired would break "Bluetooth was off at startup and comes on later".
     *
     * This flag is what stops a disconnect from being quietly undone by the 20s connection watchdog
     * or by any ACTION_STATE_CHANGED broadcast that happens to arrive afterwards.
     */
    @Volatile
    private var autoConnectSuppressed = false

    /** Whether automatic BLE connects are currently suppressed by an intentional disconnect. */
    fun isAutoConnectSuppressed(): Boolean = autoConnectSuppressed

    /**
     * Lifts the suppression. Called from every entry point that represents the user (or the host on
     * their behalf) asking to connect: startup bonding, connect/adopt, manual reconnect.
     */
    fun allowAutoConnect() {
        if (autoConnectSuppressed) Timber.tag(TAG).d("auto-connect suppression lifted")
        autoConnectSuppressed = false
    }

    /** Deadline (elapsedRealtime) until which a link-down reads as [ConnectionState.Searching]. */
    @Volatile
    private var reconnectGraceUntilMs = 0L

    /** Deadline until which an accepted reconnect counts as still in flight. */
    @Volatile
    private var attemptInFlightUntilMs = 0L

    /**
     * Republishes the state the moment the grace window lapses. Without it the expiry is only
     * noticed on the next watchdog tick, which at a 60s interval would leave "reconnecting" on
     * screen for up to a minute longer than intended.
     */
    private var graceExpiryJob: Job? = null

    // ───────────────────────── State derivation ─────────────────────────

    /** Called from `BleManager.updateStatus()` whenever a transport link flips. */
    fun onTransportChanged() = recompute()

    /** Called from the init-handler state setter whenever the handshake state changes. */
    fun onInitStateChanged(state: State) {
        lastInitState = state
        recompute()
    }

    /**
     * The link just went down unexpectedly. Called from the transport's disconnect path.
     *
     * Two jobs: open the [RECONNECT_GRACE_MS] window so the UI reads "reconnecting" rather than
     * "disconnected", and ask for the reconnect NOW instead of waiting out the next watchdog tick —
     * which cost ~4s of the observed recovery time. Both are no-ops when auto-reconnect isn't
     * wanted (manual disconnect, unpair, USB takeover), because those clear [autoReconnectDesired].
     */
    fun onLinkLost(transport: TransportId) {
        if (!autoReconnectDesired) {
            Timber.tag(TAG).d("link lost over $transport — auto-reconnect not desired, staying down")
            return
        }
        openReconnectGrace()
        recompute()
        if (shouldAutoReconnect()) requestReconnect(transport, "link lost")
    }

    /**
     * The radio went silent: [com.commcrete.stardust.util.connectivity.PortUtils] sent keepalive
     * pings and nothing at all came back for three consecutive windows (~30s).
     *
     * This is the only detector for the failure both transports share — the link is physically up
     * and the radio behind it is not running. Neither stack notices on its own: a USB bridge stays
     * enumerated and bus-powered when the radio's MCU hangs, and an LE link only dies on supervision
     * timeout if the radio's controller stops answering too. Both leave the SDK reporting
     * Ready/Syncing forever.
     *
     * Deliberately publishes an honest **Disconnected** rather than the [ConnectionState.Searching]
     * that a fresh drop gets: the grace window exists to ride out a blip that usually heals within
     * one attempt, and 30s of total silence is already well past that. The retry then runs behind
     * the Disconnected state — the user is told the truth while the SDK keeps working on it.
     */
    @Synchronized
    fun onKeepaliveLost(transport: TransportId, reason: String) {
        Timber.tag(TAG).w("keepalive lost over $transport — $reason")
        // Each transport tears itself down and arms its own recovery (USB re-probes a device that
        // is still attached; BLE has none and is retried just below).
        try {
            TransportRegistry.of(transport).onKeepaliveLost(reason)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "keepalive teardown over $transport failed")
        }
        // BLE only. A USB link that went silent is restored by re-probing the attached device, and
        // if the cable is out there is deliberately nothing to fall back to: a USB unplug stays the
        // user's decision, which is also why the takeover cleared autoReconnectDesired.
        if (transport == TransportId.BLE && shouldAutoReconnect()) {
            requestReconnect(transport, reason, withGrace = false)
        }
    }

    @Synchronized
    private fun recompute() {
        val active = TransportRegistry.active()?.id
        val previous = _connectionState.value
        val next = derive(active, lastInitState, inReconnectGrace())
        _connectionState.value = next

        // Reaching a connected state means any pending reconnect is moot and backoff can reset.
        if (next is ConnectionState.LinkUp || next is ConnectionState.Syncing || next is ConnectionState.Ready) {
            reconnectJob?.cancel()
            reconnectJob = null
            attempt.set(0)
            clearReconnectGrace()
            attemptInFlightUntilMs = 0L
        }

        // Arm the auto-reconnect watchdog only on the EDGE INTO an established BLE link
        // (Syncing/Ready), never on a recompute that merely re-observes one.
        //
        // Edge-triggering is what makes an unpair stick. `unpairDeviceBLE()` disables auto-reconnect
        // while the link is still physically up, so the very next recompute still derives
        // Ready(BLE) — and a level-triggered arm here would immediately undo the disable, leaving
        // the teardown that follows looking like an unexpected drop to be reconnected.
        //
        // LinkUp is excluded for a related reason: it can be derived spuriously right after a manual
        // disconnect, when the init state is already DISCONNECTED but BleManager.isBleConnected
        // hasn't propagated to false yet.
        if (!isBleEstablished(previous) && isBleEstablished(next)) {
            autoReconnectDesired = true
            startReconnectWatchdog()
        }
    }

    private fun isBleEstablished(state: ConnectionState): Boolean = when (state) {
        is ConnectionState.Syncing -> state.transport == TransportId.BLE
        is ConnectionState.Ready -> state.transport == TransportId.BLE
        else -> false
    }

    /**
     * Publishes a [ConnectionState.Blocked] for a connect attempt the phone refused (adapter off,
     * permission missing). Deliberately NOT latched: the next transport or handshake change
     * re-derives the state through [recompute], so a blocker never outlives the condition that
     * produced it.
     */
    /**
     * Re-derives and republishes the state. For callers that finish a teardown whose individual
     * steps were all no-ops because everything was already in the target state.
     */
    fun refresh() = recompute()

    fun reportBlocked(reason: Blocker) {
        Timber.tag(TAG).w("connection blocked: $reason")
        _connectionState.value = ConnectionState.Blocked(reason)
    }

    private fun derive(active: TransportId?, s: State, inGrace: Boolean): ConnectionState = when {
        s == State.BLUETOOTH_OFF -> ConnectionState.Blocked(Blocker.BLUETOOTH_OFF)
        s == State.CANCELED || StardustInitConnectionHandler.hasUnsyncableError() ->
            ConnectionState.Error(toConnectionError(s))
        // A link-down inside the post-drop grace is a reconnect in progress, not a dead connection.
        active == null -> if (s == State.SEARCHING || inGrace) ConnectionState.Searching else ConnectionState.Disconnected
        s == State.SUCCESS -> ConnectionState.Ready(active)
        StardustInitConnectionHandler.isSyncing() -> ConnectionState.Syncing(active)
        else -> ConnectionState.LinkUp(active)
    }

    private fun inReconnectGrace(): Boolean =
        autoReconnectDesired && SystemClock.elapsedRealtime() < reconnectGraceUntilMs

    /** Anchors the grace window and arms the republish for the moment it lapses. */
    @Synchronized
    private fun openReconnectGrace() {
        reconnectGraceUntilMs = SystemClock.elapsedRealtime() + RECONNECT_GRACE_MS
        graceExpiryJob?.cancel()
        graceExpiryJob = Scopes.getDefaultCoroutine().launch {
            delay(RECONNECT_GRACE_MS)
            recompute()
        }
    }

    @Synchronized
    private fun clearReconnectGrace() {
        reconnectGraceUntilMs = 0L
        graceExpiryJob?.cancel()
        graceExpiryJob = null
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
    @Synchronized
    fun requestReconnect(transport: TransportId, reason: String, withGrace: Boolean = true) {
        // BLE only: a USB ping-timeout reconnect must still work during a USB session, and entering
        // USB calls disableAutoReconnect() as part of the takeover.
        if (transport == TransportId.BLE && autoConnectSuppressed) {
            Timber.tag(TAG).d("BLE reconnect suppressed by an intentional disconnect ($reason)")
            return
        }
        if (reconnectJob?.isActive == true) {
            Timber.tag(TAG).d("reconnect already in progress; ignoring request ($reason)")
            return
        }
        // See RECONNECT_ATTEMPT_WINDOW_MS: the job guard above expires almost immediately, so this
        // is what actually gives an attempt time to land instead of being torn down by the next
        // trigger. Applied here rather than in shouldAutoReconnect() so that EVERY caller respects
        // it — the ping-timeout and connection watchdogs come straight in here.
        val now = SystemClock.elapsedRealtime()
        if (now < attemptInFlightUntilMs) {
            Timber.tag(TAG).d("reconnect still in flight for ${attemptInFlightUntilMs - now}ms; ignoring request ($reason)")
            return
        }
        attemptInFlightUntilMs = now + RECONNECT_ATTEMPT_WINDOW_MS
        // Open a grace window for an attempt that started from an already-down state (a manual
        // reconnect, say) — it never passed through onLinkLost, so it has no window of its own.
        //
        // Strictly `== 0L`, i.e. only when no outage is being tracked yet. This must NEVER extend an
        // existing window: the watchdog retries for as long as the radio is away, and renewing per
        // attempt made the window unexpirable — the state sat on Searching forever, blinking through
        // Disconnected for one frame every 30s (captured 2026-09-16 09:54-09:57). The window is
        // anchored to when the link was lost; retries after it lapses are silent housekeeping, and
        // the user sees an honest Disconnected until one of them succeeds.
        //
        // [withGrace] = false is the keepalive verdict: the radio has already been silent for ~30s,
        // so there is no blip left to ride out and the state must read Disconnected while this
        // attempt runs.
        if (withGrace && autoReconnectDesired && reconnectGraceUntilMs == 0L) {
            openReconnectGrace()
            recompute()
        }
        val delayMs = backoffDelay()
        Timber.tag(TAG).d("scheduling reconnect over $transport in ${delayMs}ms (attempt ${attempt.get() + 1}, reason=$reason)")

        reconnectJob = Scopes.getDefaultCoroutine().launch {
            if (delayMs > 0) delay(delayMs)
            attempt.incrementAndGet()
            try {
                TransportRegistry.of(transport).reconnect()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "reconnect over $transport failed")
            }
        }
    }

    private fun backoffDelay(): Long {
        val a = attempt.get()
        if (a == 0) return 0L
        val shift = (a - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
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
     *  - Bluetooth off → the state is [ConnectionState.Blocked], not [ConnectionState.Disconnected],
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
                // Republish: nothing else fires when the post-drop grace simply expires, so without
                // this the state would sit on Searching after auto-reconnect has given up hope.
                recompute()
                // Re-derive paired-state from the OS bond registry before deciding, which also
                // repairs the published BleManager.isPaired for the host UI. No-ops when the bond
                // set is unreadable, so it can never wipe a valid pairing.
                PairingRepository.reconcile()
                if (shouldAutoReconnect()) {
                    requestReconnect(TransportId.BLE, "auto-reconnect watchdog")
                }
            }
        }
    }

    /**
     * Paired-state is DERIVED here, not read from [BleManager.isPaired].
     *
     * That LiveData is written optimistically from several places and cleared from others, and was
     * observed reading `false` while the device was genuinely app-paired (live capture 2026-08-26:
     * `linkState: isPaired=false` at 11:09:52 and 11:10:20, while `canStartInit` resolved
     * `paired=44:B7:D0:71:73:C7` at 11:10:39). Gating the watchdog on that cached value silently
     * disabled every BLE auto-reconnect in such a window.
     *
     * [PairingRepository.currentPairedAddress] intersects the saved address with the OS bond
     * registry, and deliberately falls back to trusting the saved record when the bond set is
     * unreadable (Bluetooth off / no CONNECT permission) — so a transient off-state cannot look
     * like "not paired" either.
     */
    private fun shouldAutoReconnect(): Boolean =
        autoReconnectDesired &&
            RegisteredUserUtils.isUserLoggedIn() &&
            PairingRepository.currentPairedAddress() != null &&
            !BleManager.isUSBConnected &&
            isBluetoothOn() &&
            // Deliberately NOT `_connectionState.value is Disconnected` any more: inside the
            // post-drop grace the published state is Searching, and gating on that would switch the
            // watchdog off exactly when it is needed. This re-derives the same condition the
            // published value used to carry, with the grace factored out.
            //
            // Still intentionally excludes a real SEARCHING (a fresh connect started by
            // bondOnStartup / triggerInitSequence): firing a reconnect then would force-disconnect
            // that in-flight attempt.
            derive(TransportRegistry.active()?.id, lastInitState, inGrace = false) is ConnectionState.Disconnected

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
    /**
     * Stops auto-reconnect AND blocks the connect paths that don't consult it — the Bluetooth-on
     * observer and [com.commcrete.stardust.util.DataManager.bondOnStartup]. For UNPAIR only.
     *
     * Kept separate from [disableAutoReconnect] because the host may have no explicit "connect"
     * action: if a USB unplug suppressed everything, the app's next `bondOnStartup()` would be
     * refused and BLE could never come back. An unpair is different — there is deliberately nothing
     * left to connect to.
     */
    @Synchronized
    fun suppressAutoConnect() {
        disableAutoReconnect()
        autoConnectSuppressed = true
        Timber.tag(TAG).d("auto-connect suppressed (unpair)")
    }

    @Synchronized
    fun disableAutoReconnect() {
        autoReconnectDesired = false
        watchdogJob?.cancel()
        watchdogJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        // Nothing is going to reconnect, so a link-down must read as Disconnected rather than
        // pretending a reconnect is under way.
        clearReconnectGrace()
        attemptInFlightUntilMs = 0L
        // Republish. The teardown that follows this call CANNOT be relied on to do it: when the
        // link is already down, `updateStatus()` computes the same transport it already had and
        // early-returns, and `updateConnectionState(DISCONNECTED)` finds the handshake state
        // already DISCONNECTED and skips — so nothing recomputes and the flow keeps whatever it
        // last published, typically the Searching from the grace window that was just cleared.
        //
        // Safe since arming became edge-triggered: recomputing while the link is still physically
        // up re-derives the same established state, which is no longer an edge and cannot re-arm
        // the watchdog this call just stopped.
        recompute()
    }
}
