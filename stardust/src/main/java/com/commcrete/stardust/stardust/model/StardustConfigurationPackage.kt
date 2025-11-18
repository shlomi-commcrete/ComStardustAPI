package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.enums.LicenseType


data class StardustConfigurationPackage(

    val presets: List<StardustConfigurationParser.Preset>,
    //LO Outputs
    var frequencyLOTX: Double,
    var frequencyLORX: Double,
    var powerLOTX: Int,
    var powerLORX: Int,

    //Current Preset
    val currentPreset: StardustConfigurationParser.CurrentPreset,

    var bittelType: StardustConfigurationParser.StardustType,
    var portType: StardustConfigurationParser.PortType,
    var crcType: Int,
    var serverByteType: Int,
    var debugIgnoreCanTransmit: Boolean,
    var snifferMode: StardustConfigurationParser.SnifferMode,
    var appId: String,
    val antenna: StardustConfigurationParser.AntennaType,
    var radioLODeduction: Float,
    var stardustId: String,
    var power12V: Float,
    var powerBattery: Float,
    var batteryChargeStatus: StardustConfigurationParser.StardustBatteryCharge,
    var mcuTemperature: Int,
    var rdpLevel: StardustConfigurationParser.StardustRDPLevel,
    var licenseType: LicenseType,
    val deviceModel: String,
    val deviceSerial: String
){
    fun getCurrentRadios (preset : StardustConfigurationParser.CurrentPreset? = null) : Radios {
        val presetToCheck = preset?:currentPreset
        if (presetToCheck == StardustConfigurationParser.CurrentPreset.PRESET1) {
            val preset = presets.get(0)
            return Radios(
                preset.xcvrList.get(0).functionality,
                preset.xcvrList.get(1).functionality,
                preset.xcvrList.get(2).functionality,
                StardustConfigurationParser.StardustTypeFunctionality.ST,
            )
        } else if (presetToCheck == StardustConfigurationParser.CurrentPreset.PRESET2) {
            val preset = presets.get(1)
            return Radios(
                preset.xcvrList.get(0).functionality,
                preset.xcvrList.get(1).functionality,
                preset.xcvrList.get(2).functionality,
                StardustConfigurationParser.StardustTypeFunctionality.ST,
            )
        } else if (presetToCheck == StardustConfigurationParser.CurrentPreset.PRESET3) {
            val preset = presets.get(2)
            return Radios(
                preset.xcvrList.get(0).functionality,
                preset.xcvrList.get(1).functionality,
                preset.xcvrList.get(2).functionality,
                StardustConfigurationParser.StardustTypeFunctionality.ST,
            )
        } else {
            val preset = presets.get(0)
            return Radios(
                preset.xcvrList.get(0).functionality,
                preset.xcvrList.get(1).functionality,
                preset.xcvrList.get(2).functionality,
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

