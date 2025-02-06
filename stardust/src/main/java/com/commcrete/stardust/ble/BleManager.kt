package com.commcrete.stardust.ble

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.util.Scopes
import com.commcrete.bittell.util.connectivity.ConnectivityObserver
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.connectivity.NetworkConnectivityObserver
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object BleManager {

    const val CONNECTION_TAG = "connection_tag"

    var isBleConnected = false
    var isUSBConnected = false
    var isNetworkConnected = false
    var isNetworkToggleEnabled = true
    var isBluetoothToggleEnabled = true
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

    fun updateStatus () {
        if(isUsbEnabled () && isBluetoothEnabled()) {
            DataManager.getCallbacks()?.connectionStatusChanged(ConnectionStatus.BLE_USB)
        } else if(isUsbEnabled ()) {
            DataManager.getCallbacks()?.connectionStatusChanged(ConnectionStatus.USB)
        } else if (isBluetoothEnabled()) {
            DataManager.getCallbacks()?.connectionStatusChanged(ConnectionStatus.BLE)
        } else {
            DataManager.getCallbacks()?.connectionStatusChanged(ConnectionStatus.DISCONNECTED)
        }
    }

    enum class ConnectionStatus {
        USB,
        BLE,
        BLE_USB,
        DISCONNECTED
    }
}