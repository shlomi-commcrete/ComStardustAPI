package com.commcrete.stardust.transport

import androidx.lifecycle.LiveData
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.usb.BittelUsbManager2

/**
 * [Transport] adapter over the existing USB-serial stack ([BittelUsbManager2], a singleton).
 * Pure delegation.
 *
 * Note: [send] writes straight to the UART. Today most traffic is still enqueued through the BLE
 * [com.commcrete.stardust.ble.ClientConnection] queue (which itself routes to USB when USB is
 * active), so this direct path is used by the future ConnectionManager rather than existing
 * callers. Unifying the send path is Stage 3/4 work.
 */
internal object UsbTransport : Transport {

    override val id: TransportId = TransportId.USB

    override val isConnected: Boolean
        get() = BleManager.isUSBConnected

    override val connectionState: LiveData<Boolean>
        get() = BleManager.usbConnectionStatus

    override fun send(pkg: StardustPackage) {
        BittelUsbManager2.sendDataToUart(pkg)
    }

    override fun disconnect(force: Boolean) {
        BittelUsbManager2.disconnect()
    }

    override fun updateBlePort() = BittelUsbManager2.updateBlePort()

    override fun saveConfiguration() = BittelUsbManager2.saveConfiguration()
}
