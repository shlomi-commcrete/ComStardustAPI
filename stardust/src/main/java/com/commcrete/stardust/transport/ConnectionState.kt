package com.commcrete.stardust.transport

/**
 * Stable, public reason a connection ended in a terminal error. Mapped from the SDK's internal
 * handshake state so consumers never depend on that internal enum ([com.commcrete.stardust.stardust.StardustInitConnectionHandler.State]).
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

    /** Bluetooth adapter is off. */
    data object BluetoothOff : ConnectionState

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
