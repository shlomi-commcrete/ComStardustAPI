package com.commcrete.stardust.stardust.model


import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.stardust.model.config.AirEncryptionMode
import com.commcrete.stardust.stardust.model.config.AntennaType
import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.stardust.model.config.CurrentPreset
import com.commcrete.stardust.stardust.model.config.PortType
import com.commcrete.stardust.stardust.model.config.Preset
import com.commcrete.stardust.stardust.model.config.SnifferMode
import com.commcrete.stardust.stardust.model.config.StardustRDPLevel
import com.commcrete.stardust.stardust.model.config.StardustType

data class StardustConfigurationPackage(

    val presets: List<Preset>,
    //LO Outputs
    var frequencyLOTX: Double,
    var frequencyLORX: Double,
    var powerLOTX: Int,
    var powerLORX: Int,

    //Current Preset
    val currentPreset: CurrentPreset,

    var gpsEnabled: Boolean,
    var stardustType: StardustType,
    var portType: PortType,
    var crcType: Int,
    var airEncryptionMode: AirEncryptionMode,
    var snifferMode: SnifferMode,
    var appId: String,
    var stardustId: String,
    var logDebugMode: Int,
    var logStreamEnable: Int,
    val antenna: AntennaType,
    var radioLODeduction: Float,
    var radioXcvr4Deduction: Float,
    var relayMode: Int,
    var power12V: Float,
    var powerBattery: Float,
    var mcuTemperature: Int,
    var rdpLevel: StardustRDPLevel,
    var licenseType: LicenseType,
    val deviceModel: String,
    val deviceSerial: String,
    val sosXCVR: Int,
    val sosDestinations: List<String>
) {

    internal fun getPresetData(preset : CurrentPreset): Preset? {
        return presets[preset.value]
    }

    fun getCenterFrequency(preset : CurrentPreset = currentPreset): Frequency? {
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

    fun presetsWithoutConfig(): List<Preset> {
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

    private fun Preset.collectFunctionalities(): Pair<Set<FunctionalityType>, Set<FunctionalityType>> {
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
