package com.commcrete.stardust.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.Location
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.StardustAPI
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ble.BleScanner
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.location.PollingUtils
import com.commcrete.stardust.stardust.StardustPackageHandler
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.audio.PttInterface
import com.commcrete.stardust.util.audio.RecorderUtils

object DataManager : StardustAPI, PttInterface{

    private var clientConnection : ClientConnection?  = null
    private var bittelPackageHandler : StardustPackageHandler? = null
    private var pollingUtils : PollingUtils? = null
    lateinit var context : Context
    private var bleScanner : BleScanner? = null
    private var source : String? = null
    private var destination : String? = null

    fun requireContext (context: Context){
        this.context = context
    }

    internal fun getClientConnection (context: Context) : ClientConnection {
        if(clientConnection == null) {
            clientConnection = ClientConnection(context = context)
        }

        getStardustPackageHandler(context)

        return clientConnection!!
    }

    internal fun getStardustPackageHandler(context: Context): StardustPackageHandler {
        if(bittelPackageHandler == null){
            bittelPackageHandler = StardustPackageHandler(context, clientConnection)
        }
        return bittelPackageHandler!!
    }

    internal fun getPollingUtils () : PollingUtils{
        if(pollingUtils == null){
            pollingUtils = PollingUtils(context)
        }
        return pollingUtils!!
    }

    override fun sendMessage(stardustAPIPackage: StardustAPIPackage, text: String) {

    }
    @SuppressLint("MissingPermission")
    override fun startPTT(stardustAPIPackage: StardustAPIPackage) {
        this.source = stardustAPIPackage.source
        RecorderUtils.init(this)
        RecorderUtils.onRecord(true, stardustAPIPackage.destination)
    }
    @SuppressLint("MissingPermission")
    override fun stopPTT(stardustAPIPackage: StardustAPIPackage) {
        RecorderUtils.onRecord(false, stardustAPIPackage.destination)
    }

    override fun sendLocation(stardustAPIPackage: StardustAPIPackage, location: Location) {
    }


    override fun init(context: Context) {
        requireContext(context)
    }

    override fun scanForDevice() : MutableLiveData<List<ScanResult>> {
        val bleScanner = getBleScanner(this.context)
        bleScanner.startScan()
        return bleScanner.getScanResultsLiveData()
    }

    override fun connectToDevice(device: BluetoothDevice) {
        val bleScanner = getBleScanner(this.context)
        bleScanner.stopScan()
        this.bleScanner = null
    }

    override fun disconnectFromDevice() {
    }

    override fun getSource(): String {
        return this.source ?: ""
    }

    override fun getDestenation(): String? {
        return this.destination ?: ""
    }

    override fun sendDataToBle(bittelPackage: StardustPackage) {

    }

    fun getBleScanner (context: Context): BleScanner {
        if(this.bleScanner == null) {
            bleScanner = BleScanner(this.context)
        }
        return bleScanner!!
    }
}