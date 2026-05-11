package com.commcrete.stardust.stardust

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.RegisteredUserUtils
import com.commcrete.stardust.util.SharedPreferencesUtil

object StardustPolygonChange {

    internal val clientConnection : ClientConnection = DataManager.getClientConnection()
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

    fun startProcess (beamNum : String) {
        isProcessRunning = true
        requestNewFreq(beamNum)
    }

    private fun requestNewFreq (beamNum : String) {
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
    private fun sendNewFreq() {
        val user = RegisteredUserUtils.mRegisterUser.value ?: return
        val src = user.appId ?: return
        val dst = user.deviceId ?: return

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
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_POLYGON_FREQ,
            data = data)
        clientConnection.addMessageToQueue(txPackage)

        resetTimer()
    }

    fun updateServerOfFreqChange() {
        // TODO: Notify server of successful update then save config
        resetTimer()
    }
    fun sendSaveConfig() {
        val user = RegisteredUserUtils.mRegisterUser.value ?: return
        val src = user.appId ?: return
        val dst = user.deviceId ?: return

        val configurationSavePackage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SAVE_CONFIGURATION)
        clientConnection.addMessageToQueue(configurationSavePackage)

        resetTimer()
    }

    fun updateServerOfSaveConfigSuccess() {
        // TODO: Notify server of successful save config

        isProcessRunning = false
    }
}