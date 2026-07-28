package com.commcrete.stardust.stardust.model


import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.stardust.model.StardustConfigurationParser.CarrierType

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

    internal fun getPresetData(preset : StardustConfigurationParser.CurrentPreset): StardustConfigurationParser.Preset? {
        return presets[preset.value]
    }

    fun getCenterFrequency(preset : StardustConfigurationParser.CurrentPreset = currentPreset): Frequency? {
        return getPresetData(preset)?.xcvrList?.firstOrNull()?.let {
            val delta: Double = 25.0 / 3.0 / 1000.0
            val (rx, tx) = when(it.carrier.index) {
                0 -> (it.rxFrequency + delta) to (it.txFrequency + delta)
                1 -> it.rxFrequency to it.txFrequency
                2 -> (it.rxFrequency - delta) to (it.txFrequency - delta)
                else -> return null
            }
            Frequency(rx = rx + frequencyLORX, tx = tx + frequencyLOTX)
        }
    }

    fun presetsWithoutConfig(): List<StardustConfigurationParser.Preset> {
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
            if (!xcvr.hasDefaultFrequency() && xcvr.carrier.type != CarrierType.ST) {
                presetFunctionalities.addAll(xcvr.getOptions())
                // Required: all allowed options with valid bitwise
                requiredFunctionalities.addAll(
                    xcvr.carrier.type.getAllowedFunctionalityOptions().filter { it.bitwise != -1 }
                )

            }
        }

        return presetFunctionalities to requiredFunctionalities
    }

    private fun hasMissingRequiredFunctionalities(
        actual: Set<FunctionalityType>,
        required: Set<FunctionalityType>
    ): Boolean {
        return required.any { it !in actual }
    }

    data class Frequency(val rx: Double, val tx: Double)
}

