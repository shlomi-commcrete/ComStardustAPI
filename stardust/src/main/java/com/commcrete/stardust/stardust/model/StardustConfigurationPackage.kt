package com.commcrete.stardust.stardust.model

import android.content.Context
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.stardust.model.StardustConfigurationParser.StardustCarrier
import com.commcrete.stardust.stardust.model.StardustConfigurationParser.StardustTypeFunctionality
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
    val deviceSerial: String,
    val sosXCVR: Int,
    val sosDestinations: List<String>
) {
    private val TAG = "PresetValidation"

    fun getCurrentRadios (preset : StardustConfigurationParser.CurrentPreset? = null) : Radios? {
        return getPresetData(preset ?: currentPreset)?.let {
            Radios(
                it.xcvrList[0].functionality,
                it.xcvrList[1].functionality,
                it.xcvrList[2].functionality,
                StardustTypeFunctionality.ST,
            )
        }
    }

    private fun getPresetData(preset : StardustConfigurationParser.CurrentPreset): StardustConfigurationParser.Preset? {
        return presets[preset.value]
    }

    fun getCenterFrequency(preset : StardustConfigurationParser.CurrentPreset = currentPreset): Frequency? {
        return getPresetData(preset)?.xcvrList?.firstOrNull()?.let {
            val delta: Double = 25.0 / 3.0 / 1000.0
            val (rx, tx) = when(it.carrier) {
                StardustCarrier.Carrier1 -> (it.rxFrequency + delta) to (it.txFrequency + delta)
                StardustCarrier.Carrier2 -> it.rxFrequency to it.txFrequency
                StardustCarrier.Carrier3 -> (it.rxFrequency - delta) to (it.txFrequency - delta)
            }
            Frequency(rx = rx + frequencyLORX, tx = tx + frequencyLOTX)
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

    data class Frequency(val rx: Double, val tx: Double)
}

