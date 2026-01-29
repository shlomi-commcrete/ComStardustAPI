package com.commcrete.stardust.stardust.model

import android.content.Context
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.stardust.model.StardustConfigurationParser.StardustTypeFunctionality
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.SharedPreferencesUtil.KEY_LAST_CARRIERS1
import com.commcrete.stardust.util.SharedPreferencesUtil.KEY_LAST_CARRIERS2
import com.commcrete.stardust.util.SharedPreferencesUtil.KEY_LAST_CARRIERS3


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
) {
    private val TAG = "PresetValidation"

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

    fun presetsWithoutConfig(context: Context): List<StardustConfigurationParser.Preset> {
        return presets.filter { preset ->

            val (defaultFunctionalities, requiredFunctionalities) = preset.collectFunctionalities()
            hasMissingRequiredFunctionalities(defaultFunctionalities, requiredFunctionalities)
            // No missing required functionality → preset is valid
//            if (!hasMissingRequiredFunctionalities(defaultFunctionalities, requiredFunctionalities)) {
//                return@filter false
//            }

//            val currentPreset = preset.currentPreset ?: return@filter true
//
//            val localFunctionalities = getLocalFunctionalitiesByPreset(currentPreset, context) ?: return@filter true
//
//            hasMissingRequiredFunctionalities(localFunctionalities, requiredFunctionalities)
        }
    }


    private fun StardustConfigurationParser.Preset.collectFunctionalities(): Pair<Set<FunctionalityType>, Set<FunctionalityType>> {
        val presetFunctionalities = mutableSetOf<FunctionalityType>()
        val requiredFunctionalities = mutableSetOf<FunctionalityType>()
        for (xcvr in xcvrList) {
            // Actual: only for non-default frequency XCVRs
            if (!xcvr.hasDefaultFrequency() && xcvr.functionality != StardustTypeFunctionality.ST) {
                presetFunctionalities.addAll(xcvr.getOptions())
                // Required: all allowed options with valid bitwise
                requiredFunctionalities.addAll(
                    xcvr.functionality.getAllowedFunctionalityOptions().filter { it.bitwise != -1 }
                )

            }
        }

        return presetFunctionalities to requiredFunctionalities
    }

    private fun getLocalFunctionalitiesByPreset(preset : StardustConfigurationParser.CurrentPreset, context: Context) : Set<FunctionalityType>? {
        val localKey = when (preset) {
            StardustConfigurationParser.CurrentPreset.PRESET1 -> KEY_LAST_CARRIERS1
            StardustConfigurationParser.CurrentPreset.PRESET2 -> KEY_LAST_CARRIERS2
            StardustConfigurationParser.CurrentPreset.PRESET3 -> KEY_LAST_CARRIERS3
        }
        return SharedPreferencesUtil.getCarrier(context, localKey)?.activeFunctionalities
    }

    private fun hasMissingRequiredFunctionalities(
        actual: Set<FunctionalityType>,
        required: Set<FunctionalityType>
    ): Boolean {
        return required.any { it !in actual }
    }

    data class Radios (
        val xcvr1 : StardustConfigurationParser.StardustTypeFunctionality,
        val xcvr2 : StardustConfigurationParser.StardustTypeFunctionality,
        val xcvr3 : StardustConfigurationParser.StardustTypeFunctionality,
        val xcvr4 : StardustConfigurationParser.StardustTypeFunctionality,
    )
}

