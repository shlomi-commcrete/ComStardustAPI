package com.commcrete.stardust.stardust.model


data class StardustConfigurationPackage(

    //Preset 1
    val frequencyXcvr1SatelliteTXBytesPreset1 : Double,
    val frequencyXcvr1SatelliteRXBytesPreset1 : Double,
    val frequencyXcvr1PowerBytesPreset1 : Int,
    val functionalityXcvr1Preset1 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr2SatelliteTXBytesPreset1 : Double,
    val frequencyXcvr2SatelliteRXBytesPreset1 : Double,
    val frequencyXcvr2PowerBytesPreset1 : Int,
    val functionalityXcvr2Preset1 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr3SatelliteTXBytesPreset1 : Double,
    val frequencyXcvr3SatelliteRXBytesPreset1 : Double,
    val frequencyXcvr3PowerBytesPreset1 : Int,
    val functionalityXcvr3Preset1 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr4SatelliteTXBytesPreset1 : Double,
    val frequencyXcvr4SatelliteRXBytesPreset1 : Double,
    val frequencyXcvr4PowerBytesPreset1 : Int,
    val functionalityXcvr4Preset1 : StardustConfigurationParser.StardustTypeFunctionality,

    //Preset 2
    val frequencyXcvr1SatelliteTXBytesPreset2 : Double,
    val frequencyXcvr1SatelliteRXBytesPreset2 : Double,
    val frequencyXcvr1PowerBytesPreset2 : Int,
    val functionalityXcvr1Preset2 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr2SatelliteTXBytesPreset2 : Double,
    val frequencyXcvr2SatelliteRXBytesPreset2 : Double,
    val frequencyXcvr2PowerBytesPreset2 : Int,
    val functionalityXcvr2Preset2 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr3SatelliteTXBytesPreset2 : Double,
    val frequencyXcvr3SatelliteRXBytesPreset2 : Double,
    val frequencyXcvr3PowerBytesPreset2 : Int,
    val functionalityXcvr3Preset2 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr4SatelliteTXBytesPreset2 : Double,
    val frequencyXcvr4SatelliteRXBytesPreset2 : Double,
    val frequencyXcvr4PowerBytesPreset2 : Int,
    val functionalityXcvr4Preset2 : StardustConfigurationParser.StardustTypeFunctionality,

    //Preset 3
    val frequencyXcvr1SatelliteTXBytesPreset3 : Double,
    val frequencyXcvr1SatelliteRXBytesPreset3 : Double,
    val frequencyXcvr1PowerBytesPreset3 : Int,
    val functionalityXcvr1Preset3 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr2SatelliteTXBytesPreset3 : Double,
    val frequencyXcvr2SatelliteRXBytesPreset3 : Double,
    val frequencyXcvr2PowerBytesPreset3 : Int,
    val functionalityXcvr2Preset3 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr3SatelliteTXBytesPreset3 : Double,
    val frequencyXcvr3SatelliteRXBytesPreset3 : Double,
    val frequencyXcvr3PowerBytesPreset3 : Int,
    val functionalityXcvr3Preset3 : StardustConfigurationParser.StardustTypeFunctionality,

    val frequencyXcvr4SatelliteTXBytesPreset3 : Double,
    val frequencyXcvr4SatelliteRXBytesPreset3 : Double,
    val frequencyXcvr4PowerBytesPreset3 : Int,
    val functionalityXcvr4Preset3 : StardustConfigurationParser.StardustTypeFunctionality,

    //LO Outputs
    var frequencyLOTX : Double,
    var frequencyLORX : Double,
    var powerLOTX : Int,
    var powerLORX : Int,

    //Current Preset
    val currentPreset: StardustConfigurationParser.CurrentPreset,

    var bittelType : StardustConfigurationParser.StardustType,
    var portType : StardustConfigurationParser.PortType,
    var crcType : Int,
    var serverByteType : Int,
    var debugIgnoreCanTransmit : Boolean,
    var snifferMode: StardustConfigurationParser.SnifferMode,
    var appId : String,
    val antenna : StardustConfigurationParser.AntennaType,
    var radioLODeduction : Float,
    var stardustId : String,
    var power12V : Float,
    var powerBattery : Float,
    var batteryChargeStatus : StardustConfigurationParser.StardustBatteryCharge,
    var mcuTemperature : Int,
    var rdpLevel : StardustConfigurationParser.StardustRDPLevel,
){
    fun getCurrentRadios () : Radios {
        if (currentPreset == StardustConfigurationParser.CurrentPreset.PRESET1) {
            return Radios(
                functionalityXcvr1Preset1,
                functionalityXcvr2Preset1,
                functionalityXcvr3Preset1,
                StardustConfigurationParser.StardustTypeFunctionality.ST,
            )
        } else if (currentPreset == StardustConfigurationParser.CurrentPreset.PRESET2) {
            return Radios(
                functionalityXcvr1Preset2,
                functionalityXcvr2Preset2,
                functionalityXcvr3Preset2,
                StardustConfigurationParser.StardustTypeFunctionality.ST,
            )
        } else if (currentPreset == StardustConfigurationParser.CurrentPreset.PRESET3) {
            return Radios(
                functionalityXcvr1Preset3,
                functionalityXcvr2Preset3,
                functionalityXcvr3Preset3,
                StardustConfigurationParser.StardustTypeFunctionality.ST,
            )
        } else {
            return Radios(
                functionalityXcvr1Preset1,
                functionalityXcvr2Preset1,
                functionalityXcvr3Preset1,
                StardustConfigurationParser.StardustTypeFunctionality.ST,
            )
        }
    }

    data class Radios (
        val xcvr1 : StardustConfigurationParser.StardustTypeFunctionality,
        val xcvr2 : StardustConfigurationParser.StardustTypeFunctionality,
        val xcvr3 : StardustConfigurationParser.StardustTypeFunctionality,
        val xcvr4 : StardustConfigurationParser.StardustTypeFunctionality,
    )
}

