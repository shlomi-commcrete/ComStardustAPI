package com.commcrete.stardust.transport

import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.util.DataManager

/**
 * Central access point for the connection [Transport]s and the single place that decides which
 * one is currently active.
 *
 * The selection rule (USB takes precedence over BLE) mirrors [BleManager.updateStatus]; this
 * centralises the BLE-vs-USB branching that is otherwise duplicated inline across the codebase
 * (e.g. port-update loops). Stage 3's ConnectionManager will own this selection as observable
 * state; for now it is a pure query with no side effects.
 */
object TransportRegistry {

    private var bleTransport: BleTransport? = null

    /** The BLE transport, wrapping the shared [com.commcrete.stardust.ble.ClientConnection]. */
    fun ble(): Transport =
        bleTransport ?: BleTransport(DataManager.getClientConnection()).also { bleTransport = it }

    /** The USB transport singleton. */
    fun usb(): Transport = UsbTransport

    fun of(id: TransportId): Transport = when (id) {
        TransportId.BLE -> ble()
        TransportId.USB -> usb()
    }

    /**
     * The transport currently carrying a connected radio, or `null` when neither link is up.
     * USB wins when both are somehow active, matching the app's USB-over-BLE precedence.
     */
    fun active(): Transport? = when {
        BleManager.isUsbEnabled() -> usb()
        BleManager.isBluetoothConnected() -> ble()
        else -> null
    }
}
