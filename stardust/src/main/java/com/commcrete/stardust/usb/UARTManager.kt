package com.commcrete.stardust.usb


import android.content.Context.USB_SERVICE
import android.hardware.usb.UsbManager
import com.commcrete.stardust.util.DataManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Executors

class UARTManager() {
    private var serialPort: UsbSerialPort? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var ioManager: SerialInputOutputManager? = null

    @Volatile
    private var ctsPolling = false
    private var ctsThread: Thread? = null

    interface UARTCallback {
        fun onReceivedData(data: ByteArray)
        fun onError(message: String)
    }

    interface CTSChange {
        fun onCTSChanged (isActive : Boolean)
    }

    fun connectDevice(callback : SerialInputOutputManager.Listener, mPort : Int, onCTSChange: CTSChange? = null) : Boolean{
        val usbManager = DataManager.appContext.getSystemService(USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Timber.tag("SerialInputOutputManager").d("availableDrivers.isEmpty()")
            return false
        }
        var driver : UsbSerialDriver? = null
        // Open a connection to the first available driver.
        for (mDriver in availableDrivers) {
            for (port in mDriver.ports) {
                Timber.tag("SerialInputOutputManager").d("mPort : $mPort")
                Timber.tag("SerialInputOutputManager").d("port.device.deviceId : ${port.device.deviceId}")
                if(port.device.deviceId == mPort) {
                    driver = mDriver
                }
            }
        }
        if( driver == null) {
            return false
        }
        Timber.tag("SerialInputOutputManager").d("availableDrivers[0]")
        val connection = usbManager.openDevice(driver.device) ?: return false
        Timber.tag("SerialInputOutputManager").d("usbManager.openDevice(driver.device)")
        try {
            serialPort = driver.ports[0] // Most devices have just one port (port 0)
            serialPort?.open(connection)
            serialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Timber.tag("SerialInputOutputManager").d("setParameters")
            ioManager = SerialInputOutputManager(serialPort).apply {
                Timber.tag("SerialInputOutputManager").d("SerialInputOutputManager(serialPort).apply ")
                listener = callback
                executor.submit(this)
                Timber.tag("SerialInputOutputManager").d("executor.submit")
            }
            if (onCTSChange != null ) {
                startCtsPolling(onCTSChange)
            }

            return true



        } catch (e: IOException) {
            Timber.tag("SerialInputOutputManager").e("connectDevice : " + e)
            return false
            // Handle error
        }
    }

    /**
     * Polls the CTS line (used as the PTT button signal) on a background thread. The thread is
     * stoppable via [ctsPolling] + interrupt so [disconnect] can tear it down — previously this was
     * a `while(true)` thread with no reference, leaking one busy-poll thread per audio connect.
     */
    private fun startCtsPolling(onCTSChange: CTSChange) {
        var previousCtsStatus = serialPort?.cts
        ctsPolling = true
        ctsThread = Thread {
            while (ctsPolling) {
                try {
                    val currentCtsStatus = serialPort?.cts
                    if (currentCtsStatus != previousCtsStatus) {
                        previousCtsStatus = currentCtsStatus
                        Timber.tag("SerialInputOutputManager").d("CTS changed to ${if (currentCtsStatus == true) "ON" else "OFF"}")
                        onCTSChange.onCTSChanged(currentCtsStatus == true)
                    }
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // Transient read error; keep polling until explicitly stopped.
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun send(data: ByteArray) {
        try {
            serialPort?.write(data, 3000)
        } catch (e: IOException) {
            Timber.tag("SerialInputOutputManager").e("send : " + e)
            // Handle error
        }
    }

    fun disconnect() {
        ctsPolling = false
        ctsThread?.interrupt()
        ctsThread = null
        try {
            ioManager?.stop()
            serialPort?.close()
        } catch (e : Exception) {
            e.printStackTrace()
        } finally {
            ioManager = null
            serialPort = null
            executor.shutdownNow()
        }
    }
}