package com.commcrete.stardust.stardust.model

import android.content.Context
import com.commcrete.stardust.util.FileUtils
import com.google.gson.Gson
import com.google.gson.JsonObject


object StardustDefaults {

    fun getStardustDefaults (context: Context) : StardustDefaults {
        val file = FileUtils.readFile(context = context,
            fileType = ".json", fileName = "defaultConfig", folderName = "config")
        val defaults = Gson().fromJson(file, StardustDefaults::class.java)
        val json = Gson().fromJson(file, JsonObject::class.java)
        defaults.portType =  if (json.get("uartPortPreference").asInt == 0)
        StardustConfigurationParser.PortType.BLUETOOTH else StardustConfigurationParser.PortType.USB
        defaults.stardustType = StardustConfigurationParser.StardustType.values()[json.get("stardustTypePreference").asInt]
        return defaults
    }

    data class StardustDefaults (
        var frequencyHRSatelliteTXBytes : Double,
        var frequencyHRRadioTXBytes : Double,
        var frequencyLRSatelliteTXBytes : Double,
        var frequencyHRSatelliteRXBytes : Double,
        var frequencyHRRadioRXBytes : Double,
        var frequencyLRSatelliteRXBytes : Double,
        var powerLOTX : Int,
        var powerLORX : Int,
        var powerHRTX : Int,
        var powerLRTX : Int,
        var stardustType : StardustConfigurationParser.StardustType,
        var portType : StardustConfigurationParser.PortType,
        var serverByteType : Int,
    )
}