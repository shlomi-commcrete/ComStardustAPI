package com.commcrete.stardust.util.connectivity

import android.content.Context
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.usb.BittelUsbManager2
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

object PortUtils {
    private var job: Job? = null

    fun startUpdatingPort(context: Context) {
        job = Scopes.getMainCoroutine().launch {
            while (isActive) {
                if(BleManager.isUsbEnabled()){
                    BittelUsbManager2.updateBlePort()
                    Timber.tag("startUpdatingPort").d("updateUsbPort")
                }else if (BleManager.isBluetoothEnabled()) {
                    DataManager.getClientConnection(context).updateBlePort()
                    Timber.tag("startUpdatingPort").d("updateBlePort")
                }
                delay(30000) // Wait for 10 seconds
            }
        }
    }

    fun stopUpdatingPort() {
        job?.cancel() // Cancels the coroutine

    }
}