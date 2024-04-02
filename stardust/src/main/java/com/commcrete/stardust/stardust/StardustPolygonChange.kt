package com.commcrete.stardust.stardust

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil

object StardustPolygonChange {

    internal val clientConnection : ClientConnection = DataManager.getClientConnection(DataManager.context)
    var isProcessRunning = false

    private val runnable : Runnable = kotlinx.coroutines.Runnable {
        isProcessRunning = false
    }
    private val handler : Handler = Handler(Looper.getMainLooper())

    private fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 40000)
    }

    fun startProcess (beamNum : String, context: Context) {
        isProcessRunning = true
        requestNewFreq(beamNum, context)
    }

    private fun requestNewFreq (beamNum : String, context: Context) {
//        SharedPreferencesUtil.getAppUser(context)?.let {
//            Scopes.getDefaultCoroutine().launch {
//                val bittel = it.bittelId
//                val polygon = Polygon(bittel, beamNum)
//                val change = PolygonRepository(BittelRetrofit.PolygonService).requestChange(polygon)
//                if(change?.isSuccessful == true) {
//
//                }
//            }
//        }
        resetTimer()

    }
    private fun sendNewFreq (context: Context) {
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                // TODO: add number of polygon
                val frequencyHRSatelliteTXBytes = (1.0 * StardustConfigurationParser.MHz).toInt().intToByteArray().reversedArray()
                val frequencyHRRadioTXBytes = (1.0 * StardustConfigurationParser.MHz).toInt().intToByteArray().reversedArray()
                val frequencyLRSatelliteTXBytes = (1.0 * StardustConfigurationParser.MHz).toInt().intToByteArray().reversedArray()
                val frequencyHRSatelliteRXBytes = (1.0 * StardustConfigurationParser.MHz).toInt().intToByteArray().reversedArray()
                val frequencyHRRadioRXBytes = (1.0 * StardustConfigurationParser.MHz).toInt().intToByteArray().reversedArray()
                val frequencyLRSatelliteRXBytes = (1.0 * StardustConfigurationParser.MHz).toInt().intToByteArray().reversedArray()
                val data = StardustPackageUtils.byteArrayToIntArray(frequencyHRSatelliteTXBytes +
                        frequencyHRRadioTXBytes + frequencyLRSatelliteTXBytes + frequencyHRSatelliteRXBytes +
                        frequencyHRRadioRXBytes +  frequencyLRSatelliteRXBytes)
                val txPackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_FREQ,
                    data = data)
                clientConnection.addMessageToQueue(txPackage)
            }
        }
        resetTimer()
    }

    fun updateServerOfFreqChange() {
        // TODO: Notify server of successful update then save config
        resetTimer()
    }
    fun sendSaveConfig (context: Context) {
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val configurationSavePackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.SAVE_CONFIGURATION)
                clientConnection.addMessageToQueue(configurationSavePackage)
            }
        }
        resetTimer()
    }

    fun updateServerOfSaveConfigSuccess() {
        // TODO: Notify server of successful save config

        isProcessRunning = false
    }
}