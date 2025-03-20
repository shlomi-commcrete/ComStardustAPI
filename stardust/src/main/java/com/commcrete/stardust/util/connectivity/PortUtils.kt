package com.commcrete.stardust.util.connectivity

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private var jobPing: Job? = null

    private val handler : Handler = Handler(Looper.getMainLooper())
    private val connectionTimeout = 1000L
    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        Scopes.getMainCoroutine().launch {
            BleManager.isUSBConnected = false
            BleManager.usbConnectionStatus.value = false
            BleManager.updateStatus ()
        }
        DataManager.getUsbManager(DataManager.context).reconnectToDevice()
    }

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
                delay(20000)
            }
        }

        jobPing = Scopes.getDefaultCoroutine().launch {
            while (isActive) {
                DataManager.getClientConnection(context).sendPing()
                resetConnectionTimer()
                delay(10000)
            }
        }
    }

    fun onPingReceived () {
        removeConnectionTimer()
    }

    private fun resetConnectionTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, connectionTimeout)
    }

    fun removeConnectionTimer() {
        try {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    fun stopUpdatingPort() {
        job?.cancel() // Cancels the coroutine
        jobPing?.cancel() // Cancels the coroutine

    }
}