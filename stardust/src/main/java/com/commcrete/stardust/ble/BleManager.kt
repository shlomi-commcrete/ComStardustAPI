package com.commcrete.stardust.ble

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.util.Scopes
import com.commcrete.bittell.util.connectivity.ConnectivityObserver
import com.commcrete.stardust.enums.ConnectionType
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.transport.ConnectionManager
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.getClientConnection
import com.commcrete.stardust.util.connectivity.NetworkConnectivityObserver
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object BleManager {

    const val CONNECTION_TAG = "connection_tag"

    const val REQUEST_ENABLE_BT = 10100

    var isBleConnected = false
    var isUSBConnected = false
        internal set(value) {
            if(value) {
                isBleConnected = false
            }
            field = value
        }
    var isNetworkConnected = false
    var isNetworkToggleEnabled = true

    private var connectionStatus: ConnectionType? = null
    val bleConnectionStatus : MutableLiveData<Boolean> = MutableLiveData(isBleConnected)
    val usbConnectionStatus : MutableLiveData<Boolean> = MutableLiveData(isUSBConnected)
    val isPaired : MutableLiveData<Boolean> = MutableLiveData(false)
    val hasBattery : MutableLiveData<Boolean> = MutableLiveData(true)
    val rssi : MutableLiveData<Int> = MutableLiveData(0)

    fun initServerConnectivityObserver(){
        val connectivityObserver = NetworkConnectivityObserver()
        Scopes.getMainCoroutine().launch {
            connectivityObserver.observe().collectLatest {
                if(it == ConnectivityObserver.Status.Available){
                    isNetworkConnected = true
//                    bleConnectionStatus.value = false
                } else {
                    isNetworkConnected = false
                    bleConnectionStatus.value = isBleConnected
                }
            }
        }
    }

    fun initBleConnectState() {
        BluetoothStateManager.initialize()
    }

    fun isUsbEnabled() : Boolean {
        return isUSBConnected
    }

    fun isNetworkEnabled() : Boolean{
        return isNetworkConnected && isNetworkToggleEnabled
    }

    fun isBluetoothConnected() : Boolean {
        return isBleConnected && getClientConnection().isBluetoothEnabled()
    }

    fun updateStatus() {
        val lastConnectionStatus = connectionStatus
        val newStatus = when {
            isUsbEnabled() ->  ConnectionType.USB
            isBluetoothConnected() -> ConnectionType.BLE
            else -> null
        }

        Log.d("StardustDataManager", "updateStatus: newStatus -> $newStatus")

        if(lastConnectionStatus == newStatus) return
        connectionStatus = newStatus

        when(newStatus) {
            ConnectionType.USB -> {
                // USB takeover: tear BLE down without unpairing. Also disable the BLE
                // auto-reconnect watchdog — otherwise, when USB later drops, the watchdog would
                // immediately auto-reconnect BLE, contradicting the "SDK stays idle; the user
                // decides" policy for a USB unplug.
                //
                // This is deliberately UNCONDITIONAL. It used to be guarded by
                // `lastConnectionStatus == ConnectionType.BLE`, but `connectionStatus` is only BLE
                // when `isBluetoothConnected()` was true at the previous transition. A BLE link
                // that was physically up but never finished the init handshake leaves it at `null`,
                // so the guard skipped the teardown and the BLE GATT stayed open for the entire USB
                // session. Reaching this branch at all means USB just became the active transport,
                // which is sufficient reason to drop BLE regardless of what the cached status said.
                //
                // Note this branch only runs on an actual transition into USB — `updateStatus`
                // early-returns above when the computed status is unchanged — so it cannot fire
                // repeatedly for an already-established USB session.
                ConnectionManager.disableAutoReconnect()
                getClientConnection().disconnectFromBLEDevice(disconnectByForce = true, withStateUpdate = false)
                // Keep the observable in sync: the `isUSBConnected` setter clears `isBleConnected`
                // silently, so without this a host observing `bleConnectionStatus` still sees BLE
                // connected for the whole USB session. postValue because updateStatus() is reached
                // from non-main threads (e.g. BittelUsbManager2.disconnect()).
                bleConnectionStatus.postValue(false)
            }

            ConnectionType.BLE -> {}

            else -> {
                // Transport went null. Two possible histories:
                //   - USB was active and just unplugged (Case 4): SDK must stay idle so the host
                //     can show its "connect via BLE / unpair" dialog. Disable the watchdog.
                //   - BLE was active and just dropped unexpectedly (Case 2 unexpected drop, e.g.
                //     battery-dies / out-of-range): the watchdog is exactly the recovery
                //     mechanism, leave it armed.
                if (lastConnectionStatus == ConnectionType.USB) {
                    ConnectionManager.disableAutoReconnect()
                }
                // No transport left, so any file/image transfer in flight is over
                // whether or not the disconnect was intentional. Record it now — an
                // unexpected drop never reaches disconnectFromDevice(), so this is the
                // only place a battery-dies / out-of-range / unplug loss is settled.
                DataManager.failInFlightFileTransfers()
                ConfigurationUtils.reset()
                CarriersUtils.reset()
                StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
            }
        }

        DataManager.getCallbacks()?.connectionStatusChanged(newStatus)
        ConnectionManager.onTransportChanged()
    }

    fun redirectUserToTurnOnBLE(context: Activity) {
        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            context.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        } catch (e: SecurityException) {
            try {
                val settingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                context.startActivity(settingsIntent)
            } catch (ex: Exception) {
                // fallback: settings unavailable
                Toast.makeText(context, "Unable to open Bluetooth settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

}