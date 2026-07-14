package com.commcrete.stardust.transport

import com.commcrete.stardust.stardust.StardustInitConnectionHandler

/**
 * A single, unified view of the connection — the Stage 3 replacement for reconciling the two
 * separate signals the app exposes today: the raw transport link
 * ([com.commcrete.stardust.ble.BleManager.bleConnectionStatus] / `usbConnectionStatus`) and the
 * init-handshake state ([StardustInitConnectionHandler.State]).
 *
 * A physical link being up ([LinkUp]) is distinct from the device being usable ([Ready], reached
 * only after the init handshake), which previously required the UI to cross-reference two
 * callbacks (`connectionStatusChanged` + `onDeviceInitialized`).
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

    /** Connected and running the init handshake [step]. */
    data class Syncing(val transport: TransportId, val step: StardustInitConnectionHandler.State) : ConnectionState

    /** Fully connected and synced — the device is usable. */
    data class Ready(val transport: TransportId) : ConnectionState

    /** Connected but the handshake produced a terminal error (license/preset/encryption/canceled). */
    data class Failed(val reason: StardustInitConnectionHandler.State) : ConnectionState
}
