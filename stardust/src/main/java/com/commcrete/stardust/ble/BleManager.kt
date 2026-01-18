package com.commcrete.stardust.ble

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.util.Scopes
import com.commcrete.bittell.util.connectivity.ConnectivityObserver
import com.commcrete.stardust.enums.ConnectionType
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.PermissionTracking
import com.commcrete.stardust.util.connectivity.NetworkConnectivityObserver
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object BleManager {

    const val CONNECTION_TAG = "connection_tag"

    const val REQUEST_ENABLE_BT = 10100

    var isBleConnected = false
    var isUSBConnected = false
    var isNetworkConnected = false
    var isNetworkToggleEnabled = true
    var isBluetoothToggleEnabled = true

    private var connectionStatus: ConnectionType? = null
    val bleConnectionStatus : MutableLiveData<Boolean> = MutableLiveData(isBleConnected)
    val usbConnectionStatus : MutableLiveData<Boolean> = MutableLiveData(isUSBConnected)
    val isPaired : MutableLiveData<Boolean> = MutableLiveData(false)
    val hasBattery : MutableLiveData<Boolean> = MutableLiveData(true)
    val rssi : MutableLiveData<Int> = MutableLiveData(0)

    fun initServerConnectivityObserver(context : Context){
        val connectivityObserver = NetworkConnectivityObserver(context)
        Scopes.getMainCoroutine().launch {
            connectivityObserver.observe().collectLatest {
                if(it == ConnectivityObserver.Status.Available){
                    isNetworkConnected = true
//                    bleConnectionStatus.value = false
                } else {
                    isNetworkConnected = false
                    if(isBleConnected){
                        bleConnectionStatus.value = true
                    }else {
                        bleConnectionStatus.value = false
                    }
                }
            }
        }
    }

    fun initBleConnectState (context: Context) {
        BluetoothStateManager.initialize(context)
    }

    fun isUsbEnabled () : Boolean {
        return isUSBConnected
    }

    fun isNetworkEnabled () : Boolean{
        return isNetworkConnected && isNetworkToggleEnabled
    }

    fun isBluetoothEnabled () : Boolean{
        return isBleConnected && isBluetoothToggleEnabled
    }

    fun updateStatus() {
        val newStatus = when {
            isUsbEnabled() -> {
                if (!isBluetoothToggleEnabled && isBleConnected) {
                    DataManager.getClientConnection(DataManager.context).disconnectFromBLEDevice(true)
                }
                ConnectionType.USB
            }

            isBluetoothEnabled() -> ConnectionType.BLE

            else -> {
                ConfigurationUtils.reset()
                CarriersUtils.reset()
                StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.DISCONNECTED)
                null
            }
        }
        connectionStatus = newStatus
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