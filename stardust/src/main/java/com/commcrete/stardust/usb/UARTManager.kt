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
        android.util.Log.d("ConfigDebug", "UART connectDevice targetDeviceId=$mPort driversFound=${availableDrivers.size} context: ${DataManager.appContext}")
        UsbDiag.log(
            "UART.connectDevice",
            "ENTER targetDeviceId=$mPort driversFound=${availableDrivers.size} wantsCts=${onCTSChange != null} " +
                "attachedDevices=${usbManager.deviceList.size}"
        )
        if (availableDrivers.isEmpty()) {
            Timber.tag("SerialInputOutputManager").d("availableDrivers.isEmpty()")
            android.util.Log.w("ConfigDebug", "UART ABORT — UsbSerialProber found NO drivers (device not recognised by the default prober)")
            UsbDiag.verdict(
                "UsbSerialProber found NO drivers. The device is attached but the default prober does " +
                    "not recognise its VID/PID — a custom ProbeTable is needed, or the device is not a " +
                    "supported serial chip. Compare the vid/pid in the UsbDiag 'match' lines above."
            )
            return false
        }
        var driver : UsbSerialDriver? = null
        // Open a connection to the first available driver.
        for (mDriver in availableDrivers) {
            for (port in mDriver.ports) {
                Timber.tag("SerialInputOutputManager").d("mPort : $mPort")
                Timber.tag("SerialInputOutputManager").d("port.device.deviceId : ${port.device.deviceId}")
                android.util.Log.d("ConfigDebug", "UART candidate driver=${mDriver.javaClass.simpleName} portDeviceId=${port.device.deviceId} productName='${port.device.productName}'")
                UsbDiag.log(
                    "UART.probe",
                    "candidate driver=${mDriver.javaClass.simpleName} portDeviceId=${port.device.deviceId} " +
                        "matchesTarget=${port.device.deviceId == mPort} ${UsbDiag.describe(port.device)}"
                )
                if(port.device.deviceId == mPort) {
                    driver = mDriver
                }
            }
        }
        if( driver == null) {
            android.util.Log.w("ConfigDebug", "UART ABORT — no driver whose port.device.deviceId == $mPort")
            UsbDiag.verdict(
                "No driver matched deviceId=$mPort. The prober found ${availableDrivers.size} driver(s) " +
                    "but none for the device we were granted permission for — the deviceId changed " +
                    "(re-enumeration between grant and open) or the device is a different port."
            )
            return false
        }
        Timber.tag("SerialInputOutputManager").d("availableDrivers[0]")
        UsbDiag.log(
            "UART.connectDevice",
            "matched driver=${driver.javaClass.simpleName} ports=${driver.ports.size} " +
                "hasPermission=${runCatching { usbManager.hasPermission(driver.device) }.getOrNull()} — calling openDevice()"
        )
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            android.util.Log.w("ConfigDebug", "UART ABORT — usbManager.openDevice() returned null (permission not actually held?)")
            UsbDiag.verdict(
                "openDevice() returned NULL. hasPermission=" +
                    "${runCatching { usbManager.hasPermission(driver.device) }.getOrNull()} — if that is false, " +
                    "the permission was never actually granted; if true, another process holds the device."
            )
            return false
        }
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

            UsbDiag.log(
                "UART.connectDevice",
                "OPEN OK — 115200/8/N/1, IO manager submitted, ctsPolling=${onCTSChange != null}. " +
                    "Waiting for the radio to send something (onNewData)."
            )
            return true



        } catch (e: IOException) {
            Timber.tag("SerialInputOutputManager").e("connectDevice : " + e)
            android.util.Log.w("ConfigDebug", "UART ABORT — IOException opening/configuring the serial port: ${e.message}", e)
            UsbDiag.error("UART.connectDevice", "IOException on open/setParameters — port not usable", e)
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

    /** @return true if the bytes were handed to the port; false if the port is closed or the write failed. */
    fun send(data: ByteArray): Boolean {
        val port = serialPort ?: run {
            UsbDiag.warn("UART.send", "DROPPED ${data.size} bytes — serialPort is NULL (port closed or never opened)")
            return false
        }
        return try {
            port.write(data, 3000)
            true
        } catch (e: IOException) {
            Timber.tag("SerialInputOutputManager").e("send : " + e)
            // Surfaced rather than swallowed: a write that keeps failing is the other way a dead
            // link stays invisible (the read thread's onRunError is the first). Callers can decide
            // what to do; nothing is torn down here, because a single transient write failure is
            // not proof the port is gone.
            UsbDiag.error("UART.send", "write FAILED for ${data.size} bytes — link may be dead", e)
            false
        }
    }

    fun disconnect() {
        UsbDiag.log("UART.disconnect", "closing port=${serialPort != null} ioManager=${ioManager != null} ctsPolling=$ctsPolling")
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