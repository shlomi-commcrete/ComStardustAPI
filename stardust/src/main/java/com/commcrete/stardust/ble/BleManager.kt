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

    fun isBluetoothConnected() : Boolean{
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
                if (lastConnectionStatus == ConnectionType.BLE) {
                    getClientConnection().disconnectFromBLEDevice(disconnectByForce = true, withStateUpdate = false)
                }
            }

            ConnectionType.BLE -> {}

            else -> {
                ConfigurationUtils.reset()
                CarriersUtils.reset()
                StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
            }
        }

        DataManager.getCallbacks()?.connectionStatusChanged(newStatus)
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