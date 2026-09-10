package com.commcrete.bittell.util.bittel_package

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import timber.log.Timber
import java.io.File
import java.io.IOException

class UARTManager(private val context: Context) {
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    interface UARTCallback {
        fun onReceivedData(data: ByteArray)
        fun onError(message: String)
    }

    interface CTSChange {
        fun onCTSChanged (isActive : Boolean)
    }

    fun connectDevice(callback : SerialInputOutputManager.Listener, mPort : Int, onCTSChange: CTSChange? = null) : Boolean{
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
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
                // Use start() rather than executor.submit(this).
                //
                // submit(this) resolves to ExecutorService.submit(Runnable), which binds to
                // SerialInputOutputManager only because it implements Runnable in the version we
                // compile against. If a different copy of com.hoho.android.usbserial is on the
                // runtime classpath (e.g. one shadowing ours from the host app) and that copy does
                // not implement Runnable, the call dies with
                //   ClassCastException: SerialInputOutputManager cannot be cast to java.lang.Runnable
                // start() carries no such dependency, is the API upstream has recommended since
                // 3.3.3, and pairs with the ioManager?.stop() already in disconnect(). It also
                // avoids the thread leak the old executor was never shut down to prevent.
                start()
                Timber.tag("SerialInputOutputManager").d("ioManager.start()")
            }
            if (onCTSChange != null ) {
                var previousCtsStatus = serialPort?.cts

                Thread {
                    while (true) {
                        try {
                            val currentCtsStatus = serialPort?.cts
                            if (currentCtsStatus != previousCtsStatus) {
                                previousCtsStatus = currentCtsStatus
                                Timber.tag("SerialInputOutputManager").d("CTS changed to ${if (currentCtsStatus == true) "ON" else "OFF"}")

                                // Perform actions based on CTS status
                                onCTSChange.onCTSChanged(currentCtsStatus == true)
                            }

                            // Sleep for a short period to avoid excessive CPU usage
                            try {
                                Thread.sleep(50)
                            } catch (e: InterruptedException) {
                            }
                        }catch (e : Exception) {
                        }

                    }
                }.start()
            }

            return true



        } catch (e: IOException) {
            Timber.tag("SerialInputOutputManager").e("connectDevice : " + e)
            return false
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
        try {
            ioManager?.stop()
            serialPort?.close()
        } catch (e : Exception) {
            e.printStackTrace()
        }
    }
}