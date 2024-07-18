package com.commcrete.bittell.util.bittel_package

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.Executors

class UARTManager(private val context: Context) {
    private var serialPort: UsbSerialPort? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var ioManager: SerialInputOutputManager? = null

    interface UARTCallback {
        fun onReceivedData(data: ByteArray)
        fun onError(message: String)
    }

    fun connectDevice(callback : SerialInputOutputManager.Listener) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Timber.tag("SerialInputOutputManager").d("availableDrivers.isEmpty()")
            return
        }

        // Open a connection to the first available driver.
        val driver = availableDrivers[0]
        Timber.tag("SerialInputOutputManager").d("availableDrivers[0]")
        val connection = usbManager.openDevice(driver.device) ?: return
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

        } catch (e: IOException) {
            Timber.tag("SerialInputOutputManager").e("connectDevice : " + e)

            // Handle error
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
        ioManager?.stop()
        serialPort?.close()
    }
}
