package com.commcrete.stardust.stardust.model

data class StardustConfigurationPackage(
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
    var radioHRDeduction : Float,
    var radioLRDeduction : Float,
    var radioLODeduction : Float,
    var stardustType : StardustConfigurationParser.StardustType,
    var portType : StardustConfigurationParser.PortType,
    var crcType : Int,
    var serverByteType : Int,
    var frequencyLOTX : Double,
    var frequencyLORX : Double,
    var frequencyLRTX : Double,
    var frequencyLRRX : Double,
    var powerBattery : Float,
    var power12V : Float,
    var batteryChargeStatus : StardustConfigurationParser.StardustBatteryCharge,
    var mcuTemperature : Int,
    var rdpLevel : StardustConfigurationParser.StardustRDPLevel,
    var snifferMode: StardustConfigurationParser.SnifferMode,
    var StardustId : String,
    var appId : String,
) {
    fun setFromDefaults (StardustDefaults: StardustDefaults.StardustDefaults) {
        frequencyHRSatelliteTXBytes = StardustDefaults.frequencyHRSatelliteTXBytes
        frequencyHRRadioTXBytes = StardustDefaults.frequencyHRRadioTXBytes
        frequencyLRSatelliteTXBytes = StardustDefaults.frequencyLRSatelliteTXBytes
        frequencyHRSatelliteRXBytes = StardustDefaults.frequencyHRSatelliteRXBytes
        frequencyHRRadioRXBytes = StardustDefaults.frequencyHRRadioRXBytes
        frequencyLRSatelliteRXBytes = StardustDefaults.frequencyLRSatelliteRXBytes
        powerLOTX = StardustDefaults.powerLOTX
        powerLORX = StardustDefaults.powerLORX
        powerHRTX = StardustDefaults.powerHRTX
        powerLRTX = StardustDefaults.powerLRTX
        portType = StardustDefaults.portType
        stardustType = StardustDefaults.stardustType
        serverByteType = StardustDefaults.serverByteType
    }
}
