package com.commcrete.stardust.util.connectivity


import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.transport.ConnectionManager
import com.commcrete.stardust.transport.TransportId
import com.commcrete.stardust.transport.TransportRegistry
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
    private val connectionTimeout = 10000L
    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        ConnectionManager.requestReconnect(TransportId.USB, "USB ping timeout")
    }

    fun startUpdatingPort() {
        job = Scopes.getMainCoroutine().launch {
            while (isActive) {
                TransportRegistry.active()?.let { transport ->
                    transport.updateBlePort()
                    Timber.tag("startUpdatingPort").d("updatePort over ${transport.id}")
                }
                delay(20000)
            }
        }

        jobPing = Scopes.getDefaultCoroutine().launch {
            while (isActive) {
                DataManager.getClientConnection().sendPing()
                resetConnectionTimer()
                delay(connectionTimeout)
            }
        }
    }

    fun onPingReceived () {
        removeConnectionTimer()
//        DataManager.getUsbManager(DataManager.context).resetReconnect()
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
        } catch (e : Exception) {
            e.printStackTrace()
        }
    }

    fun stopUpdatingPort() {
        job?.cancel() // Cancels the coroutine
        jobPing?.cancel() // Cancels the coroutine

    }
}