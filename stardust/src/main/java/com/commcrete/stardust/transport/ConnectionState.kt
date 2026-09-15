package com.commcrete.stardust.transport

import com.commcrete.stardust.BleUnavailableReason
import com.commcrete.stardust.enums.ScanFailure

/**
 * Stable, public reason a connection ended in a terminal error. Mapped from the SDK's internal
 * handshake state so consumers never depend on that internal enum ([com.commcrete.stardust.stardust.StardustInitConnectionHandler.State]).
 *
 * The split against [Blocker] is deliberate and is about WHO fixes it: a [ConnectionError] is about
 * the radio or its license (the user cannot resolve it from the phone), a [Blocker] is something the
 * user changes on the phone.
 */
enum class ConnectionError {
    /** No valid license on the device. */
    NoLicense,
    /** Encryption-key mismatch / key setup failed. */
    EncryptionKey,
    /** Device presets are missing or invalid. */
    Preset,
    /** The connection/handshake was canceled or aborted. */
    Canceled,
    /** Terminal failure with no more specific cause. */
    Unknown,
}

/**
 * Something on the PHONE prevents connecting or scanning, and the user can fix it. Reported through
 * [ConnectionState.Blocked] when it stops a link, and through [ScanState.Failed] when it stops a
 * scan — the same vocabulary either way.
 *
 * Supersedes [BleUnavailableReason] and [ScanFailure], which are kept only for the deprecated
 * callbacks.
 */
enum class Blocker {
    /** The Bluetooth adapter is turned off. Send the user to `ACTION_REQUEST_ENABLE`. */
    BLUETOOTH_OFF,
    /** The device has no Bluetooth adapter / BLE hardware. Nothing the user can do. */
    BLUETOOTH_UNSUPPORTED,
    /** `BLUETOOTH_SCAN` (Android 12+) is not granted. */
    SCAN_PERMISSION_MISSING,
    /** `BLUETOOTH_CONNECT` (Android 12+) is not granted. */
    CONNECT_PERMISSION_MISSING,
    /**
     * `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` are not granted. Required for scan results on
     * every API level, because the library deliberately does not flag `BLUETOOTH_SCAN` with
     * `neverForLocation` (see AndroidManifest.xml).
     */
    LOCATION_PERMISSION_MISSING,
    /** The device-wide Location toggle is off. Send the user to `ACTION_LOCATION_SOURCE_SETTINGS`. */
    LOCATION_SERVICES_OFF,
    /**
     * The platform's scan-start throttle tripped (5 starts per 30 seconds). Back off; do not retry
     * immediately.
     */
    SCAN_THROTTLED,
    /**
     * The platform refused or could not run the scan for a reason the user cannot act on (internal
     * stack error, no free hardware slot, unsupported settings, scan already running).
     */
    SCAN_UNAVAILABLE,
}

/**
 * The single, unified view of the connection — the public read-model derived from the transport
 * link and the init-handshake state. It is complete on its own: consumers render everything they
 * need from this and never reach into the SDK's internal predicates or state enum.
 *
 * A physical link being up ([LinkUp]) is distinct from the device being usable ([Ready], reached
 * only after the init handshake).
 */
sealed interface ConnectionState {

    /** No link and nothing in progress. */
    data object Disconnected : ConnectionState

    /**
     * A link cannot be established until the user fixes [reason]. Published when a connect attempt is
     * actually refused, so it reflects the most recent attempt rather than a continuous poll — the
     * next transport or handshake change re-derives the state.
     */
    data class Blocked(val reason: Blocker) : ConnectionState

    /** Looking for / forming a link (scanning, bonding, or init searching) — no link yet. */
    data object Searching : ConnectionState

    /** Physically connected over [transport], handshake not yet started. */
    data class LinkUp(val transport: TransportId) : ConnectionState

    /** Connected and running the init handshake. */
    data class Syncing(val transport: TransportId) : ConnectionState

    /** Fully connected and synced — the device is usable. */
    data class Ready(val transport: TransportId) : ConnectionState

    /** Terminal failure with a typed [cause]. Surfaced regardless of whether a link is up. */
    data class Error(val cause: ConnectionError) : ConnectionState
}

/**
 * Which link is carrying the radio, or null when none is. Replaces the separate
 * `connectionStatusChanged(ConnectionType?)` callback.
 */
val ConnectionState.transport: TransportId?
    get() = when (this) {
        is ConnectionState.LinkUp -> transport
        is ConnectionState.Syncing -> transport
        is ConnectionState.Ready -> transport
        else -> null
    }

/** True only in [ConnectionState.Ready]: connected AND synced, so the device can be used. */
val ConnectionState.isUsable: Boolean
    get() = this is ConnectionState.Ready

/** Bridges the deprecated [BleUnavailableReason] onto the unified vocabulary. */
internal fun BleUnavailableReason.toBlocker(): Blocker = when (this) {
    BleUnavailableReason.BLUETOOTH_DISABLED -> Blocker.BLUETOOTH_OFF
    BleUnavailableReason.BLUETOOTH_UNSUPPORTED -> Blocker.BLUETOOTH_UNSUPPORTED
    BleUnavailableReason.CONNECT_PERMISSION_MISSING -> Blocker.CONNECT_PERMISSION_MISSING
    BleUnavailableReason.SCAN_PERMISSION_MISSING -> Blocker.SCAN_PERMISSION_MISSING
    BleUnavailableReason.UNKNOWN -> Blocker.SCAN_UNAVAILABLE
}

/**
 * Bridges the deprecated [ScanFailure] onto the unified vocabulary. The platform's distinct
 * "cannot run the scan" codes collapse onto [Blocker.SCAN_UNAVAILABLE] — the host has no different
 * remedy for any of them. [ScanFailure.MISSING_PERMISSIONS] is refined by the caller, which can
 * check WHICH permission is absent.
 */
internal fun ScanFailure.toBlocker(): Blocker = when (this) {
    ScanFailure.LOCATION_SERVICES_DISABLED -> Blocker.LOCATION_SERVICES_OFF
    ScanFailure.MISSING_PERMISSIONS -> Blocker.SCAN_PERMISSION_MISSING
    ScanFailure.BLUETOOTH_DISABLED -> Blocker.BLUETOOTH_OFF
    ScanFailure.BLUETOOTH_UNAVAILABLE -> Blocker.BLUETOOTH_UNSUPPORTED
    ScanFailure.SCANNING_TOO_FREQUENTLY -> Blocker.SCAN_THROTTLED
    ScanFailure.ALREADY_STARTED,
    ScanFailure.APPLICATION_REGISTRATION_FAILED,
    ScanFailure.INTERNAL_ERROR,
    ScanFailure.FEATURE_UNSUPPORTED,
    ScanFailure.OUT_OF_HARDWARE_RESOURCES,
    ScanFailure.UNKNOWN -> Blocker.SCAN_UNAVAILABLE
}
