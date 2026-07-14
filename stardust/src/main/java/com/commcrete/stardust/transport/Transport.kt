package com.commcrete.stardust.transport

import androidx.lifecycle.LiveData
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.BittelProtocol

/** The two physical links a Stardust radio can be reached over. */
enum class TransportId { BLE, USB }

/**
 * A single physical link to a Stardust/Bittel radio.
 *
 * Stage 2 of the connection refactor: this is a thin abstraction over the two existing stacks
 * ([com.commcrete.stardust.ble.ClientConnection] for BLE, [com.commcrete.stardust.usb.BittelUsbManager2]
 * for USB). The concrete adapters DELEGATE to those classes — no behaviour is reimplemented here.
 *
 * It intentionally models only the operations that are shared and that the transport-selection
 * logic needs today: identity, link state, send, disconnect, and the [BittelProtocol] port config.
 * Connection establishment stays transport-specific (BLE scans+bonds, USB attaches) and a unified
 * `connect`, coroutine/`Result` send, and an `incoming` flow are deferred to Stage 3, where a
 * `ConnectionManager` will own a single connection-state machine and consume these transports.
 */
interface Transport : BittelProtocol {

    val id: TransportId

    /** Whether this link currently carries a connected radio. */
    val isConnected: Boolean

    /** Observable link-up state (mirrors the existing per-transport LiveData). */
    val connectionState: LiveData<Boolean>

    /** Enqueues a package for transmission over this transport. */
    fun send(pkg: StardustPackage)

    /** Tears the link down. [force] disconnects even if the session isn't fully connected. */
    fun disconnect(force: Boolean = true)
}
