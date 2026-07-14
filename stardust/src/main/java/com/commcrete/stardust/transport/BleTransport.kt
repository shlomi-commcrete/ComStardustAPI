package com.commcrete.stardust.transport

import androidx.lifecycle.LiveData
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.model.StardustPackage

/**
 * [Transport] adapter over the existing BLE stack ([ClientConnection]). Pure delegation — it adds
 * no behaviour of its own, so introducing it changes nothing at runtime until callers opt in.
 */
internal class BleTransport(private val conn: ClientConnection) : Transport {

    override val id: TransportId = TransportId.BLE

    override val isConnected: Boolean
        get() = BleManager.isBleConnected

    override val connectionState: LiveData<Boolean>
        get() = BleManager.bleConnectionStatus

    override fun send(pkg: StardustPackage) {
        conn.addMessageToQueue(pkg)
    }

    override fun disconnect(force: Boolean) {
        conn.disconnectFromBLEDevice(disconnectByForce = force, withStateUpdate = true)
    }

    override fun updateBlePort() = conn.updateBlePort()

    override fun saveConfiguration() = conn.saveConfiguration()
}
