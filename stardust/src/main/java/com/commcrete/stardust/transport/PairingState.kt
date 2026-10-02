package com.commcrete.stardust.transport

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why a pairing attempt ended without a bond. Split by what the host tells the user, not by the
 * platform's (more numerous) unbond codes.
 */
sealed interface PairingFailure {
    /** The radio never answered: off, out of range, or already connected elsewhere. */
    data object NotReachable : PairingFailure
    /** The user declined or dismissed the system pairing dialog (or the radio refused). */
    data object Rejected : PairingFailure
    /** Authentication failed — typically a wrong PIN / passkey. */
    data object AuthFailed : PairingFailure
    /** Pairing started but was not confirmed in time. */
    data object Timeout : PairingFailure
    /** No user is logged in, so the SDK refuses to connect. */
    data object NotLoggedIn : PairingFailure
    /** Something on the phone stops the attempt; same vocabulary as [ConnectionState.Blocked]. */
    data class Blocked(val blocker: Blocker) : PairingFailure
    /** No more specific cause is available. */
    data object Unknown : PairingFailure
}

/**
 * Progress of the most recent pairing attempt started by
 * [com.commcrete.stardust.StardustAPI.connect] (or a companion-device association). Covers only the
 * part BEFORE a link exists — once [Paired] is reached, follow
 * [com.commcrete.stardust.StardustAPI.connectionState] for LinkUp → Syncing → Ready.
 *
 * Kept apart from [ConnectionState] on purpose: that one is re-derived from link + handshake state
 * on every change, while a pairing failure must stay visible until the user retries.
 */
sealed interface PairingState {

    /** No attempt in progress (none started yet, or the host called `cancelPairing()`). */
    data object Idle : PairingState

    /** Attempt started; waiting for the radio to answer. The system dialog is not up yet. */
    data class Connecting(val address: String) : PairingState

    /** The system pairing dialog (or notification) is being shown; waiting for the user. */
    data class AwaitingConfirmation(val address: String) : PairingState

    /** Bonded (or was already bonded). Connection continues on `connectionState()`. */
    data class Paired(val address: String) : PairingState

    /** Terminal for this attempt; stays until the next `connect()`. */
    data class Failed(val address: String, val reason: PairingFailure) : PairingState
}

/**
 * Owns the [PairingState] stream. Only [begin] may start an attempt; every other transition is
 * applied only while an attempt for the same address is in flight, so bond broadcasts for a device
 * that is already connected (or for an attempt the host canceled) can never resurrect the stream.
 */
internal object PairingTracker {

    private const val TAG = "PairingTracker"

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    /** Address of the attempt currently in flight, or null. */
    val activeAddress: String?
        get() = when (val s = _state.value) {
            is PairingState.Connecting -> s.address
            is PairingState.AwaitingConfirmation -> s.address
            else -> null
        }

    fun isActive(address: String?): Boolean =
        address != null && activeAddress?.equals(address, ignoreCase = true) == true

    @Synchronized
    fun begin(address: String) = publish(PairingState.Connecting(address))

    @Synchronized
    fun awaitingConfirmation(address: String) {
        if (isActive(address)) publish(PairingState.AwaitingConfirmation(address))
    }

    @Synchronized
    fun paired(address: String) {
        if (isActive(address)) publish(PairingState.Paired(address))
    }

    @Synchronized
    fun failed(address: String, reason: PairingFailure) {
        if (isActive(address)) publish(PairingState.Failed(address, reason))
    }

    /** Ends the active attempt without a failure (host-initiated cancel). */
    @Synchronized
    fun cancel(): Boolean {
        if (activeAddress == null) return false
        publish(PairingState.Idle)
        return true
    }

    private fun publish(next: PairingState) {
        Log.d(TAG, "pairing: ${_state.value} -> $next")
        _state.value = next
    }
}
